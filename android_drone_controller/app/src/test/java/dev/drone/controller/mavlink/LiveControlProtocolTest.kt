package dev.drone.controller.mavlink

import org.junit.Assert.*
import org.junit.Test

/** Golden bytes generated independently by pymavlink 2.4.49 ardupilotmega v2:
 * MAVLink(None,srcSystem=255,srcComponent=190),seq=0;
 * MAVLink_command_long_message(42,1,cmd,0,p1,p2,0,0,0,0,0).pack(mav).
 * They deliberately include the truncation of trailing confirmation=0.
 */
class LiveControlProtocolTest {
    private fun bytes(hex:String)=hex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()

    @Test fun explicitArmKeepsForceAndUnusedParametersZeroAgainstIndependentVector() {
        val frame=Mavlink2Encoder().armDisarm(42,1,true)
        assertArrayEquals(bytes("fd20000000ffbe4c00000000803f00000000000000000000000000000000000000000000000090012a010364"),frame)
        val decoded=Mavlink2Parser().feed(frame).single()
        assertEquals(76,decoded.messageId)
        assertEquals(400,decoded.payload.u16(28))
        assertEquals(1f,decoded.payload.f32(0))
        assertEquals(0f,decoded.payload.f32(4))
        for(index in 8 until 28)assertEquals(0,decoded.payload.u8(index))
    }

    @Test fun explicitDisarmKeepsForceZeroAgainstIndependentVector() {
        assertArrayEquals(bytes("fd20000000ffbe4c00000000000000000000000000000000000000000000000000000000000090012a01143c"),
            Mavlink2Encoder().armDisarm(42,1,false))
    }

    @Test fun stabilizeAndLoiterModeCommandsMatchIndependentVectors() {
        assertArrayEquals(bytes("fd20000000ffbe4c00000000803f000000000000000000000000000000000000000000000000b0002a012918"),
            Mavlink2Encoder().flightMode(42,1,0))
        assertArrayEquals(bytes("fd20000000ffbe4c00000000803f0000a0400000000000000000000000000000000000000000b0002a0139a0"),
            Mavlink2Encoder().flightMode(42,1,5))
    }

    @Test fun operatorCommandsNeverBroadcastOrAddressTheGcs() {
        for(target in listOf(0,255,-1,256)) {
            assertThrows(IllegalArgumentException::class.java){Mavlink2Encoder().armDisarm(target,1,true)}
            assertThrows(IllegalArgumentException::class.java){Mavlink2Encoder().flightMode(target,1,0)}
        }
        assertThrows(IllegalArgumentException::class.java){Mavlink2Encoder().armDisarm(42,0,false)}
        assertThrows(IllegalArgumentException::class.java){Mavlink2Encoder().flightMode(42,1,-1)}
        assertThrows(IllegalArgumentException::class.java){Mavlink2Encoder().flightMode(42,1,256)}
    }

    @Test fun extendedStateUsesOfficialMessage245AndDistinguishesGroundFromAir() {
        val parser=Mavlink2Parser()
        val ground=TelemetryDecoder.decode(parser.feed(bytes("fd0200000d2a01f50000000158f5")).single()) as Telemetry.ExtendedSysState
        val air=TelemetryDecoder.decode(parser.feed(bytes("fd0200000e2a01f5000000023709")).single()) as Telemetry.ExtendedSysState
        assertEquals(0,ground.vtolState)
        assertTrue(ground.onGround)
        assertFalse(ground.inAir)
        assertFalse(air.onGround)
        assertTrue(air.inAir)
        assertTrue(245 in Mavlink2Encoder.READABLE_MESSAGES)
        assertEquals(0L,parser.diagnostics.unsupportedMessages)
    }

    @Test fun missingLandedStateDoesNotBecomeGroundEvidence() {
        val unknown=TelemetryDecoder.decode(MavFrame(0,42,1,245,byteArrayOf(0))) as Telemetry.ExtendedSysState
        assertEquals(0,unknown.landedState)
        assertFalse(unknown.onGround)
        assertFalse(unknown.inAir)
        assertNull(TelemetryDecoder.decode(MavFrame(0,42,1,245,byteArrayOf(0,1,2))))
    }

    @Test fun preArmHealthIsExplicitAndOtherEnabledFailuresRemainVisible() {
        val prearm=1L shl 28
        val status=Telemetry.SysStatus(12000,-1,-1,prearm or 1,prearm or 1,prearm,
            0x80000000,0x80000000,0)
        assertTrue(status.preArmCheckPresent)
        assertTrue(status.preArmCheckEnabled)
        assertTrue(status.preArmCheckHealthy)
        assertEquals(1L,status.unhealthyEnabledSensors)
        assertEquals(0x80000000L,status.unhealthyEnabledSensorsExtended)
        val absent=Telemetry.SysStatus(65535,-1,-1,0,0,0)
        assertFalse(absent.preArmCheckPresent)
        assertFalse(absent.preArmCheckEnabled)
        assertFalse(absent.preArmCheckHealthy)
    }

    @Test fun ekfFlagsPreserveUnknownOrUninitializedStateRatherThanInventReadiness() {
        val uninitialized=Telemetry.EkfStatus(1024,0f,0f,0f,0f,0f)
        assertTrue(uninitialized.uninitialized)
        assertFalse(uninitialized.attitudeValid)
        val attitudeOnly=uninitialized.copy(flags=1)
        assertTrue(attitudeOnly.attitudeValid)
        assertFalse(attitudeOnly.horizontalAbsolutePositionValid)
        assertFalse(attitudeOnly.uninitialized)
    }
}
