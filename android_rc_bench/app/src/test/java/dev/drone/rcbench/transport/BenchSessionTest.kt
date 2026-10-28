package dev.drone.rcbench.transport

import dev.drone.rcbench.control.ArmState
import dev.drone.rcbench.mavlink.MavFrame
import dev.drone.rcbench.mavlink.Mavlink2Parser
import dev.drone.rcbench.mavlink.MavlinkCrc
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

/** Loopback TCP integration, not an ArduPilot SITL/hardware acceptance test.
 * The peer emits real unsigned CRC-valid MAVLink2 frames; mirroring RC output
 * proves this transport's feedback path only, not acceptance by a real aircraft.
 */
class BenchSessionTest {
    @Test fun splitCoalescedRealTcpDiscoversTargetAndStopsWhenHeartbeatBecomesArmed() {
        FakeAutopilot(physicalChannels = 0, initialRaw = IntArray(18)).use { fc ->
            BenchSession().use { session ->
                Pulse(session).use { pulse ->
                    session.foreground(true)
                    session.connect("127.0.0.1", fc.port)
                    await("TCP connection and outbound GCS heartbeat") {
                        session.snapshot().engine.connected && fc.frames.any { it.frame.messageId == 0 }
                    }
                    configure(session)
                    session.enableTest(); session.deadman(true)
                    assertFalse(session.snapshot().engine.enabled)
                    assertEquals(ArmState.UNKNOWN, session.snapshot().engine.arm)
                    assertQuiet(session, fc)

                    fc.heartbeat.set(Heartbeat.DISARMED)
                    await("fresh split/coalesced autopilot HEARTBEAT and RC_CHANNELS") {
                        session.snapshot().engine.ready
                    }
                    assertTrue(session.snapshot().engine.received!!.all { it == 0 })
                    session.enableTest()
                    session.sticks(1f, -1f, 0f, 1f)
                    val began = System.nanoTime()
                    pulse.start()
                    await("receiverless count0 bootstraps and mirrors beyond feedback timeout") { fc.overrides().size >= 17 }
                    val captures = fc.overrides()
                    assertTrue(captures.size >= 17)
                    captures.forEach { capture ->
                        assertArrayEquals(intArrayOf(1900, 1100, 1100, 1600), channels(capture.frame))
                        assertWireContract(capture.frame)
                    }
                    // Allow capture/OS scheduling skew; exact 50ms/no-catch-up
                    // boundaries are covered with fake time in BenchEngineTest.
                    val spanMs = (System.nanoTime() - began) / 1_000_000.0
                    assertTrue("Unexpected rate: ${captures.size} frames / ${spanMs}ms",
                        captures.size <= spanMs / 50.0 + 2.0)
                    await("actual mirrored RC telemetry matches last TX") {
                        val s = session.snapshot()
                        s.engine.lastSent != null && s.engine.received != null &&
                            s.engine.lastSent!!.contentEquals(s.engine.received!!.take(4).toIntArray())
                    }
                    val snapshot = session.snapshot()
                    assertEquals(42, snapshot.engine.targetSystem)
                    assertEquals(1, snapshot.engine.targetComponent)
                    assertNotNull(snapshot.lastTxAgeMs); assertNotNull(snapshot.lastRxAgeMs)
                    assertTrue(snapshot.txBytes > 0 && snapshot.rxBytes > 0)
                    assertTrue(snapshot.txOverrides >= 17)
                    assertTrue(snapshot.txHz <= 21.0)
                    assertEquals(0L, snapshot.crcErrors)
                    assertEquals(0L, snapshot.parserErrors)
                    assertTrue(fc.splitWrites.get() > 0 && fc.coalescedWrites.get() > 0)

                    fc.heartbeat.set(Heartbeat.ARMED)
                    await("ARMED telemetry revokes the active lease") {
                        session.snapshot().engine.arm == ArmState.ARMED && !session.snapshot().engine.enabled
                    }
                    pulse.pause()
                    assertQuiet(session, fc)
                    fc.heartbeat.set(Heartbeat.DISARMED)
                    await("DISARMED heartbeat returns") { session.snapshot().engine.arm == ArmState.DISARMED }
                    session.deadman(true)
                    assertQuiet(session, fc) // Returning DISARMED never auto-enables RC.
                    assertOnlyPermittedOutboundMessages(fc)
                }
            }
        }
    }

    @Test fun releaseBackgroundAndManualReconnectNeverSendNeutralReleaseOrOldThrottle() {
        FakeAutopilot(Heartbeat.DISARMED).use { fc ->
            BenchSession().use { session ->
                Pulse(session).use { pulse ->
                    session.foreground(true); session.connect("127.0.0.1", fc.port)
                    await("autopilot RC feedback") { session.snapshot().engine.received != null }
                    configure(session); session.enableTest()
                    session.sticks(0f, 0f, 1f, 0f); pulse.start()
                    await("high bench throttle reached peer") {
                        fc.overrides().any { channels(it.frame)[2] == 1900 }
                    }
                    pulse.pause(); session.deadman(false)
                    assertQuiet(session, fc)
                    assertFalse(session.snapshot().engine.enabled)

                    session.enableTest(); session.sticks(0f, 0f, .5f, 0f)
                    val before = fc.overrides().size
                    pulse.start()
                    await("new manually enabled override") { fc.overrides().size > before }
                    pulse.pause(); session.foreground(false)
                    assertQuiet(session, fc)
                    session.foreground(true); session.deadman(true)
                    assertQuiet(session, fc) // Focus return cannot restart a lease.

                    session.disconnect("manual disconnect for regression")
                    assertEquals(ArmState.UNKNOWN, session.snapshot().engine.arm)
                    val connections = fc.connections.get()
                    fc.rcEnabled.set(false)
                    session.connect("127.0.0.1", fc.port)
                    await("new TCP connection") {
                        fc.connections.get() > connections && session.snapshot().engine.arm == ArmState.DISARMED
                    }
                    assertNull(session.snapshot().engine.received)
                    assertTrue(session.snapshot().engine.channels.none { it.verified })
                    session.enableTest(); session.deadman(true)
                    assertFalse(session.snapshot().engine.enabled)
                    assertQuiet(session, fc)

                    configure(session); session.enableTest()
                    assertFalse("HEARTBEAT alone cannot establish actual RC", session.snapshot().engine.enabled)
                    fc.rcEnabled.set(true)
                    await("new connection receives its own fresh RC_CHANNELS") { session.snapshot().engine.received != null }
                    assertFalse(session.snapshot().engine.enabled)
                    session.enableTest()
                    val newConnection = fc.connections.get()
                    pulse.start()
                    await("explicit new lease starts at throttle minimum") {
                        fc.overrides().any { it.connection == newConnection }
                    }
                    val resumed = fc.overrides().first { it.connection == newConnection }
                    assertEquals(1100, channels(resumed.frame)[2])
                    pulse.pause(); session.disconnect()
                    assertQuiet(session, fc)
                    assertOnlyPermittedOutboundMessages(fc)
                    fc.overrides().forEach { capture ->
                        // Neither neutral-on-stop nor release sentinels are generated.
                        assertTrue(channels(capture.frame).all { it in 800..2200 || it == 65535 })
                        assertWireContract(capture.frame)
                    }
                }
            }
        }
    }

    @Test fun liveTcpTxDoesNotCountAsActualRcFeedbackAndMismatchRevokesLease() {
        FakeAutopilot(Heartbeat.DISARMED).use { fc ->
            fc.mirror.set(false)
            BenchSession().use { session ->
                Pulse(session).use { pulse ->
                    session.foreground(true); session.connect("127.0.0.1", fc.port)
                    await("initial autopilot telemetry") { session.snapshot().engine.received != null }
                    configure(session); session.enableTest()
                    session.sticks(1f, 0f, .5f, 0f); pulse.start()
                    await("override actually captured at TCP peer") { fc.overrides().size >= 2 }
                    val early = session.snapshot()
                    assertTrue(early.txOverrides > 0)
                    assertEquals(1900, early.engine.lastSent!![0])
                    assertEquals(1500, early.engine.received!![0])
                    assertNotNull(early.lastTxAgeMs); assertNotNull(early.lastRxAgeMs)
                    await("feedback mismatch stops despite fresh HEARTBEAT, RC and deadman") {
                        val s = session.snapshot().engine
                        !s.enabled && s.overrides > 0 && s.arm == ArmState.DISARMED &&
                            s.rcAgeMs != null && s.rcAgeMs!! < 500
                    }
                    pulse.pause()
                    assertQuiet(session, fc)
                    assertTrue(session.snapshot().engine.reason.contains("RC"))
                    assertOnlyPermittedOutboundMessages(fc)
                }
            }
        }
    }

    private fun configure(session: BenchSession) {
        (1..4).forEach { session.configure(it, 1100, 1900, 1500, true) }
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
            assertTrue("Unexpected outbound message ${it.frame.messageId}", it.frame.messageId == 0 || it.frame.messageId == 70)
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
        val rcEnabled = AtomicBoolean(true)
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
                val bundle = encode(0, hb, 50) + if (rcEnabled.get()) encode(65, rc, 118) else byteArrayOf()
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
