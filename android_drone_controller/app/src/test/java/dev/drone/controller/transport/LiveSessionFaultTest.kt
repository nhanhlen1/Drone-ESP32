package dev.drone.controller.transport

import dev.drone.controller.control.ArmState
import dev.drone.controller.control.AuthorityStage
import dev.drone.controller.mavlink.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** These exercise the production socket actor with a modeled localhost peer.
 * They are transport/failsafe evidence, not ArduPilot or hardware acceptance.
 */
class LiveSessionFaultTest {
    @Test fun fragmentedFourChannelControlContinuesAfterArmedAtOneTwentyHzScheduler() {
        MockFc().use { fc -> BenchSession().use { session ->
            ready(session,fc)
            Pulse(session).use { pulse ->
                enable(session)
                pulse.start()
                await("initial live four-channel echo") { session.snapshot().live.authority.rcEchoVerified }
                fc.armed.set(true)
                await("operator authority survives ARMED") {
                    session.snapshot().live.authority.stage==AuthorityStage.ARMED_CONTROL_CANDIDATE
                }
                val requested=intArrayOf(1660,1380,1580,1420)
                pulse.input.set(floatArrayOf(.4f,-.3f,.6f,-.2f))
                await("four axes on actual TCP") { fc.overrides().count { channels(it.frame).contentEquals(requested) }>=20 }
                session.stop("test completed")
                pulse.pause()
                quiet(session,fc)
                val selected=fc.overrides().filter { channels(it.frame).contentEquals(requested) }
                val elapsed=(selected.last().atNanos-selected.first().atNanos)/1_000_000.0
                val observedHz=(selected.size-1)*1000/elapsed
                assertTrue("single 20Hz target, observed $observedHz Hz",observedHz in 12.0..22.5)
                val intervals=selected.zipWithNext { a,b -> (b.atNanos-a.atNanos)/1_000_000.0 }
                assertTrue("no duplicate RC scheduler/burst",intervals.all{it>=35})
                assertTrue("fragmented inbound headers exercised",fc.fragmented.get()>0)
                assertEquals(0,fc.errors.get())
                fc.overrides().forEach { assertAuxiliaryUntouched(it.frame) }
                assertTrue(session.snapshot().rcTiming.samples>=18)
            }
        } }
    }

    @Test fun heartbeatTimeoutStopsLiveControlWhileOtherTelemetryRemainsActiveAndDoesNotResume() {
        MockFc().use { fc -> BenchSession().use { session ->
            ready(session,fc)
            Pulse(session).use { pulse ->
                enable(session);pulse.start()
                await("live RC") { fc.overrides().size>=3 }
                fc.heartbeatEnabled.set(false)
                await("stale heartbeat revokes authority",3000) { !session.snapshot().live.authority.canTransmit }
                assertEquals(ArmState.UNKNOWN,session.snapshot().engine.arm)
                quiet(session,fc)
                fc.heartbeatEnabled.set(true)
                await("fresh heartbeat returns") { session.snapshot().engine.arm==ArmState.DISARMED }
                quiet(session,fc)
                assertFalse("fresh link alone cannot re-authorize",session.snapshot().live.authority.canTransmit)
            }
        } }
    }

    @Test fun absentRcFeedbackRevokesEvenWithFreshHeartbeatAndOngoingUiInput() {
        MockFc().use { fc -> BenchSession().use { session ->
            ready(session,fc)
            Pulse(session).use { pulse ->
                enable(session);pulse.start()
                await("initial correlated RC") { session.snapshot().live.authority.rcEchoVerified }
                fc.rcEnabled.set(false)
                await("RC feedback freshness expires",2600) { !session.snapshot().live.authority.canTransmit }
                assertEquals(ArmState.DISARMED,session.snapshot().engine.arm)
                assertTrue("HEARTBEAT remained fresh",session.snapshot().engine.heartbeatAgeMs!!<1000)
                quiet(session,fc)
            }
        } }
    }

    @Test fun reconnectClearsAuthorityAndOldHighThrottleBeforeAnyNewConsent() {
        MockFc().use { fc -> BenchSession().use { session ->
            ready(session,fc)
            Pulse(session).use { pulse ->
                enable(session);pulse.start()
                await("initial echo") { session.snapshot().live.authority.rcEchoVerified }
                fc.armed.set(true)
                await("ARMED") { session.snapshot().engine.arm==ArmState.ARMED }
                pulse.input.set(floatArrayOf(.2f,-.2f,.875f,.1f))
                await("old high throttle was on wire") { fc.overrides().any{channels(it.frame)[2]==1800} }
                pulse.pause()
                session.disconnect("modeled Wi-Fi loss")
                val firstConnection=fc.connections.get()
                fc.armed.set(false)
                session.connect("127.0.0.1",fc.port)
                await("fresh connection and parameters") {
                    fc.connections.get()>firstConnection && session.snapshot().engine.arm==ArmState.DISARMED &&
                        session.snapshot().engine.parameters.containsKey("RC4_REVERSED")
                }
                assertFalse(session.snapshot().live.authority.canTransmit)
                assertEquals(1100,session.snapshot().live.lastRequested[2])
                Thread.sleep(180)
                assertTrue("no automatic RC replay",fc.overrides().none{it.connection>firstConnection})
                pulse.input.set(floatArrayOf(0f,0f,0f,0f))
                enable(session);pulse.start()
                await("new explicit session emits throttle minimum") { fc.overrides().any{it.connection>firstConnection} }
                fc.overrides().filter{it.connection>firstConnection}.forEach {
                    assertEquals(1100,channels(it.frame)[2])
                }
            }
        } }
    }

    @Test fun actualPartialAndZeroWritesPreserveFourChannelFramesAndSingleSequence() {
        MockFc().use { fc ->
            val calls=AtomicInteger();val partial=AtomicInteger();val zero=AtomicInteger()
            BenchSession(writer=ChannelWriter { channel,bytes ->
                if(calls.incrementAndGet()%4==0){zero.incrementAndGet();0}
                else {
                    val limit=bytes.limit()
                    try {
                        if(bytes.remaining()>3){bytes.limit(bytes.position()+3);partial.incrementAndGet()}
                        channel.write(bytes)
                    } finally {bytes.limit(limit)}
                }
            }).use { session ->
                ready(session,fc,ControlProfile.BENCH_4CH)
                Pulse(session).use { pulse ->
                    enable(session);pulse.input.set(floatArrayOf(-.5f,.25f,.5f,.75f));pulse.start()
                    val expected=intArrayOf(1300,1600,1500,1800)
                    await("complete short-write four-channel frames") { fc.overrides().count{channels(it.frame).contentEquals(expected)}>=5 }
                    session.stop("end short-write test");pulse.pause();quiet(session,fc)
                    assertTrue(partial.get()>0);assertTrue(zero.get()>0)
                    assertEquals(0,fc.errors.get())
                    assertTrue(fc.frames.all{it.frame.sequenceDisposition !in setOf(SequenceDisposition.DUPLICATE,SequenceDisposition.REORDERED)})
                    fc.overrides().forEach{assertAuxiliaryUntouched(it.frame)}
                }
            }
        }
    }

    @Test fun revocationDuringPartialLiveRcClosesSocketWithoutCompletingTheOldFrame() {
        MockFc().use { fc ->
            val started=AtomicBoolean(false)
            BenchSession(writer=ChannelWriter { channel,bytes ->
                val rc=bytes.get(7).toInt() and 255==70
                if(rc && bytes.position()>0)0 else {
                    val limit=bytes.limit()
                    try {
                        if(rc)bytes.limit(bytes.position()+3)
                        val n=channel.write(bytes)
                        if(rc&&n>0)started.set(true)
                        n
                    } finally {bytes.limit(limit)}
                }
            }).use { session ->
                ready(session,fc)
                enable(session);session.controlPulse()
                await("RC prefix actually written") { started.get() }
                session.stop("operator stop during partial frame")
                await("partial frame forces close") { !session.snapshot().engine.connected }
                assertFalse(session.snapshot().live.authority.canTransmit)
                assertTrue(fc.overrides().isEmpty())
                quiet(session,fc)
            }
        }
    }

    @Test fun zeroWriteBackpressureCannotReplayAnOutdatedJoystickFrameWhenWritableAgain() {
        MockFc().use { fc ->
            val block=AtomicBoolean(true);val attempts=AtomicInteger()
            BenchSession(writer=ChannelWriter { channel,bytes ->
                if(bytes.get(7).toInt() and 255==70 && block.get()){attempts.incrementAndGet();0}
                else channel.write(bytes)
            }).use { session ->
                ready(session,fc,ControlProfile.BENCH_4CH)
                Pulse(session).use { pulse ->
                    enable(session);pulse.input.set(floatArrayOf(-.75f,.5f,.25f,-.5f));pulse.start()
                    await("backpressured RC selected") { attempts.get()>5 }
                    pulse.input.set(floatArrayOf(.75f,-.5f,.75f,.5f))
                    // Renew the final input synchronously before making TCP writable.
                    session.controlInput(.75f,-.5f,.75f,.5f);session.controlPulse()
                    block.set(false)
                    await("latest-only RC after zero writes") { fc.overrides().size>=3 }
                    session.stop("end backpressure test");pulse.pause()
                    val expected=intArrayOf(1800,1300,1700,1700)
                    fc.overrides().forEach { assertArrayEquals(expected,channels(it.frame)) }
                    assertEquals(0,fc.errors.get())
                }
            }
        }
    }

    private fun ready(session:BenchSession,fc:MockFc,profile:ControlProfile=ControlProfile.CONTROL) {
        session.foreground(true);session.selectControlMode(profile);session.connect("127.0.0.1",fc.port)
        await("verified FC and read-only mapping/calibrations",4000) {
            val s=session.snapshot()
            s.engine.arm==ArmState.DISARMED && s.engine.parameters.containsKey("RC4_REVERSED") &&
                s.live.authority.stage==AuthorityStage.TELEMETRY_READY
        }
    }
    private fun enable(session:BenchSession) {
        assertTrue("explicit propeller-off session: ${session.snapshot().live.authority.reason}",session.enableControl(true,true,true))
    }
    private fun quiet(session:BenchSession,fc:MockFc) {
        Thread.sleep(90)
        val before=fc.overrides().size;val written=session.snapshot().txOverrides
        Thread.sleep(220)
        assertEquals("no new wire RC after revoke",before,fc.overrides().size)
        assertEquals(written,session.snapshot().txOverrides)
    }
    private fun await(label:String,timeout:Long=3000,condition:()->Boolean) {
        val deadline=System.nanoTime()+timeout*1_000_000
        while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(10)}
        assertTrue("timeout: $label",condition())
    }
    private fun channels(frame:MavFrame)=IntArray(4){frame.payload.u16(it*2)}
    private fun assertAuxiliaryUntouched(frame:MavFrame) {
        assertEquals(42,frame.payload.u8(16));assertEquals(1,frame.payload.u8(17))
        val padded=frame.payload.copyOf(38)
        for(i in 4..7)assertEquals(65535,padded.u16(i*2))
        for(i in 8..17)assertEquals(0,padded.u16(18+(i-8)*2))
    }
    private class Pulse(private val session:BenchSession):Closeable {
        val input=AtomicReference(floatArrayOf(0f,0f,0f,0f))
        private val active=AtomicBoolean(false)
        private val executor=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"mock-live-ui-pulse").apply{isDaemon=true}}
        init {executor.scheduleAtFixedRate({if(active.get())send()},0,40,TimeUnit.MILLISECONDS)}
        private fun send(){val i=input.get();session.controlInput(i[0],i[1],i[2],i[3]);session.controlPulse()}
        fun start(){active.set(true);send()}
        fun pause(){active.set(false)}
        override fun close(){pause();executor.shutdownNow()}
    }
    private data class Capture(val frame:MavFrame,val connection:Int,val atNanos:Long)
    private class MockFc:Closeable {
        private val server=ServerSocket(0,8,InetAddress.getByAddress(byteArrayOf(127,0,0,1)))
        val port:Int get()=server.localPort
        val armed=AtomicBoolean(false)
        val heartbeatEnabled=AtomicBoolean(true)
        val rcEnabled=AtomicBoolean(true)
        val connections=AtomicInteger()
        val fragmented=AtomicInteger()
        val errors=AtomicInteger()
        val frames=CopyOnWriteArrayList<Capture>()
        private val peers=CopyOnWriteArrayList<Peer>()
        private val running=AtomicBoolean(true)
        private val emitter=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"mock-live-fc-tx").apply{isDaemon=true}}
        private val acceptor=Thread({
            while(running.get())try {
                val socket=server.accept().apply{tcpNoDelay=true;soTimeout=50}
                peers.add(Peer(socket,connections.incrementAndGet()))
            }catch(_:SocketException){if(running.get())errors.incrementAndGet()}
        },"mock-live-fc-accept").apply{isDaemon=true;start()}
        private val params=linkedMapOf(
            "MAV_GCS_SYSID" to 255f,"MAV_GCS_SYSID_HI" to 0f,"RC_OPTIONS" to 33f,"RC_OVERRIDE_TIME" to .5f,
            "RCMAP_ROLL" to 1f,"RCMAP_PITCH" to 2f,"RCMAP_THROTTLE" to 3f,"RCMAP_YAW" to 4f,
        ).apply{for(n in 1..4){put("RC${n}_MIN",1100f);put("RC${n}_MAX",1900f);put("RC${n}_TRIM",1500f);put("RC${n}_REVERSED",0f)}}
        init{emitter.scheduleAtFixedRate({peers.forEach{it.emit()}},0,50,TimeUnit.MILLISECONDS)}
        fun overrides()=frames.filter{it.frame.messageId==70}
        private inner class Peer(private val socket:Socket,private val id:Int) {
            private var sequence=0
            private var emissions=0
            private val actual=AtomicReference(intArrayOf(1500,1500,1100,1500))
            private val reader=Thread({
                val parser=Mavlink2Parser();val buffer=ByteArray(1024)
                while(running.get()&&!socket.isClosed)try {
                    val count=socket.getInputStream().read(buffer);if(count<0)break
                    val before=parser.diagnostics
                    parser.feed(buffer,0,count).forEach{frame->
                        frames.add(Capture(frame,id,System.nanoTime()))
                        if(frame.messageId==70)actual.set(channelsOf(frame))
                    }
                    val after=parser.diagnostics
                    errors.addAndGet((after.crcErrors-before.crcErrors+after.malformedFrames-before.malformedFrames+after.garbageBytes-before.garbageBytes).toInt())
                }catch(_:SocketTimeoutException){}catch(_:Exception){break}
            },"mock-live-fc-read-$id").apply{isDaemon=true;start()}
            fun emit() {
                if(socket.isClosed)return
                val messages=ArrayList<ByteArray>()
                if(heartbeatEnabled.get())messages+=encode(0,ByteArray(9).apply {
                    this[4]=2;this[5]=3;this[6]=(1 or if(armed.get())128 else 0).toByte();this[7]=(if(armed.get())4 else 3).toByte();this[8]=3
                })
                val mask=(1L shl 28) or 7L
                messages+=encode(1,ByteArray(43).apply {
                    putI32(0,mask.toInt());putI32(4,mask.toInt());putI32(8,mask.toInt());putU16(14,11800);putU16(16,65535);this[30]=(-1).toByte()
                })
                messages+=encode(193,ByteArray(22).apply{putU16(20,63)})
                messages+=encode(245,byteArrayOf(0,1))
                if(rcEnabled.get())messages+=encode(65,ByteArray(42).apply {
                    putI32(0,1000+emissions*50);val values=actual.get();for(i in 0..17)putU16(4+i*2,values.getOrElse(i){0})
                    this[40]=4;this[41]=(-1).toByte()
                })
                if(emissions%10==0)params.entries.forEachIndexed{index,entry->
                    messages+=encode(22,ByteArray(25).apply {
                        putF32(0,entry.value);putU16(4,params.size);putU16(6,index);entry.key.toByteArray(Charsets.US_ASCII).copyInto(this,8);this[24]=9
                    })
                }
                emissions++
                val bundle=messages.fold(ByteArray(0)){a,b->a+b}
                try {
                    val output=socket.getOutputStream()
                    if(emissions%2==1&&bundle.size>5){output.write(bundle,0,5);output.flush();Thread.sleep(2);output.write(bundle,5,bundle.size-5);fragmented.incrementAndGet()}
                    else output.write(bundle)
                    output.flush()
                }catch(_:Exception){runCatching{socket.close()}}
            }
            private fun encode(message:Int,payload:ByteArray):ByteArray {
                val bytes=ByteArray(payload.size+12)
                bytes[0]=0xfd.toByte();bytes[1]=payload.size.toByte();bytes[4]=(sequence++ and 255).toByte();bytes[5]=42;bytes[6]=1
                bytes[7]=message.toByte();bytes[8]=(message ushr 8).toByte();bytes[9]=(message ushr 16).toByte()
                payload.copyInto(bytes,10)
                val crc=MavlinkCrc.x25(bytes,1,payload.size+9,CommonDialect.messages.getValue(message).crcExtra)
                bytes.putU16(payload.size+10,crc);return bytes
            }
            private fun channelsOf(frame:MavFrame)=IntArray(4){frame.payload.u16(it*2)}
            fun close(){runCatching{socket.close()};reader.join(500)}
        }
        override fun close(){running.set(false);emitter.shutdownNow();server.close();peers.forEach{it.close()};acceptor.join(500)}
    }
}
