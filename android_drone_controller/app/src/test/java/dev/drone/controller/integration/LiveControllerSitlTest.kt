package dev.drone.controller.integration

import dev.drone.controller.command.ArmCommandStatus
import dev.drone.controller.command.ArmRequestResult
import dev.drone.controller.control.ArmState
import dev.drone.controller.mavlink.*
import dev.drone.controller.transport.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Real upstream ArduCopter, through the SAME BenchSession implementation shipped
 * in the APK. All network endpoints below are loopback; ARM is simulation only.
 * RC_OPTIONS32 retains the throttle-idle arming check and a simulated receiver.
 * No ARMING_CHECK, failsafe or actual hardware parameter is changed by this test.
 */
class LiveControllerSitlTest {
    @Test fun liveFourChannelBenchWritesDistinctAxesAndReconnectNeverReplaysThrottle() {
        Simulator(32).use { simulator ->
            val wire=WireObservation()
            BenchSession(writer=wire).use { session ->
                simulator.awaitListening()
                session.foreground(true)
                session.selectControlMode(ControlProfile.BENCH_4CH)
                session.connect("127.0.0.1",simulator.port)
                configured(session)
                assertFalse("Bench cannot enable without physical isolation confirmation",session.enableControl(true,false,true))
                assertTrue("Fresh calibrated four-channel bench can enable: ${session.snapshot().live}",session.enableControl(true,true,true))
                val expected=listOf(1660,1380,1300,1540)
                pump(session,"All four requested PWM values reach ArduPilot RC input",3000, .4f,-.3f,.25f,.1f) {
                    session.snapshot().engine.received?.take(4)==expected
                }
                val baselineAux=session.snapshot().engine.received!!.drop(4)
                pumpFor(session,1500,.4f,-.3f,.25f,.1f)
                assertEquals(expected,session.snapshot().engine.received!!.take(4))
                assertEquals("Auxiliary channels remain untouched",baselineAux,session.snapshot().engine.received!!.drop(4))
                assertEquals(ArmState.DISARMED,session.snapshot().engine.arm)
                assertTrue("Production scheduler must write multiple 20Hz frames",wire.rcFrames().size>=20)
                assertTrue("No ARM command during bench",wire.commandFrames(400).isEmpty())
                assertTiming(wire)
                // Stop refreshing the production input/watchdog. No UI event or old
                // throttle may restart it once the lease is revoked.
                Thread.sleep(450)
                assertFalse(session.snapshot().live.authority.canTransmit)
                val stopped=wire.rcFrames().size
                Thread.sleep(250)
                assertEquals("Expired input must stop RC transmission",stopped,wire.rcFrames().size)
                session.disconnect("SITL test deliberate disconnect")
                session.connect("127.0.0.1",simulator.port)
                configured(session)
                val reconnected=wire.rcFrames().size
                Thread.sleep(650)
                assertEquals("Reconnect sends no old RC without a new operator grant",reconnected,wire.rcFrames().size)
                assertFalse(session.snapshot().live.authority.canTransmit)
                assertTrue(session.snapshot().live.lastRequested.let{it.isEmpty()||it.getOrNull(2)==1100})
                println("LIVE_SITL four-axis=$expected, writes=$stopped, reconnect replay=0; hardware NOT VERIFIED")
            }
        }
    }

    @Test fun liveArmModeAckAndFreshHeartbeatKeepRcActiveAcrossArmedTransition() {
        Simulator(32).use { simulator ->
            val wire=WireObservation()
            BenchSession(writer=wire).use { session ->
                simulator.awaitListening()
                session.foreground(true)
                session.selectControlMode(ControlProfile.CONTROL)
                session.connect("127.0.0.1",simulator.port)
                configured(session)
                assertTrue("ARM before an operator control grant must be rejected",session.requestArm(true,true) is ArmRequestResult.Rejected)
                assertTrue("Enable explicit ground-control session: ${session.snapshot().live}",session.enableControl(true,false,true))
                pump(session,"PreArm/SYS_STATUS/EKF/ground/RC prerequisites",45000) { session.snapshot().live.armBlocker==null }
                assertTrue("Missing active confirmation must deny ARM",session.requestArm(true,false) is ArmRequestResult.Rejected)
                assertTrue("Mode command must be explicitly confirmed",session.requestFlightMode(1,true))
                pump(session,"ACRO mode ACK and later heartbeat",4000) {
                    val hb=session.snapshot().engine.telemetry["Heartbeat"] as? Telemetry.Heartbeat
                    hb?.customMode==1L && session.snapshot().live.modeCommand.heartbeatConfirmed
                }
                assertTrue(session.requestFlightMode(0,true))
                pump(session,"STABILIZE mode ACK and later heartbeat",4000) {
                    val hb=session.snapshot().engine.telemetry["Heartbeat"] as? Telemetry.Heartbeat
                    hb?.customMode==0L && session.snapshot().live.modeCommand.heartbeatConfirmed
                }
                val beforeArm=wire.rcFrames().size
                assertTrue("Simulation normal ARM intent accepted: ${session.snapshot().live}",session.requestArm(true,true) is ArmRequestResult.Accepted)
                pump(session,"ARM ACK plus a fresh later armed heartbeat",5000) {
                    session.snapshot().engine.arm==ArmState.ARMED && session.snapshot().live.armCommand.status==ArmCommandStatus.CONFIRMED
                }
                assertTrue("Expected ARMED heartbeat must preserve production RC authority",session.snapshot().live.authority.canTransmit)
                val armedStart=wire.rcFrames().size
                val expected=listOf(1560,1460,1140,1540)
                pump(session,"Four axes continue after ARMED",2500,.15f,-.1f,.05f,.1f) {
                    session.snapshot().engine.received?.take(4)==expected
                }
                pumpFor(session,1000,.15f,-.1f,.05f,.1f)
                assertTrue("RC packets continue after armed transition",wire.rcFrames().size-armedStart>=12)
                assertTrue("RC crossed the transition",armedStart>beforeArm)
                pump(session,"Return throttle to min and fresh echo",2000) {
                    session.snapshot().engine.received?.take(4)==listOf(1500,1500,1100,1500)
                }
                assertTrue("Explicit simulated on-ground DISARM accepted",session.requestArm(false,true) is ArmRequestResult.Accepted)
                pump(session,"DISARM ACK plus a fresh later disarmed heartbeat",5000) {
                    session.snapshot().engine.arm==ArmState.DISARMED && session.snapshot().live.armCommand.status==ArmCommandStatus.CONFIRMED
                }
                val command400=wire.commandFrames(400)
                assertEquals("Exactly one normal ARM and one DISARM; never retries",2,command400.size)
                command400.forEach { frame ->
                    val p=ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals("No force magic is ever encoded",0f,p.getFloat(4),0f)
                }
                assertEquals("Only readback parameters: no PARAM_SET",0,wire.frames().count{it.messageId==23})
                assertTiming(wire)
                println("LIVE_SITL normal ARM/DISARM ACK+HB confirmed; armed four-axis=$expected; noforce/noPARAM_SET")
            }
        }
    }

    /** Independent negative reference checks ArduPilot's own normal ARM rejection;
     * the production API separately rejects unsafe local evidence above. This
     * intentionally does not bypass the production PreArm gate to create a test.
     */
    @Test fun simulatorItselfRejectsUnforcedArmWhenThrottleIsHigh() {
        Simulator(32).use { simulator ->
            simulator.awaitListening()
            Socket("127.0.0.1",simulator.port).use { socket ->
                socket.soTimeout=100
                val encoder=Mavlink2Encoder();val parser=Mavlink2Parser();val bytes=ByteArray(8192)
                val out=socket.getOutputStream();val input=socket.getInputStream()
                var heartbeat:Telemetry.Heartbeat?=null
                var preArm=false
                var next=0L
                val until=System.nanoTime()+45_000_000_000L
                while(System.nanoTime()<until&&!preArm) {
                    if(System.nanoTime()>=next) {
                        out.write(encoder.heartbeat());out.write(encoder.rcOverride(42,1,intArrayOf(1500,1500,1100,1500)))
                        next=System.nanoTime()+50_000_000
                    }
                    try {
                        val n=input.read(bytes);if(n<0)error("Simulator disconnected")
                        parser.feed(bytes,0,n).forEach { frame ->
                            when(val t=TelemetryDecoder.decode(frame)) {
                                is Telemetry.Heartbeat -> heartbeat=t
                                is Telemetry.SysStatus -> preArm=t.sensorsHealthy and (1L shl 28)!=0L
                                else -> Unit
                            }
                        }
                    } catch(_:java.net.SocketTimeoutException) { }
                }
                assertNotNull(heartbeat);assertTrue("Baseline simulator PreArm must first be healthy",preArm)
                // This reference sender is deliberately NOT the APK; its purpose
                // is to observe FC rejection without weakening APK validation.
                out.write(encoder.rcOverride(42,1,intArrayOf(1500,1500,1800,1500)))
                Thread.sleep(150)
                out.write(encoder.armDisarm(42,1,true));out.flush()
                var ack:Telemetry.CommandAck?=null
                var armed=false
                val rejectUntil=System.nanoTime()+3_000_000_000
                while(System.nanoTime()<rejectUntil) {
                    try {
                        val n=input.read(bytes);if(n<0)break
                        parser.feed(bytes,0,n).forEach { frame ->
                            when(val t=TelemetryDecoder.decode(frame)) {
                                is Telemetry.CommandAck -> if(t.command==400)ack=t
                                is Telemetry.Heartbeat -> armed=armed||t.armed
                                else -> Unit
                            }
                        }
                    } catch(_:java.net.SocketTimeoutException) { }
                }
                assertNotNull("Actual ArduPilot ARM rejection ACK",ack)
                assertTrue("ArduPilot must deny an unforced high-throttle ARM",ack!!.result in setOf(1,2,4))
                assertFalse("Simulator remains disarmed after denial",armed)
                println("SITL reference negative ARM high-throttle ACK=${ack!!.result}; APK local gate not bypassed")
            }
        }
    }

    private fun configured(session:BenchSession) {
        val names=(1..4).flatMap{listOf("RC${it}_MIN","RC${it}_TRIM","RC${it}_MAX","RC${it}_REVERSED")}+
            listOf("RCMAP_ROLL","RCMAP_PITCH","RCMAP_THROTTLE","RCMAP_YAW","RC_OPTIONS","RC_OVERRIDE_TIME")
        await("Read-only FC configuration and fresh heartbeat: ${session.snapshot().tcpStatus}",30000) {
            val e=session.snapshot().engine
            e.arm==ArmState.DISARMED && names.all{it in e.parameters} && ("MAV_GCS_SYSID" in e.parameters || "SYSID_MYGCS" in e.parameters)
        }
    }
    private fun pump(session:BenchSession,label:String,timeoutMs:Long,roll:Float=0f,pitch:Float=0f,throttle:Float=0f,yaw:Float=0f,condition:()->Boolean) {
        val until=System.nanoTime()+timeoutMs*1_000_000L
        while(System.nanoTime()<until) {
            session.controlInput(roll,pitch,throttle,yaw);session.controlPulse()
            if(condition())return
            Thread.sleep(20)
        }
        assertTrue("Timeout $label; snapshot=${session.snapshot()}",condition())
    }
    private fun pumpFor(session:BenchSession,durationMs:Long,roll:Float,pitch:Float,throttle:Float,yaw:Float) {
        val until=System.nanoTime()+durationMs*1_000_000L
        while(System.nanoTime()<until){session.controlInput(roll,pitch,throttle,yaw);session.controlPulse();Thread.sleep(20)}
    }
    private fun assertTiming(wire:WireObservation) {
        val intervals=wire.rcTimes().zipWithNext{a,b->(b-a)/1_000_000.0}.sorted()
        assertTrue("Enough measured production scheduler intervals",intervals.size>=10)
        val p50=intervals[intervals.size/2];val p95=intervals[((intervals.size-1)*.95).toInt()]
        assertTrue("Scheduler must not send faster than its 20Hz budget: p50=$p50",p50>=40)
        assertTrue("20Hz target must remain usable under host scheduling: p95=$p95",p95<120)
        println("LIVE_SITL socket-frame interval p50=${p50}ms p95=${p95}ms; not radio or end-to-end latency")
    }

    private class WireObservation:ChannelWriter {
        private val parser=Mavlink2Parser()
        private val received=mutableListOf<MavFrame>()
        private val times=mutableListOf<Long>()
        override fun write(channel:SocketChannel,bytes:ByteBuffer):Int {
            val copy=bytes.duplicate();val count=channel.write(bytes)
            if(count>0) {
                val sent=ByteArray(count);copy.get(sent)
                val parsed=parser.feed(sent)
                synchronized(this) { received.addAll(parsed);parsed.filter{it.messageId==70}.forEach{times.add(System.nanoTime())} }
            }
            return count
        }
        @Synchronized fun frames()=received.toList()
        fun rcFrames()=frames().filter{it.messageId==70}
        fun commandFrames(command:Int)=frames().filter{it.messageId==76&&it.payload.size>=30&&(ByteBuffer.wrap(it.payload).order(ByteOrder.LITTLE_ENDIAN).getShort(28).toInt() and 65535)==command}
        @Synchronized fun rcTimes()=times.toList()
    }

    private class Simulator(rcOptions:Int):Closeable {
        val port=ServerSocket(0).use{it.localPort}
        private val directory=run {
            val root=System.getenv("DRONE_SITL_EVIDENCE_DIR")?.let{File(it).apply{mkdirs()}}
            if(root==null)Files.createTempDirectory("drone-live-sitl-").toFile()
            else Files.createTempDirectory(root.toPath(),"drone-live-sitl-").toFile()
        }
        private val log=File(directory,"sitl.log")
        private val process:Process
        init {
            val binary=System.getenv("DRONE_SITL_BINARY")
            assumeTrue("BLOCKED: DRONE_SITL_BINARY missing; real SITL NOT VERIFIED",binary!=null&&File(binary).canExecute())
            val identity=if(System.getenv("DRONE_SITL_PARAM_PROFILE")=="modern")"MAV_SYSID 42\nMAV_GCS_SYSID 255\n" else "SYSID_THISMAV 42\nSYSID_MYGCS 255\n"
            val defaults=File(directory,"ground-control.parm").apply {
                writeText("FRAME_CLASS 1\nFRAME_TYPE 1\n"+identity+"SERIAL0_PROTOCOL 2\nRC_OPTIONS $rcOptions\nRC_OVERRIDE_TIME 0.5\nRC_FS_TIMEOUT 0.5\nLOG_DISARMED 1\n"+
                    (1..4).joinToString(""){"RC${it}_MIN 1100\nRC${it}_TRIM 1500\nRC${it}_MAX 1900\n"})
            }
            process=ProcessBuilder(binary!!,"--model","quad","--speedup","1","--serial0","tcp:$port","--defaults",defaults.absolutePath,"--home","-35.363261,149.165230,584,0")
                .directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        }
        fun awaitListening()=await("SITL listening (${log.absolutePath})",10000) {
            assertTrue("Simulator exited: ${if(log.exists())log.readText().takeLast(3000)else "no log"}",process.isAlive)
            log.exists()&&log.readText().contains("bind port $port")
        }
        override fun close() { process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();println("LIVE_SITL evidence directory: ${directory.absolutePath}") }
    }
    companion object {
        private fun await(label:String,timeoutMs:Long,condition:()->Boolean) {
            val until=System.nanoTime()+timeoutMs*1_000_000L
            while(System.nanoTime()<until){if(condition())return;Thread.sleep(20)}
            assertTrue("Timeout: $label",condition())
        }
    }
}
