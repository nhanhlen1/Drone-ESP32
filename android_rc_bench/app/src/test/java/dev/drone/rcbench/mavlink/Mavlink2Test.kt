package dev.drone.rcbench.mavlink

import org.junit.Assert.*
import org.junit.Test

class Mavlink2Test {
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Independent reference frames from pymavlink common-v2 are added below.
    private fun packet(id: Int, payload: ByteArray, extra: Int, flags: Int = 0): ByteArray {
        val output = ByteArray(12 + payload.size + if (flags and 1 != 0) 13 else 0)
        output[0] = 0xfd.toByte()
        output[1] = payload.size.toByte()
        output[2] = flags.toByte()
        output[4] = 19
        output[5] = 1
        output[6] = 1
        output[7] = id.toByte()
        output[8] = (id ushr 8).toByte()
        output[9] = (id ushr 16).toByte()
        payload.copyInto(output, 10)
        output.putU16(10 + payload.size, MavlinkCrc.x25(output, 1, payload.size + 9, extra))
        return output
    }

    @Test fun txBytesMatchIndependentPymavlinkVectors() {
        val encoder = Mavlink2Encoder()
        assertArrayEquals(hex(GoldenVectors.TX_HEARTBEAT), encoder.heartbeat())
        assertArrayEquals(hex(GoldenVectors.TX_OVERRIDE), encoder.rcOverride(1, 1, intArrayOf(1100, 1500, 1100, 1500)))
    }

    @Test fun sixOfficialTelemetryMessagesMatchReferenceWireLayoutAndUnits() {
        val references = listOf(
            GoldenVectors.RX_HEARTBEAT, GoldenVectors.RX_SYS_STATUS, GoldenVectors.RX_ATTITUDE,
            GoldenVectors.RX_GPS_RAW_INT, GoldenVectors.RX_RC_CHANNELS, GoldenVectors.RX_STATUSTEXT,
        )
        val parser = Mavlink2Parser()
        val frames = references.flatMap { encoded -> hex(encoded).flatMap { parser.feed(byteArrayOf(it)) } }
        assertEquals(6, frames.size)
        assertEquals(6L, parser.diagnostics.validFrames)
        assertEquals(0L, parser.diagnostics.crcErrors)
        frames.forEachIndexed { i, frame ->
            assertEquals(42, frame.systemId)
            assertEquals(1, frame.componentId)
            assertEquals(10 + i, frame.sequence)
        }
        val hb = TelemetryDecoder.decode(frames[0]) as Telemetry.Heartbeat
        assertEquals(0x03040506L, hb.customMode)
        assertEquals(2, hb.vehicleType)
        assertEquals(3, hb.autopilot)
        assertTrue(hb.armed)
        assertEquals(3, hb.mavlinkVersion)
        val status = TelemetryDecoder.decode(frames[1]) as Telemetry.SysStatus
        assertEquals(11870, status.voltageMv)
        assertEquals(-123, status.currentCentiAmp)
        assertEquals(64, status.batteryRemainingPercent)
        assertEquals(0x80000001L, status.sensorsPresent)
        assertEquals(7L, status.sensorsEnabled)
        assertEquals(5L, status.sensorsHealthy)
        val attitude = TelemetryDecoder.decode(frames[2]) as Telemetry.Attitude
        assertEquals(0xf1234567L, attitude.timeBootMs)
        assertEquals(.125f, attitude.rollRad)
        assertEquals(-.25f, attitude.pitchRad)
        assertEquals(1.5f, attitude.yawRad)
        assertEquals(.01f, attitude.rollSpeedRad)
        assertEquals(-.02f, attitude.pitchSpeedRad)
        assertEquals(.03f, attitude.yawSpeedRad)
        val gps = TelemetryDecoder.decode(frames[3]) as Telemetry.GpsRawInt
        assertEquals(1234567890123L, gps.timeUsec)
        assertEquals(137560123, gps.latitudeE7)
        assertEquals(1005012345, gps.longitudeE7)
        assertEquals(42000, gps.altitudeMm)
        assertEquals(75, gps.eph)
        assertEquals(120, gps.epv)
        assertEquals(234, gps.velocityCms)
        assertEquals(9000, gps.courseCentiDegrees)
        assertEquals(3, gps.fixType)
        assertEquals(14, gps.satellitesVisible)
        assertEquals(9000, gps.yawCentiDegrees)
        val rc = TelemetryDecoder.decode(frames[4]) as Telemetry.RcChannels
        assertEquals(123456L, rc.timeBootMs)
        assertEquals(18, rc.channelCount)
        assertArrayEquals(intArrayOf(1100, 1500, 1100, 1500, 1700, 1800, 1900, 2000,
            1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010), rc.channels)
        assertEquals(200, rc.rssi)
        val text = TelemetryDecoder.decode(frames[5]) as Telemetry.StatusText
        assertEquals(4, text.severity)
        assertEquals("BENCH ONLY - REMOVE PROPELLERS", text.text)
        assertEquals(513, text.id)
        assertEquals(2, text.chunkSequence)
    }

    @Test fun current43ByteSysStatusAndLegacy31BytePayloadAreCompatible() {
        val parser = Mavlink2Parser()
        val frames = parser.feed(hex(GoldenVectors.RX_SYS_STATUS) + hex(CurrentVectors.RX_SYS_STATUS_43))
        assertEquals(2, frames.size)
        val legacy = TelemetryDecoder.decode(frames[0]) as Telemetry.SysStatus
        val current = TelemetryDecoder.decode(frames[1]) as Telemetry.SysStatus
        assertEquals(11870, current.voltageMv)
        assertEquals(-123, current.currentCentiAmp)
        assertEquals(64, current.batteryRemainingPercent)
        assertEquals(legacy.sensorsPresent, current.sensorsPresent)
        assertEquals(legacy.sensorsEnabled, current.sensorsEnabled)
        assertEquals(legacy.sensorsHealthy, current.sensorsHealthy)
        assertEquals(0L, legacy.sensorsPresentExtended)
        assertEquals(0L, legacy.sensorsEnabledExtended)
        assertEquals(0L, legacy.sensorsHealthyExtended)
        assertEquals(0x80000001L, current.sensorsPresentExtended)
        assertEquals(0x10203040L, current.sensorsEnabledExtended)
        assertEquals(0xf1234567L, current.sensorsHealthyExtended)
        assertEquals(0L, parser.diagnostics.malformedFrames)
        assertEquals(0L, parser.diagnostics.crcErrors)
    }

    @Test fun partiallyTruncatedSysStatusExtensionUsesZeroForMissingBytes() {
        val payload = hex(CurrentVectors.RX_SYS_STATUS_43).copyOfRange(10, 42) // base31 + extensionLSB
        val parser = Mavlink2Parser()
        val status = TelemetryDecoder.decode(parser.feed(packet(1, payload, 124)).single()) as Telemetry.SysStatus
        assertEquals(11870, status.voltageMv)
        assertEquals(1L, status.sensorsPresentExtended)
        assertEquals(0L, status.sensorsEnabledExtended)
        assertEquals(0L, status.sensorsHealthyExtended)
        assertEquals(0L, parser.diagnostics.crcErrors)
    }

    @Test fun independentlySignedReferenceIsExplicitlyRejected() {
        val parser = Mavlink2Parser()
        assertTrue(parser.feed(hex(GoldenVectors.RX_SIGNED_HEARTBEAT)).isEmpty())
        assertEquals(1L, parser.diagnostics.signedRejected)
        assertEquals(0L, parser.diagnostics.validFrames)
        assertEquals(0L, parser.diagnostics.crcErrors)
    }

    @Test fun splitAtEveryBoundaryAndCoalescedPackets() {
        val encoder = Mavlink2Encoder()
        val first = encoder.heartbeat()
        val second = encoder.rcOverride(1, 1, intArrayOf(1100, 1500, 1100, 1500))
        val input = first + second
        for (split in 0..input.size) {
            val parser = Mavlink2Parser()
            val frames = parser.feed(input.copyOfRange(0, split)) + parser.feed(input.copyOfRange(split, input.size))
            assertEquals(2, frames.size)
            assertEquals(0, frames[0].messageId)
            assertEquals(70, frames[1].messageId)
            assertEquals(0L, parser.diagnostics.crcErrors)
        }
        val parser = Mavlink2Parser()
        val frames = input.flatMap { parser.feed(byteArrayOf(it)) }
        assertEquals(2, frames.size)
    }

    @Test fun sequenceRolloverAndSourceIdsAreUnsigned() {
        val encoder = Mavlink2Encoder(255, 190)
        val parser = Mavlink2Parser()
        for (i in 0..260) {
            val frame = parser.feed(encoder.heartbeat()).single()
            assertEquals(i and 255, frame.sequence)
            assertEquals(255, frame.systemId)
            assertEquals(190, frame.componentId)
        }
    }

    @Test fun rcWireFieldsUseIgnoreNotReleaseAndDynamicTarget() {
        val encoder = Mavlink2Encoder()
        val frame = Mavlink2Parser().feed(encoder.rcOverride(42, 7, intArrayOf(65535, 1500, 1000, 2200))).single()
        assertEquals(18, frame.payload.size) // zero extension tail is legally truncated
        val p = frame.payload.copyOf(38)
        assertEquals(65535, p.u16(0))
        assertEquals(1500, p.u16(2))
        assertEquals(1000, p.u16(4))
        assertEquals(2200, p.u16(6))
        for (i in 4..7) assertEquals(65535, p.u16(i * 2))
        assertEquals(42, p.u8(16))
        assertEquals(7, p.u8(17))
        for (i in 0..9) assertEquals(0, p.u16(18 + 2 * i))
    }

    @Test fun encoderRejectsReleaseBroadcastAndBadChannelCounts() {
        val encoder = Mavlink2Encoder()
        for (bad in listOf(0, 799, 2201, 65534, -1)) {
            assertThrows(IllegalArgumentException::class.java) { encoder.rcOverride(1, 1, intArrayOf(1100, bad, 1100, 1500)) }
        }
        assertThrows(IllegalArgumentException::class.java) { encoder.rcOverride(0, 1, intArrayOf(1100, 1500, 1100, 1500)) }
        assertThrows(IllegalArgumentException::class.java) { encoder.rcOverride(1, 0, intArrayOf(1100, 1500, 1100, 1500)) }
        assertThrows(IllegalArgumentException::class.java) { encoder.rcOverride(1, 1, intArrayOf(1100)) }
    }

    @Test fun corruptCrcAndGarbageResynchronizeWithoutEmittingBadFrame() {
        val good = Mavlink2Encoder().heartbeat()
        val bad = good.copyOf().also { it[14] = (it[14].toInt() xor 1).toByte() }
        val parser = Mavlink2Parser()
        val result = parser.feed(byteArrayOf(7, 8, 9) + bad + good)
        assertEquals(1, result.size)
        assertEquals(1L, parser.diagnostics.crcErrors)
        assertTrue(parser.diagnostics.garbageBytes >= 3)
    }

    @Test fun corruptKnownPayloadLengthRecoversWithoutWaitingFor255Bytes() {
        val good = hex(GoldenVectors.RX_HEARTBEAT)
        val corruptLength = good.copyOf().also { it[1] = 0xff.toByte() }
        val parser = Mavlink2Parser()
        val frames = parser.feed(corruptLength + good)
        assertEquals(1, frames.size)
        assertEquals(1L, parser.diagnostics.malformedFrames)
    }

    @Test fun signedFramesAreConsumedButNeverTrusted() {
        val payload = byteArrayOf(0, 0, 0, 0, 2, 3, 0, 4, 3)
        val signed = packet(0, payload, 50, 1)
        // Garbage which resembles a header inside the signature must not be parsed.
        signed[signed.size - 13] = 0xfd.toByte()
        val parser = Mavlink2Parser()
        assertTrue(parser.feed(signed.copyOfRange(0, signed.size - 1)).isEmpty())
        val frames = parser.feed(signed.copyOfRange(signed.size - 1, signed.size) + Mavlink2Encoder().heartbeat())
        assertEquals(1, frames.size)
        assertEquals(1L, parser.diagnostics.signedRejected)
        assertEquals(1L, parser.diagnostics.validFrames)
    }

    @Test fun v1BodyWithFdIsNotDecodedAsV2() {
        val v1 = ByteArray(17)
        v1[0] = 0xfe.toByte()
        v1[1] = 9
        for (i in 6..14) v1[i] = 0xfd.toByte()
        val parser = Mavlink2Parser()
        val frames = parser.feed(v1 + Mavlink2Encoder().heartbeat())
        assertEquals(1, frames.size)
        assertEquals(1L, parser.diagnostics.mavlink1Ignored)
    }

    @Test fun unknownMessagesAndUnsupportedFlagsAreNotCrcVerified() {
        val unknown = packet(100000, ByteArray(10) { 0xfd.toByte() }, 0)
        val flags = packet(0, byteArrayOf(0, 0, 0, 0, 2, 3, 0, 4, 3), 50, 2)
        val parser = Mavlink2Parser()
        val frames = parser.feed(unknown + flags + Mavlink2Encoder().heartbeat())
        assertEquals(1, frames.size)
        assertEquals(1L, parser.diagnostics.unknownMessages)
        assertEquals(1L, parser.diagnostics.unsupportedFlags)
    }

    @Test fun maximum280BytePacketAndThousandsOfGarbageBytesStayBounded() {
        val maximum = packet(100001, ByteArray(255) { 7 }, 0, 1)
        assertEquals(280, maximum.size)
        val parser = Mavlink2Parser()
        val frames = parser.feed(ByteArray(20000) { 8 } + maximum + Mavlink2Encoder().heartbeat())
        assertEquals(1, frames.size)
        assertEquals(1L, parser.diagnostics.unknownMessages)
        assertEquals(0L, parser.diagnostics.malformedFrames)
        assertEquals(20000L, parser.diagnostics.garbageBytes)
    }

    @Test fun truncatedV2BaseAndPartialExtensionsAreZeroPadded() {
        val parser = Mavlink2Parser()
        val attitude = TelemetryDecoder.decode(parser.feed(packet(30, byteArrayOf(123), 39)).single()) as Telemetry.Attitude
        assertEquals(123L, attitude.timeBootMs)
        assertEquals(0f, attitude.yawRad)
        val payload = ByteArray(52)
        payload[0] = 4
        "OK".toByteArray().copyInto(payload, 1)
        payload[51] = 37 // only the LSB of STATUSTEXT.id extension survives zero truncation
        val text = TelemetryDecoder.decode(parser.feed(packet(253, payload, 83)).single()) as Telemetry.StatusText
        assertEquals("OK", text.text)
        assertEquals(37, text.id)
        assertEquals(0, text.chunkSequence)
        val gps = TelemetryDecoder.decode(parser.feed(packet(24, ByteArray(30), 24)).single()) as Telemetry.GpsRawInt
        assertEquals(0, gps.yawCentiDegrees)
    }

    @Test fun zeroPayloadAndNonfiniteAttitudeNeverReadOutsidePacket() {
        val frame = Mavlink2Parser().feed(packet(30, byteArrayOf(), 39)).single()
        val attitude = TelemetryDecoder.decode(frame) as Telemetry.Attitude
        assertEquals(0L, attitude.timeBootMs)
        val invalid = ByteArray(28)
        invalid[6] = 0xc0.toByte()
        invalid[7] = 0x7f // IEEE754 quiet NaN roll
        assertNull(TelemetryDecoder.decode(Mavlink2Parser().feed(packet(30, invalid, 39)).single()))
    }

    @Test fun disconnectResetDropsPartialOldStream() {
        val good = Mavlink2Encoder().heartbeat()
        val parser = Mavlink2Parser()
        assertTrue(parser.feed(good.copyOfRange(0, 12)).isEmpty())
        parser.reset()
        assertEquals(1, parser.feed(good).size)
        assertEquals(1L, parser.diagnostics.validFrames)
    }

    @Test fun sentinelsAndUnsigned32BitFieldsDecodeExactly() {
        val payload = ByteArray(31)
        payload[3] = 0x80.toByte()
        payload.putU16(14, 65535)
        payload.putU16(16, 65535)
        payload[30] = 0xff.toByte()
        val status = TelemetryDecoder.decode(Mavlink2Parser().feed(packet(1, payload, 124)).single()) as Telemetry.SysStatus
        assertEquals(0x80000000L, status.sensorsPresent)
        assertEquals(65535, status.voltageMv)
        assertEquals(-1, status.currentCentiAmp)
        assertEquals(-1, status.batteryRemainingPercent)
    }
}
