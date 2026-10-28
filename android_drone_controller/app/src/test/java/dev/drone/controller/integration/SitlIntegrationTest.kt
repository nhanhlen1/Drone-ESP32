package dev.drone.controller.integration

import dev.drone.controller.control.ArmState
import dev.drone.controller.control.SystemHealth
import dev.drone.controller.mavlink.*
import dev.drone.controller.transport.BenchSession
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/** Runs a real, separately launched upstream ArduCopter SITL. Never a physical FC.
 * Set DRONE_SITL_BINARY explicitly; absent binary reports JUnit skipped, never PASS.
 */
class SitlIntegrationTest {
    @Test fun reported1470PathHasIndependentReceiverlessWireRegression() {
        Sitl(33).use { sitl ->
            sitl.awaitListening()
            Socket("127.0.0.1",sitl.port).use { socket ->
                socket.soTimeout=100;socket.tcpNoDelay=true
                val encoder=Mavlink2Encoder();val parser=Mavlink2Parser()
                val input=socket.getInputStream();val output=socket.getOutputStream();val bytes=ByteArray(8192)
                var target:MavFrame?=null
                val until=System.nanoTime()+20_000_000_000
                while(System.nanoTime()<until&&target==null) {
                    output.write(encoder.heartbeat());output.flush()
                    try {
                        val count=input.read(bytes)
                        if(count<0)error("SITL closed before heartbeat")
                        target=parser.feed(bytes,0,count).firstOrNull {
                            (TelemetryDecoder.decode(it) as? Telemetry.Heartbeat)?.let{hb->!hb.armed&&hb.autopilot==3&&hb.systemStatus in 3..5}==true
                        }
                    }catch(_:java.net.SocketTimeoutException){ }
                }
                assertNotNull("SITL target discovery",target)
                val peer=target!!
                output.write(encoder.setMessageInterval(peer.systemId,peer.componentId,65,200000));output.flush()
                for(pwm in listOf(1470,1500,1530)) {
                    var observed=false
                    val deadline=System.nanoTime()+1_000_000_000
                    var nextSend=0L
                    while(System.nanoTime()<deadline&&!observed) {
                        if(System.nanoTime()>=nextSend) {
                            output.write(encoder.rcOverride(peer.systemId,peer.componentId,intArrayOf(pwm,65535,65535,65535)));output.flush()
                            nextSend=System.nanoTime()+50_000_000
                        }
                        try {
                            val count=input.read(bytes)
                            if(count<0)error("SITL closed during RC observation")
                            parser.feed(bytes,0,count).forEach { frame ->
                                when(val value=TelemetryDecoder.decode(frame)) {
                                    is Telemetry.Heartbeat -> assertFalse("Simulation must remain disarmed",value.armed)
                                    is Telemetry.RcChannels -> if(value.channels[0]==pwm) {
                                        assertTrue("Ignored channels remain absent",value.channels.drop(1).all{it==0})
                                        observed=true
                                    }
                                    else -> Unit
                                }
                            }
                        }catch(_:java.net.SocketTimeoutException){ }
                    }
                    assertTrue("Receiverless ArduPilot CH1=$pwm",observed)
                }
                println("SITL independent encoder CH1=1470/1500/1530 observed with channels2–18 ignored; not Google AI source verification")
            }
        }
    }
    @Test fun realBenchSessionReceiverlessCh1ReachesArduPilotRadioInputAndStops() {
        Sitl(33).use { sitl ->
            BenchSession().use { session ->
                session.foreground(true)
                sitl.awaitListening()
                session.connect("127.0.0.1",sitl.port)
                try {
                    await("SITL fresh DISARMED + receiverless diagnostic observations",20000) {
                        val e=session.snapshot().engine
                        e.arm==ArmState.DISARMED && (e.systemHealth in setOf(SystemHealth.STANDBY,SystemHealth.ACTIVE) || e.criticalRcDiagnosticEligible)
                    }
                } catch(error:AssertionError) {
                    throw AssertionError("SITL session readiness failed; snapshot=${session.snapshot()}",error)
                }
                session.configure(1,1100,1900,1500,true)
                session.engine.confirmBenchConditions(true,true,true,true)
                session.enableTest(); assertTrue(session.snapshot().engine.enabled)
                session.singleChannel(1,1f)
                val deadline=System.nanoTime()+700_000_000L
                var reached=false
                while(System.nanoTime()<deadline) {
                    session.deadman(true)
                    if(session.snapshot().engine.received?.get(0)==1520) reached=true
                    Thread.sleep(30)
                }
                assertTrue("Real ArduPilot rc().get_radio_in CH1 must observe1520",reached)
                await("absolute lease revoked",1500) { !session.snapshot().engine.enabled }
                val sent=session.snapshot().txOverrides
                assertTrue(sent in 1..16)
                Thread.sleep(250)
                assertEquals(sent,session.snapshot().txOverrides)
                assertFalse("SITL does not unlock real hardware acceptance",session.snapshot().engine.inputVerified)
                assertEquals(ArmState.DISARMED,session.snapshot().engine.arm)
                assertTrue(session.snapshot().streamRequests.isNotEmpty())
                println("SITL receiverless real BenchSession: txOverrides=$sent CH1 radio_in1520 observed; hardware NOT VERIFIED")
            }
        }
    }

    @Test fun referenceEncoderBothMethodsMapAllAxesAndOverrideTimeoutReturnsToSimulatedReceiver() {
        Sitl(0).use { sitl ->
            sitl.awaitListening()
            Socket("127.0.0.1",sitl.port).use { socket ->
                socket.soTimeout=200;socket.tcpNoDelay=true
                val encoder=Mavlink2Encoder();val parser=Mavlink2Parser()
                val out=socket.getOutputStream();val input=socket.getInputStream()
                val frames=ArrayList<MavFrame>();val bytes=ByteArray(8192)
                fun exchange(send:ByteArray?=null,untilMs:Long=250):List<Telemetry> {
                    if(send!=null){out.write(send);out.flush()}
                    val until=System.nanoTime()+untilMs*1_000_000
                    val decoded=ArrayList<Telemetry>()
                    while(System.nanoTime()<until) {
                        try {
                            val n=input.read(bytes)
                            if(n<0)break
                            val incoming=parser.feed(bytes,0,n);frames.addAll(incoming)
                            incoming.mapNotNullTo(decoded){TelemetryDecoder.decode(it)}
                        } catch(_:java.net.SocketTimeoutException) { }
                    }
                    return decoded
                }
                var hb:Telemetry.Heartbeat?=null
                val bootUntil=System.nanoTime()+20_000_000_000L
                while(System.nanoTime()<bootUntil && hb==null) {
                    hb=exchange(encoder.heartbeat(),500).filterIsInstance<Telemetry.Heartbeat>().firstOrNull { !it.armed && it.systemStatus in 3..4 }
                }
                assertNotNull("SITL heartbeat",hb)
                val peer=frames.last{it.messageId==0 && TelemetryDecoder.decode(it) is Telemetry.Heartbeat}
                val system=peer.systemId;val component=peer.componentId
                exchange(encoder.setMessageInterval(system,component,65,200000))
                var latest=exchange(untilMs=500).filterIsInstance<Telemetry.RcChannels>().lastOrNull()
                assertNotNull(latest)
                val baseline=latest!!.channels.copyOf()
                // Only simulation: these values never enter the production BenchSession.
                val expected=intArrayOf(1540,1460,1140,1540)
                val wrongSender=Mavlink2Encoder(254,190)
                val rejected=ArrayList<Telemetry.RcChannels>()
                repeat(3) {
                    rejected.addAll(exchange(wrongSender.rcOverride(system,component,expected),200).filterIsInstance<Telemetry.RcChannels>())
                }
                assertTrue("negative sender test must receive RC observations",rejected.isNotEmpty())
                assertTrue("wrong GCS system ID cannot override RC input",rejected.all{baseline.take(4)==it.channels.take(4)})
                var applied=false
                repeat(6) {
                    val observations=exchange(encoder.rcOverride(system,component,expected),100).filterIsInstance<Telemetry.RcChannels>()
                    assertTrue("RC auxiliary channels stay ignored",observations.all{baseline.drop(4)==it.channels.drop(4)})
                    if(observations.any { rc -> expected.indices.all{rc.channels[it]==expected[it]} }) applied=true
                }
                assertTrue("upstream applies CH1–4 exact PWM override",applied)
                val after=exchange(untilMs=1800).filterIsInstance<Telemetry.RcChannels>()
                assertTrue("500ms override expiry must return to simulated physical receiver",after.any { baseline.take(4)==it.channels.take(4) })
                // x=pitch is inverted by Copter; y=roll; z=throttle0..1000;r=yaw.
                val manualExpected=intArrayOf(1540,1460,1140,1540)
                var manualApplied=false
                repeat(6) {
                    if(exchange(encoder.manualControl(system,100,100,50,100),100).filterIsInstance<Telemetry.RcChannels>().any { rc -> manualExpected.indices.all{rc.channels[it]==manualExpected[it]} }) manualApplied=true
                }
                assertTrue("MANUAL_CONTROL upstream axis mapping",manualApplied)
                assertTrue(frames.mapNotNull{TelemetryDecoder.decode(it)}.filterIsInstance<Telemetry.Heartbeat>().none{it.armed})
                println("SITL direct Kotlin encoder: both RC methods CH1–4 observed; timeout returns receiver baseline=${baseline.take(4)}; no ARM")
            }
        }
    }

    private class Sitl(rcOptions:Int):Closeable {
        val port=ServerSocket(0).use { it.localPort }
        private val directory=run {
            val evidence=System.getenv("DRONE_SITL_EVIDENCE_DIR")?.let { File(it).apply { mkdirs() } }
            if(evidence==null)Files.createTempDirectory("drone-sitl-").toFile()
            else Files.createTempDirectory(evidence.toPath(),"drone-sitl-").toFile()
        }
        private val process:Process
        private val log=File(directory,"sitl.log")
        init {
            val binary=System.getenv("DRONE_SITL_BINARY")
            assumeTrue("DRONE_SITL_BINARY not provided: SITL NOT VERIFIED",binary!=null && File(binary).canExecute())
            val defaults=File(directory,"bench.parm").apply {
                val modern=System.getenv("DRONE_SITL_PARAM_PROFILE")=="modern"
                val identity=if(modern) "MAV_SYSID 42\nMAV_GCS_SYSID 255\n" else "SYSID_THISMAV 42\nSYSID_MYGCS 255\n"
                writeText("FRAME_CLASS 1\nFRAME_TYPE 1\n"+identity+"SERIAL0_PROTOCOL 2\nRC_OPTIONS $rcOptions\nRC_OVERRIDE_TIME 0.5\nRC_FS_TIMEOUT 0.5\nLOG_DISARMED 1\n"+
                    (1..4).joinToString(""){"RC${it}_MIN 1100\nRC${it}_TRIM 1500\nRC${it}_MAX 1900\n"})
            }
            process=ProcessBuilder(binary!!,"--model","quad","--speedup","1","--serial0","tcp:$port", "--defaults",defaults.absolutePath,"--home","-35.363261,149.165230,584,0")
                .directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        }
        fun awaitListening() {
            await("SITL listening: ${log.absolutePath}",10000) {
                assertTrue("SITL exited: ${log.readText().takeLast(3000)}",process.isAlive)
                log.exists() && log.readText().contains("bind port $port")
            }
        }
        override fun close() {
            process.destroy();if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly()
            println("SITL evidence directory: ${directory.absolutePath}")
        }
    }
    companion object {
        private fun await(label:String,timeout:Long,condition:()->Boolean) {
            val until=System.nanoTime()+timeout*1_000_000
            while(System.nanoTime()<until){if(condition())return;Thread.sleep(20)}
            assertTrue("Timeout: $label",condition())
        }
    }
}
