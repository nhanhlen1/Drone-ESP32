package dev.drone.controller.transport

import dev.drone.controller.control.ArmState
import dev.drone.controller.mavlink.MavFrame
import dev.drone.controller.mavlink.Mavlink2Parser
import dev.drone.controller.mavlink.MavlinkCrc
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Integration TCP localhost with modeled peer: never evidence of ArduPilot acceptance. */
class BenchSessionTest {
    @Test fun missingRcStreamDoesNotDeadlockButAbsoluteBootstrapExpiresEvenWithFeedback() {
        FakeAutopilot(Heartbeat.DISARMED, 0, IntArray(18)).use { fc ->
            BenchSession().use { session ->
                Pulse(session).use { pulse ->
                    ready(session,fc)
                    assertNull(session.snapshot().engine.received)
                    authorize(session)
                    session.singleChannel(1,1f); pulse.start()
                    await("CH1 frame through actual TCP") { fc.overrides().size>=2 }
                    fc.rcEnabled.set(true)
                    await("correlated modeled feedback") { session.snapshot().engine.feedbackCorrelated }
                    assertFalse(session.snapshot().engine.inputVerified)
                    await("absolute750ms expiration despite correlated feedback") { !session.snapshot().engine.enabled }
                    pulse.pause(); assertQuiet(session,fc)
                    val values=fc.overrides()
                    assertTrue(values.size in 2..16)
                    assertTrue((values.last().atNanos-values.first().atNanos)/1_000_000 < 850)
                    values.forEach {
                        assertArrayEquals(intArrayOf(1520,65535,65535,65535),channels(it.frame))
                        assertWireContract(it.frame)
                    }
                    assertOnlyPermittedOutboundMessages(fc)
                }
            }
        }
    }
    @Test fun unconfirmedConditionsAndUnknownHeartbeatNeverTransmitRc() {
        FakeAutopilot().use { fc ->
            BenchSession().use { session ->
                session.foreground(true); session.connect("127.0.0.1",fc.port)
                await("TCP") { session.snapshot().engine.connected }
                session.configure(1,1100,1900,1500,true)
                session.enableTest();session.deadman(true)
                assertQuiet(session,fc)
                fc.heartbeat.set(Heartbeat.DISARMED)
                await("HB") { session.snapshot().engine.arm==ArmState.DISARMED }
                session.enableTest();session.deadman(true)
                assertQuiet(session,fc)
                assertFalse(session.snapshot().engine.enabled)
            }
        }
    }
    @Test fun armedHeartbeatCrcAndBackgroundRevokeLease() {
        listOf("armed","crc","background").forEach { fault ->
            FakeAutopilot(Heartbeat.DISARMED).use { fc ->
                BenchSession().use { session ->
                    Pulse(session).use { pulse ->
                        ready(session,fc);authorize(session);session.singleChannel(1,-1f);pulse.start()
                        await("initial RC before $fault") { fc.overrides().isNotEmpty() }
                        when(fault) {
                            "armed" -> fc.heartbeat.set(Heartbeat.ARMED)
                            "crc" -> fc.corrupt.set(true)
                            else -> session.foreground(false)
                        }
                        await("revoke $fault") { !session.snapshot().engine.enabled }
                        pulse.pause();assertQuiet(session,fc)
                        if(fault=="crc") assertTrue(session.snapshot().crcErrors>0)
                        if(fault=="armed") assertEquals(ArmState.ARMED,session.snapshot().engine.arm)
                    }
                }
            }
        }
    }
    @Test fun reconnectClearsCalibrationPhysicalAuthorizationAndOldRc() {
        FakeAutopilot(Heartbeat.DISARMED).use { fc ->
            BenchSession().use { session ->
                Pulse(session).use { pulse ->
                    ready(session,fc);authorize(session);session.singleChannel(1,1f);pulse.start()
                    await("first connection RC") { fc.overrides().isNotEmpty() }
                    pulse.pause();session.disconnect("test reconnect")
                    val before=fc.connections.get()
                    session.connect("127.0.0.1",fc.port)
                    await("fresh connection") { fc.connections.get()>before && session.snapshot().engine.arm==ArmState.DISARMED }
                    assertTrue(session.snapshot().engine.channels.none { it.verified })
                    session.enableTest();pulse.start();assertQuiet(session,fc)
                    assertFalse(session.snapshot().engine.enabled)
                    assertTrue(fc.overrides().none { it.connection>before })
                }
            }
        }
    }
    @Test fun heartbeatLossAndDeadmanReleaseStopWithoutAutomaticResume() {
        FakeAutopilot(Heartbeat.DISARMED).use { fc ->
            BenchSession().use { session ->
                Pulse(session).use { pulse ->
                    ready(session,fc);authorize(session);pulse.start()
                    await("RC") { fc.overrides().isNotEmpty() }
                    pulse.pause();session.deadman(false)
                    assertQuiet(session,fc)
                    session.deadman(true);assertQuiet(session,fc)
                    fc.heartbeat.set(Heartbeat.NONE)
                    await("HB timeout",2500) { session.snapshot().engine.arm==ArmState.UNKNOWN }
                    assertFalse(session.snapshot().engine.enabled)
                }
            }
        }
    }
    @Test fun blockedOldBinderCannotReinstateEngineAfterDisconnectOrNewGeneration() {
        FakeAutopilot(Heartbeat.DISARMED).use { fc ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val bindCalls=AtomicInteger()
            BenchSession(binder=ChannelBinder {
                if(bindCalls.incrementAndGet()==1){entered.countDown();release.await(3,TimeUnit.SECONDS)}
            }).use { session ->
                try {
                    session.foreground(true);session.connect("127.0.0.1",fc.port)
                    assertTrue("first binder entered",entered.await(2,TimeUnit.SECONDS))
                    session.disconnect("disconnect while bind is blocked")
                    assertFalse(session.snapshot().engine.connected)
                    session.connect("127.0.0.1",fc.port)
                    assertFalse("new connection remains unverified before binder release",session.snapshot().engine.connected)
                    release.countDown()
                    await("new generation connects") { session.snapshot().engine.arm==ArmState.DISARMED }
                    assertEquals(2,bindCalls.get())
                    assertEquals(1,fc.connections.get())
                    assertFalse(session.snapshot().engine.enabled)
                    assertEquals(0L,session.snapshot().txOverrides)
                    assertTrue(session.snapshot().engine.channels.none{it.verified})
                } finally { release.countDown() }
            }
        }
    }

    @Test fun realTcpWriterHandlesZeroAndThreeByteWritesWithoutDuplicateOrCorruptedFrames() {
        FakeAutopilot(Heartbeat.DISARMED).use { fc ->
            val calls=AtomicInteger();val zero=AtomicInteger();val partial=AtomicInteger()
            BenchSession(writer=ChannelWriter { channel,bytes ->
                if(calls.incrementAndGet()%4==0){zero.incrementAndGet();0}
                else {
                    val end=bytes.limit()
                    try {
                        if(bytes.remaining()>3){bytes.limit(bytes.position()+3);partial.incrementAndGet()}
                        channel.write(bytes)
                    } finally { bytes.limit(end) }
                }
            }).use { session ->
                Pulse(session).use { pulse ->
                    ready(session,fc);authorize(session);session.singleChannel(1,1f);pulse.start()
                    await("complete RC after actual short writes") { fc.overrides().size>=3 }
                    session.stop("finish partial write integration")
                    pulse.pause();assertQuiet(session,fc)
                    assertTrue(zero.get()>0);assertTrue(partial.get()>0)
                    assertOnlyPermittedOutboundMessages(fc)
                    fc.overrides().forEach {
                        assertArrayEquals(intArrayOf(1520,65535,65535,65535),channels(it.frame))
                        assertWireContract(it.frame)
                    }
                }
            }
        }
    }
    @Test fun revokeDuringPartiallyWrittenRcClosesConnectionRatherThanFinishingStaleFrame() {
        FakeAutopilot(Heartbeat.DISARMED).use { fc ->
            val rcPartial=AtomicBoolean(false)
            BenchSession(writer=ChannelWriter { channel,bytes ->
                val isRc=(bytes.get(7).toInt() and 255)==70
                if(isRc && bytes.position()>0) 0
                else {
                    val end=bytes.limit()
                    try {
                        if(isRc) bytes.limit(bytes.position()+3)
                        val wrote=channel.write(bytes)
                        if(isRc && wrote>0)rcPartial.set(true)
                        wrote
                    } finally { bytes.limit(end) }
                }
            }).use { session ->
                ready(session,fc);authorize(session);session.deadman(true)
                await("first3RC bytes written") { rcPartial.get() }
                session.stop("revoke pending RC frame")
                await("partial control frame causes socket close") { !session.snapshot().engine.connected }
                assertFalse(session.snapshot().engine.enabled)
                assertEquals(0,fc.overrides().size)
                assertQuiet(session,fc)
            }
        }
    }

    private fun ready(session:BenchSession,fc:FakeAutopilot) {
        session.foreground(true);session.connect("127.0.0.1",fc.port)
        await("DISARMED HEARTBEAT") { session.snapshot().engine.arm==ArmState.DISARMED }
    }
    private fun authorize(session:BenchSession) {
        session.configure(1,1100,1900,1500,true)
        session.engine.confirmBenchConditions(true,true,true)
        session.enableTest()
        assertTrue(session.snapshot().engine.enabled)
    }
    private fun assertQuiet(session: BenchSession, fc: FakeAutopilot) {
        // Already-written bytes can still arrive after a local stop; drain those
        // before measuring new wire output. Engine TX count must stay fixed too.
        Thread.sleep(80)
        val frames = fc.overrides().size
        val tx = session.snapshot().txOverrides
        Thread.sleep(220)
        assertEquals("No further RC override after lease revocation", frames, fc.overrides().size)
        assertEquals(tx, session.snapshot().txOverrides)
    }
    private fun assertOnlyPermittedOutboundMessages(fc: FakeAutopilot) {
        assertTrue(fc.frames.isNotEmpty())
        fc.frames.forEach {
            assertTrue("Unexpected outbound message ${it.frame.messageId}", it.frame.messageId in setOf(0, 20, 70, 76))
            assertEquals(255, it.frame.systemId); assertEquals(190, it.frame.componentId)
        }
        assertEquals(0, fc.parseErrors.get())
    }
    private fun assertWireContract(frame: MavFrame) {
        assertEquals(70, frame.messageId)
        assertTrue(frame.payload.size >= 18)
        assertEquals(42, frame.payload[16].toInt() and 255)
        assertEquals(1, frame.payload[17].toInt() and 255)
        for (channel in 4..7) assertEquals(65535, u16(frame.payload, channel * 2))
        for (channel in 8..17) assertEquals(0, u16(frame.payload, 18 + (channel - 8) * 2))
    }
    private fun channels(frame: MavFrame) = IntArray(4) { u16(frame.payload, 2 * it) }
    private fun u16(p: ByteArray, i: Int) =
        (p.getOrElse(i) { 0 }.toInt() and 255) or ((p.getOrElse(i + 1) { 0 }.toInt() and 255) shl 8)
    private fun await(label: String, timeoutMs: Long = 2500, condition: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < until) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timed out: $label", condition())
    }

    private class Pulse(private val session: BenchSession) : Closeable {
        private val active = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-deadman").apply { isDaemon = true } }
        init { executor.scheduleAtFixedRate({ if (active.get()) session.deadman(true) }, 0, 50, TimeUnit.MILLISECONDS) }
        fun start() { active.set(true); session.deadman(true) }
        fun pause() { active.set(false) }
        override fun close() { pause(); executor.shutdownNow() }
    }
    private enum class Heartbeat { NONE, DISARMED, ARMED }
    private data class Capture(val frame: MavFrame, val connection: Int, val atNanos: Long)

    private inner class FakeAutopilot(
        initial: Heartbeat = Heartbeat.NONE,
        private val physicalChannels: Int = 4,
        private val initialRaw: IntArray = intArrayOf(1500, 1500, 1100, 1500),
    ) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val port: Int get() = server.localPort
        val heartbeat = AtomicReference(initial)
        val mirror = AtomicBoolean(true)
        val rcEnabled = AtomicBoolean(false)
        val corrupt = AtomicBoolean(false)
        val connections = AtomicInteger()
        val splitWrites = AtomicInteger()
        val coalescedWrites = AtomicInteger()
        val parseErrors = AtomicInteger()
        val frames = CopyOnWriteArrayList<Capture>()
        private val peers = CopyOnWriteArrayList<Peer>()
        private val running = AtomicBoolean(true)
        private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-autopilot-tx").apply { isDaemon = true } }
        private val acceptor = Thread({
            while (running.get()) {
                try {
                    val socket = server.accept().apply { tcpNoDelay = true; soTimeout = 50 }
                    peers.add(Peer(socket, connections.incrementAndGet()))
                } catch (_: SocketException) { if (running.get()) throw AssertionError("Unexpected test listener close") }
            }
        }, "fake-autopilot-accept").apply { isDaemon = true; start() }
        init { executor.scheduleAtFixedRate({ peers.forEach { it.emit() } }, 0, 50, TimeUnit.MILLISECONDS) }
        fun overrides() = frames.filter { it.frame.messageId == 70 }
        private inner class Peer(private val socket: Socket, private val id: Int) {
            private val parser = Mavlink2Parser()
            private val actual = AtomicReference(initialRaw.copyOf(18))
            private var sequence = 0
            private var writes = 0
            private var reportedErrors = 0
            private val reader = Thread({
                val bytes = ByteArray(1024)
                while (running.get() && !socket.isClosed) {
                    try {
                        val count = socket.getInputStream().read(bytes)
                        if (count < 0) break
                        val decoded = parser.feed(bytes, 0, count)
                        val d = parser.diagnostics
                        val rejected = (d.crcErrors + d.malformedFrames + d.unknownMessages +
                            d.unsupportedFlags + d.signedRejected + d.mavlink1Ignored + d.garbageBytes).toInt()
                        parseErrors.addAndGet(rejected - reportedErrors)
                        reportedErrors = rejected
                        decoded.forEach { frame ->
                            frames.add(Capture(frame, id, System.nanoTime()))
                            if (frame.messageId == 70 && mirror.get()) {
                                val previous = actual.get()
                                actual.set(previous.copyOf().apply {
                                    for (index in 0..3) {
                                        val value = u16(frame.payload, 2 * index)
                                        if (value != 65535) this[index] = value
                                    }
                                })
                            }
                        }
                    } catch (_: SocketTimeoutException) { /* periodically check shutdown */ }
                    catch (_: SocketException) { break }
                }
            }, "fake-autopilot-reader-$id").apply { isDaemon = true; start() }
            fun emit() {
                val state = heartbeat.get()
                if (state == Heartbeat.NONE || socket.isClosed) return
                val hb = ByteArray(9).apply {
                    this[4] = 2; this[5] = 3; this[6] = (if (state == Heartbeat.ARMED) 128 else 0).toByte()
                    this[7] = 3; this[8] = 3
                }
                val rc = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(1234)
                    val channels = actual.get()
                    repeat(18) { putShort(channels.getOrElse(it) { 0 }.toShort()) }
                    put(physicalChannels.toByte()); put(200.toByte())
                }.array()
                val heartbeatBytes = encode(0, hb, 50)
                if(corrupt.get()) heartbeatBytes[10] = (heartbeatBytes[10].toInt() xor 1).toByte()
                val bundle = heartbeatBytes + if (rcEnabled.get()) encode(65, rc, 118) else byteArrayOf()
                try {
                    val output = socket.getOutputStream()
                    if (writes++ % 2 == 0) {
                        // Header split across two writes, with HEARTBEAT and RC
                        // coalesced in the second write. No assumptions about TCP packet boundaries.
                        output.write(bundle, 0, 5); output.flush()
                        Thread.sleep(2)
                        output.write(bundle, 5, bundle.size - 5); output.flush()
                        splitWrites.incrementAndGet()
                    } else {
                        output.write(bundle); output.flush(); coalescedWrites.incrementAndGet()
                    }
                } catch (_: Exception) { runCatching { socket.close() } }
            }
            private fun encode(message: Int, payload: ByteArray, crcExtra: Int): ByteArray {
                val bytes = ByteArray(payload.size + 12)
                bytes[0] = 0xfd.toByte(); bytes[1] = payload.size.toByte()
                bytes[4] = (sequence++ and 255).toByte(); bytes[5] = 42; bytes[6] = 1; bytes[7] = message.toByte()
                payload.copyInto(bytes, 10)
                val crc = MavlinkCrc.x25(bytes, 1, payload.size + 9, crcExtra)
                bytes[10 + payload.size] = crc.toByte(); bytes[11 + payload.size] = (crc ushr 8).toByte()
                return bytes
            }
            fun close() { runCatching { socket.close() }; reader.join(500) }
        }
        override fun close() {
            running.set(false); executor.shutdownNow(); server.close()
            peers.forEach { it.close() }; acceptor.join(500)
        }
    }
}
