package dev.drone.rcbench.control

import dev.drone.rcbench.mavlink.MavFrame
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Typed frames here exercise controller policy after the parser's CRC boundary.
 * Mavlink2Test independently checks CRC/wire decoding against reference vectors.
 * Fake time is local monotonic time: no sleeps, hardware, Android, or UI mocks.
 */
class BenchEngineTest {
    private class Clock(var now: Long = 10_000L) : MonotonicClock {
        override fun nowMs() = now
        fun advance(ms: Long) { now += ms }
    }
    private class Rig {
        val clock = Clock()
        val engine = BenchEngine(clock)
        var sequence = 0
        fun heartbeat(
            system: Int = 42, component: Int = 1, armed: Boolean = false,
            vehicle: Int = 2, autopilot: Int = 3, status: Int = 3, version: Int = 3,
        ) = MavFrame(sequence++ and 255, system, component, 0,
            ByteArray(9).apply {
                this[4] = vehicle.toByte(); this[5] = autopilot.toByte()
                this[6] = (if (armed) 128 else 0).toByte()
                this[7] = status.toByte(); this[8] = version.toByte()
            })
        fun rc(
            values: IntArray = intArrayOf(1500, 1500, 1100, 1500),
            system: Int = 42, component: Int = 1, count: Int = 4,
        ): MavFrame {
            val payload = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(clock.now.toInt())
            repeat(18) { payload.putShort(values.getOrElse(it) { 65535 }.toShort()) }
            payload.put(count.toByte()); payload.put(200.toByte())
            return MavFrame(sequence++ and 255, system, component, 65, payload.array())
        }
        fun fresh(values: IntArray = intArrayOf(1500, 1500, 1100, 1500)) {
            engine.onFrame(heartbeat()); engine.onFrame(rc(values))
        }
        fun calibration(channel: Int, verified: Boolean = true) {
            assertTrue(engine.configure(channel, ChannelCalibration(verified = verified)))
        }
        fun connect() { engine.connected(); engine.foreground(true) }
        fun ready() {
            connect(); fresh(); (1..4).forEach { calibration(it) }
            assertTrue(engine.snapshot().ready)
        }
        fun enable() { ready(); assertTrue(engine.enableTest()); engine.deadman(true) }
        fun send(): ControlPermit {
            val permit = engine.planOverride()
            assertNotNull(permit)
            assertTrue(engine.permitValid(permit!!))
            engine.sent(permit)
            return permit
        }
    }

    @Test fun bootUnknownAndMissingActualRcNeverPermitOverride() {
        val r = Rig()
        assertEquals(ArmState.UNKNOWN, r.engine.snapshot().arm)
        assertNull(r.engine.planOverride())
        assertFalse(r.engine.enableTest())
        r.connect()
        (1..4).forEach { r.calibration(it) }
        assertFalse(r.engine.enableTest())
        r.engine.onFrame(r.heartbeat())
        assertEquals(ArmState.DISARMED, r.engine.snapshot().arm)
        assertNull(r.engine.snapshot().received)
        assertFalse(r.engine.snapshot().ready)
        assertFalse(r.engine.enableTest())
        r.engine.deadman(true)
        assertNull(r.engine.planOverride())
        assertEquals(0L, r.engine.snapshot().overrides)
    }

    @Test fun discoveryRejectsGcsWrongIdsAndWrongAutopilot() {
        val r = Rig(); r.connect()
        val rejected = listOf(
            r.heartbeat(system = 0), r.heartbeat(system = 255),
            r.heartbeat(component = 190), r.heartbeat(vehicle = 6),
            r.heartbeat(autopilot = 12), r.heartbeat(version = 2),
        )
        rejected.forEach {
            r.engine.onFrame(it)
            assertNull(r.engine.snapshot().targetSystem)
            assertEquals(ArmState.UNKNOWN, r.engine.snapshot().arm)
        }
        r.engine.onFrame(r.rc()) // RC is not autopilot discovery.
        assertNull(r.engine.snapshot().received)
        r.engine.onFrame(r.heartbeat(system = 42))
        assertEquals(42, r.engine.snapshot().targetSystem)
        assertEquals(1, r.engine.snapshot().targetComponent)
        r.engine.onFrame(r.heartbeat(system = 7, armed = true))
        assertEquals(42, r.engine.snapshot().targetSystem)
        assertEquals(ArmState.DISARMED, r.engine.snapshot().arm)
        r.engine.onFrame(r.rc(system = 7))
        r.engine.onFrame(r.rc(component = 190))
        assertNull(r.engine.snapshot().received)
        r.engine.onFrame(r.rc())
        assertNotNull(r.engine.snapshot().received)
    }

    @Test fun allChannelModeRequiresFourIndependentManualCalibrations() {
        val r = Rig(); r.connect(); r.fresh()
        assertTrue(r.engine.snapshot().channels.all {
            it.minimum == 1100 && it.maximum == 1900 && it.trim == 1500 && !it.verified
        })
        (1..3).forEach { r.calibration(it); assertFalse(r.engine.enableTest()) }
        r.calibration(4)
        assertTrue(r.engine.enableTest())
        assertNull(r.engine.planOverride()) // Enabling alone never starts a sender.
        r.engine.deadman(true)
        val permit = r.send()
        assertEquals(42, permit.targetSystem)
        assertEquals(1, permit.targetComponent)
        assertArrayEquals(intArrayOf(1500, 1500, 1100, 1500), permit.channels)
    }

    @Test fun throttleRestIsMinimumRatherThanTrimAndDirectionsRespectMapping() {
        val r = Rig(); r.enable()
        assertEquals(1100, r.send().channels[2])
        r.clock.advance(50)
        r.engine.sticks(1f, -1f, 1f, -1f)
        assertArrayEquals(intArrayOf(1900, 1100, 1900, 1400), r.send().channels)
        r.clock.advance(50)
        r.engine.sticks(4f, -4f, -2f, 8f)
        assertArrayEquals(intArrayOf(1900, 1100, 1100, 1600), r.send().channels)
    }

    @Test fun oneChannelModeRequiresOnlySelectedCalibrationAndOthersAreIgnored() {
        for (channel in 1..4) {
            val r = Rig(); r.connect(); r.fresh(); r.engine.selectMode(channel)
            assertFalse(r.engine.enableTest())
            r.calibration(channel)
            assertTrue(r.engine.enableTest())
            r.engine.singleChannel(channel, if (channel == 3) 0f else -1f)
            r.engine.deadman(true)
            val permit = r.send()
            for (index in 0..3) assertEquals(if (index == channel - 1) (if (channel == 4) 1400 else 1100) else 65535, permit.channels[index])
            assertTrue(r.engine.snapshot().channels.filterIndexed { index, _ -> index != channel - 1 }.none { it.verified })
        }
    }

    @Test fun yawGuardLimitsDualAndSingleModesWithoutChangingRollPitchThrottle() {
        val r = Rig(); r.enable()
        for ((yaw, expected) in listOf(-1f to 1400, 1f to 1600, -9f to 1400, 9f to 1600)) {
            r.engine.sticks(1f, -1f, 1f, yaw)
            val permit = r.send()
            assertArrayEquals(intArrayOf(1900, 1100, 1900, expected), permit.channels)
            r.clock.advance(50); r.engine.deadman(true)
        }
        r.engine.selectMode(4)
        assertTrue(r.engine.enableTest()); r.engine.deadman(true)
        for ((yaw, expected) in listOf(-1f to 1400, 1f to 1600)) {
            r.engine.singleChannel(4, yaw)
            assertArrayEquals(intArrayOf(65535, 65535, 65535, expected), r.send().channels)
            r.clock.advance(50); r.engine.deadman(true)
        }
        // Cap uses each calibrated side around trim, not a hard-coded 1500 center.
        assertTrue(r.engine.configure(4, ChannelCalibration(1100, 1900, 1300, true)))
        assertTrue(r.engine.enableTest()); r.engine.deadman(true)
        r.engine.singleChannel(4, -1f)
        assertEquals(1250, r.send().channels[3])
        r.clock.advance(50); r.engine.deadman(true)
        r.engine.singleChannel(4, 1f)
        assertEquals(1450, r.send().channels[3])
    }

    @Test fun receiverlessPhysicalCountZeroBootstrapsFromFreshRcAndUsesRawPostTxFeedback() {
        // ArduPilot chancount is HAL physical receiver count, not the number of
        // override-populated RC_Channel::get_radio_in() fields sent on the wire.
        val r = Rig(); r.connect(); r.engine.onFrame(r.heartbeat())
        r.engine.onFrame(r.rc(IntArray(4), count = 0))
        (1..4).forEach { r.calibration(it) }
        assertTrue(r.engine.snapshot().ready)
        assertArrayEquals(IntArray(4), r.engine.snapshot().received!!.take(4).toIntArray())
        assertTrue(r.engine.enableTest()); r.engine.deadman(true)
        var permit = r.send()
        assertArrayEquals(intArrayOf(1500, 1500, 1100, 1500), permit.channels)
        repeat(20) {
            r.clock.advance(50)
            r.engine.onFrame(r.heartbeat())
            r.engine.onFrame(r.rc(permit.channels, count = 0))
            r.engine.deadman(true)
            permit = r.send()
        }
        assertTrue(r.engine.snapshot().enabled)
        assertEquals(21L, r.engine.snapshot().overrides)
        assertArrayEquals(permit.channels, r.engine.snapshot().received!!.take(4).toIntArray())
    }

    @Test fun physicalReceiverCountDoesNotTruncateOverrideFeedbackFields() {
        val r = Rig(); r.ready()
        r.engine.onFrame(r.rc(count = 1))
        assertTrue(r.engine.enableTest()); r.engine.deadman(true)
        r.engine.sticks(0f, 0f, 0f, 1f)
        val permit = r.send()
        r.clock.advance(50)
        r.engine.onFrame(r.rc(permit.channels, count = 1))
        assertArrayEquals(permit.channels, r.engine.snapshot().received!!.take(4).toIntArray())
        assertTrue(r.engine.snapshot().enabled)
    }

    @Test fun invalidRcChannelCountsCannotEstablishReadiness() {
        for (count in listOf(19, 255)) {
            val r = Rig(); r.ready()
            r.engine.onFrame(r.rc(count = count))
            assertFalse("RC_CHANNELS.chancount=$count is invalid", r.engine.enableTest())
            assertFalse(r.engine.snapshot().enabled)
        }
    }

    @Test fun staleRcMessageStillBlocksEvenWithFreshHeartbeatAndConfirmedCalibration() {
        val r = Rig(); r.ready()
        r.engine.onFrame(r.rc(IntArray(4), count = 0))
        assertTrue(r.engine.enableTest())
        r.clock.advance(BenchEngine.RC_TIMEOUT_MS)
        r.engine.onFrame(r.heartbeat())
        assertFalse(r.engine.enableTest())
        assertNull(r.engine.planOverride())
    }

    @Test fun senderCapsAtTwentyHzWithoutCatchUpBursts() {
        val r = Rig(); r.enable()
        r.send()
        assertNull(r.engine.planOverride())
        r.clock.advance(49); assertNull(r.engine.planOverride())
        r.clock.advance(1); r.send()
        r.clock.advance(450); r.engine.deadman(true)
        r.send() // one overdue send, not nine catch-up sends
        repeat(10) { assertNull(r.engine.planOverride()) }
        assertEquals(3L, r.engine.snapshot().overrides)
    }

    @Test fun deadmanExpiresExactlyAtTwoHundredMsAndDoesNotResume() {
        val r = Rig(); r.enable(); val old = r.send()
        r.clock.advance(199)
        assertTrue(r.engine.permitValid(old))
        r.clock.advance(1)
        assertFalse(r.engine.permitValid(old))
        assertFalse(r.engine.snapshot().enabled)
        assertNull(r.engine.planOverride())
        r.engine.deadman(true)
        assertNull(r.engine.planOverride())
        assertEquals(1L, r.engine.snapshot().overrides)
    }

    @Test fun releaseFocusLossModeChangeAndStreamFaultInvalidatePermits() {
        val operations: List<(BenchEngine) -> Unit> = listOf(
            { it.deadman(false) }, { it.foreground(false) },
            { it.selectMode(3) }, { it.streamFault("CRC/transport fault") },
        )
        for (operation in operations) {
            val r = Rig(); r.enable(); val old = r.send()
            operation(r.engine)
            assertFalse(r.engine.permitValid(old))
            assertNull(r.engine.planOverride())
            assertFalse(r.engine.snapshot().enabled)
            r.engine.sent(old)
            assertEquals(1L, r.engine.snapshot().overrides)
            r.engine.foreground(true)
            r.engine.deadman(true)
            assertNull(r.engine.planOverride())
        }
    }

    @Test fun armedAndUnknownHeartbeatRevokeWithoutAutomaticRecovery() {
        for (status in listOf(0, 1, 2, 5, 6)) {
            val r = Rig(); r.enable(); val old = r.send()
            r.engine.onFrame(r.heartbeat(status = status))
            assertEquals(ArmState.UNKNOWN, r.engine.snapshot().arm)
            assertFalse(r.engine.permitValid(old))
            assertNull(r.engine.planOverride())
        }
        val r = Rig(); r.enable(); val old = r.send()
        r.engine.onFrame(r.heartbeat(armed = true))
        assertEquals(ArmState.ARMED, r.engine.snapshot().arm)
        assertFalse(r.engine.snapshot().enabled)
        assertFalse(r.engine.permitValid(old))
        r.engine.onFrame(r.heartbeat())
        r.engine.deadman(true)
        assertNull(r.engine.planOverride())
        assertEquals(1L, r.engine.snapshot().overrides)
    }

    @Test fun expiredHeartbeatRevokesEvenWithFreshRcAndDeadman() {
        val r = Rig(); r.enable(); val old = r.send()
        r.clock.advance(BenchEngine.HEARTBEAT_TIMEOUT_MS)
        r.engine.onFrame(r.rc()); r.engine.deadman(true)
        assertEquals(ArmState.UNKNOWN, r.engine.snapshot().arm)
        assertFalse(r.engine.permitValid(old))
        assertNull(r.engine.planOverride())
        assertFalse(r.engine.snapshot().enabled)
        r.fresh(); r.engine.deadman(true)
        assertNull(r.engine.planOverride())
    }

    @Test fun reconnectRequiresFreshTelemetryCalibrationAndNewLowThrottle() {
        val r = Rig(); r.enable()
        r.engine.sticks(0f, 0f, 1f, 0f)
        val old = r.send(); assertEquals(1900, old.channels[2])
        r.engine.disconnected("Cable removed")
        assertFalse(r.engine.permitValid(old))
        r.engine.connected()
        assertEquals(ArmState.UNKNOWN, r.engine.snapshot().arm)
        assertNull(r.engine.snapshot().received)
        assertTrue(r.engine.snapshot().channels.none { it.verified })
        assertFalse(r.engine.enableTest())
        r.fresh(); assertFalse(r.engine.enableTest())
        (1..4).forEach { r.calibration(it) }
        assertTrue(r.engine.enableTest())
        assertNull(r.engine.planOverride())
        r.engine.deadman(true)
        assertEquals(1100, r.send().channels[2])
        assertFalse(r.engine.permitValid(old))
    }

    @Test fun mismatchedActualRcStopsAt750MsEvenThoughLinkAndDeadmanAreFresh() {
        val r = Rig(); r.enable()
        r.engine.sticks(1f, 0f, 0f, 0f)
        r.send()
        repeat(14) {
            r.clock.advance(50)
            r.fresh() // RC1=1500 mismatches requested 1900.
            r.engine.deadman(true)
            r.send()
            assertTrue(r.engine.snapshot().enabled)
        }
        r.clock.advance(50); r.fresh(); r.engine.deadman(true)
        assertNull(r.engine.planOverride())
        assertFalse(r.engine.snapshot().enabled)
        assertEquals(15L, r.engine.snapshot().overrides)
    }

    @Test fun matchingActualRcRenewsFeedbackButNotDeadmanOrHeartbeat() {
        val r = Rig(); r.enable()
        r.engine.sticks(.5f, -.5f, .25f, 0f)
        var permit = r.send()
        repeat(24) {
            r.clock.advance(50)
            r.engine.onFrame(r.heartbeat())
            r.engine.onFrame(r.rc(permit.channels))
            r.engine.deadman(true)
            permit = r.send()
        }
        assertTrue(r.engine.snapshot().enabled)
        assertEquals(25L, r.engine.snapshot().overrides)
        r.clock.advance(200)
        r.fresh(permit.channels)
        assertNull(r.engine.planOverride())
        assertFalse(r.engine.snapshot().enabled)
    }

    @Test fun nonFiniteStickAndSingleChannelInputRevokeInsteadOfEncoding() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val r = Rig(); r.enable(); val old = r.send()
            r.engine.sticks(0f, 0f, bad, 0f)
            assertFalse(r.engine.snapshot().enabled)
            assertFalse(r.engine.permitValid(old))
            assertNull(r.engine.planOverride())
            val single = Rig(); single.ready(); single.engine.selectMode(3)
            assertTrue(single.engine.enableTest()); single.engine.deadman(true)
            single.engine.singleChannel(3, bad)
            assertNull(single.engine.planOverride())
        }
    }

    @Test fun calibrationMapsAsymmetricTrimAndRejectsInvalidBoundsAndNaN() {
        val c = ChannelCalibration(1100, 1900, 1300, true)
        assertTrue(c.valid())
        assertEquals(1100, c.pwm(-1f, false))
        assertEquals(1200, c.pwm(-.5f, false))
        assertEquals(1300, c.pwm(0f, false))
        assertEquals(1600, c.pwm(.5f, false))
        assertEquals(1900, c.pwm(1f, false))
        assertEquals(1100, c.pwm(0f, true))
        assertEquals(1500, c.pwm(.5f, true))
        assertEquals(1900, c.pwm(1f, true))
        assertEquals(1100, c.pwm(-9f, true))
        assertEquals(1900, c.pwm(9f, false))
        val invalid = listOf(
            ChannelCalibration(899, 1900, 1500, true),
            ChannelCalibration(1100, 2101, 1500, true),
            ChannelCalibration(1500, 1900, 1500, true),
            ChannelCalibration(1100, 1500, 1500, true),
            ChannelCalibration(1900, 1100, 1500, true),
        )
        val r = Rig()
        invalid.forEach { assertFalse(it.valid()); assertFalse(r.engine.configure(1, it)) }
        assertFalse(r.engine.configure(0, c)); assertFalse(r.engine.configure(5, c))
        try { c.pwm(Float.NaN, true); fail("NaN must never reach a PWM frame") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun changingCalibrationRevokesPermitAndClearsStoredThrottle() {
        val r = Rig(); r.enable()
        r.engine.sticks(0f, 0f, 1f, 0f)
        val old = r.send()
        assertTrue(r.engine.configure(3, ChannelCalibration(1050, 1950, 1400, true)))
        assertFalse(r.engine.snapshot().enabled)
        assertFalse(r.engine.permitValid(old))
        assertNull(r.engine.planOverride())
        assertTrue(r.engine.enableTest())
        r.engine.deadman(true); r.clock.advance(50)
        assertEquals(1050, r.send().channels[2])
        assertTrue(r.engine.configure(3, ChannelCalibration(1050, 1950, 1400, false)))
        assertFalse(r.engine.enableTest())
    }

    @Test fun stickUpdatesDoNotExtendTheIndependentDeadmanLease() {
        val r = Rig(); r.enable(); r.send()
        r.clock.advance(199)
        r.engine.sticks(1f, 1f, 1f, 1f)
        r.clock.advance(1)
        assertNull(r.engine.planOverride())
        assertFalse(r.engine.snapshot().enabled)
    }

    @Test fun nonFiniteAttitudeTelemetryIsIgnoredRatherThanDisplayedAsMeasured() {
        val r = Rig(); r.ready()
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(1); payload.putFloat(Float.NaN)
        repeat(5) { payload.putFloat(0f) }
        r.engine.onFrame(MavFrame(1, 42, 1, 30, payload.array()))
        assertFalse(r.engine.snapshot().telemetry.containsKey("Attitude"))
        assertTrue(r.engine.snapshot().ready)
    }
}
