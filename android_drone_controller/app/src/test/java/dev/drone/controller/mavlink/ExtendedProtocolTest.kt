package dev.drone.controller.mavlink

import org.junit.Assert.*
import org.junit.Test

class ExtendedProtocolTest {
    private fun hex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun decode(value:String):Telemetry = requireNotNull(TelemetryDecoder.decode(Mavlink2Parser().feed(hex(value)).single()))

    @Test fun manualControlMatchesIndependentPymavlinkWireFrame() {
        assertArrayEquals(hex(ExtendedVectors.TX_MANUAL),Mavlink2Encoder().manualControl(42,-250,500,0,-100))
        val f=Mavlink2Parser().feed(hex(ExtendedVectors.TX_MANUAL)).single()
        assertEquals(69,f.messageId)
        val p=f.payload.copyOf(30)
        assertEquals(-250,p.i16(0));assertEquals(500,p.i16(2));assertEquals(0,p.i16(4));assertEquals(-100,p.i16(6))
        assertEquals(0,p.u16(8));assertEquals(42,p.u8(10))
        assertTrue(p.copyOfRange(11,30).all{it==0.toByte()})
    }
    @Test fun readOnlyCommandAndParameterRequestsMatchIndependentVectors() {
        assertArrayEquals(hex(ExtendedVectors.TX_INTERVAL),Mavlink2Encoder().setMessageInterval(42,1,65,200000))
        assertArrayEquals(hex(ExtendedVectors.TX_REQUEST),Mavlink2Encoder().requestMessage(42,1,148))
        assertArrayEquals(hex(ExtendedVectors.TX_PARAM_READ),Mavlink2Encoder().parameterRead(42,1,"RC_OVERRIDE_TIME"))
    }
    @Test fun invalidRequestsCannotBecomeArmMotorOrUnboundedStreams() {
        val e=Mavlink2Encoder()
        for(id in listOf(70,69,76,400,209,999)) {
            assertThrows(IllegalArgumentException::class.java){e.requestMessage(1,1,id)}
            assertThrows(IllegalArgumentException::class.java){e.setMessageInterval(1,1,id,200000)}
        }
        for(interval in listOf(-1,0,1,199999,10000001))
            assertThrows(IllegalArgumentException::class.java){e.setMessageInterval(1,1,65,interval)}
        for(name in listOf("","RC X","rc1","X".repeat(17),"THR\n"))
            assertThrows(IllegalArgumentException::class.java){e.parameterRead(1,1,name)}
        assertThrows(IllegalArgumentException::class.java){e.parameterRead(0,1,"RC1_MIN")}
        assertThrows(IllegalArgumentException::class.java){e.manualControl(1,0,0,-1,0)}
        assertThrows(IllegalArgumentException::class.java){e.manualControl(1,1001,0,0,0)}
    }
    @Test fun positionPressureAndServoOffsetsAndUnitsMatchReference() {
        val p=decode(ExtendedVectors.RX_POSITION) as Telemetry.GlobalPosition
        assertEquals(123456,p.timeBootMs.toInt());assertEquals(137560123,p.latitudeE7);assertEquals(1005012345,p.longitudeE7)
        assertEquals(42123,p.altitudeMm);assertEquals(5123,p.relativeAltitudeMm)
        assertEquals(150,p.velocityNorthCms);assertEquals(-230,p.velocityEastCms);assertEquals(320,p.velocityDownCms)
        assertEquals(65535,p.headingCentiDegrees) // unknown heading sentinel preserved
        val pressure=decode(ExtendedVectors.RX_PRESSURE) as Telemetry.ScaledPressure
        assertEquals(1013.25f,pressure.absoluteHpa);assertEquals(-.125f,pressure.differentialHpa)
        assertEquals(2534,pressure.temperatureCentiDegrees)
        val servo=decode(ExtendedVectors.RX_SERVO) as Telemetry.ServoOutput
        assertEquals(123456,servo.timeUsec.toInt());assertEquals(2,servo.port)
        assertArrayEquals((1101..1108).toList().plus((1201..1208).toList()).toIntArray(),servo.channels)
    }
    @Test fun ackAndParameterDecodeReferenceFieldsWithoutImplyingRcAck() {
        val p=decode(ExtendedVectors.RX_PARAM) as Telemetry.ParameterValue
        assertEquals("RC_OVERRIDE_TIME",p.name);assertEquals(.5f,p.value);assertEquals(9,p.type)
        assertEquals(700,p.count);assertEquals(123,p.index)
        val a=decode(ExtendedVectors.RX_ACK) as Telemetry.CommandAck
        assertEquals(511,a.command);assertEquals(3,a.result);assertEquals(255,a.progress)
        assertEquals(-123,a.resultParam2);assertEquals(255,a.targetSystem);assertEquals(190,a.targetComponent)
    }
    @Test fun batterySentinelsVersionAndEkfDecodeIndependentVectors() {
        val b=decode(ExtendedVectors.RX_BATTERY) as Telemetry.BatteryStatus
        assertEquals(2,b.id);assertEquals(11870,b.voltagesMv[0]);assertEquals(65535,b.voltagesMv[1])
        assertEquals(-1,b.currentCentiAmp);assertEquals(-1,b.consumedMah);assertEquals(-1,b.remainingPercent)
        assertEquals(32767,b.temperatureCentiDegrees);assertEquals(0,b.timeRemainingSeconds)
        val v=decode(ExtendedVectors.RX_VERSION) as Telemetry.AutopilotVersion
        assertEquals(0x04080000L,v.flightSoftwareVersion);assertEquals(4,v.boardVersion.toInt())
        assertEquals(0x0102030405060708L,v.capabilities);assertEquals(java.lang.Long.parseUnsignedLong("8877665544332211",16),v.uid)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8),v.flightCustomVersion)
        val k=decode(ExtendedVectors.RX_EKF) as Telemetry.EkfStatus
        assertEquals(0x1234,k.flags);assertEquals(.125f,k.velocityVariance);assertEquals(.25f,k.horizontalVariance)
        assertEquals(.5f,k.verticalVariance);assertEquals(1f,k.compassVariance);assertEquals(2f,k.terrainVariance)
    }
    @Test fun everyExtendedFrameSurvivesAllFragmentBoundariesAndCoalescing() {
        val references=listOf(ExtendedVectors.RX_PARAM,ExtendedVectors.RX_PRESSURE,ExtendedVectors.RX_POSITION,
            ExtendedVectors.RX_SERVO,ExtendedVectors.RX_ACK,ExtendedVectors.RX_BATTERY,ExtendedVectors.RX_VERSION,ExtendedVectors.RX_EKF)
        for(encoded in references) {
            val b=hex(encoded)
            for(split in 0..b.size) {
                val parser=Mavlink2Parser()
                val frames=parser.feed(b.copyOfRange(0,split))+parser.feed(b.copyOfRange(split,b.size))
                assertEquals(1,frames.size);assertNotNull(TelemetryDecoder.decode(frames.single()))
                assertEquals(0L,parser.diagnostics.crcErrors)
            }
        }
        val parser=Mavlink2Parser()
        val stream=references.map(::hex).reduce{a,b->a+b}
        assertEquals(8,parser.feed(stream).size)
    }
    @Test fun dialectMetadataMatchesIndependentReferenceMaxLengthsAndCrcExtras() {
        val expected=mapOf(20 to (20 to 214),22 to (25 to 220),29 to (16 to 115),33 to (28 to 104),36 to (37 to 222),
            69 to (30 to 243),76 to (33 to 152),77 to (10 to 143),147 to (54 to 154),148 to (78 to 178),193 to (26 to 71))
        for((id,definition) in expected) {
            assertEquals(definition.first,CommonDialect.messages[id]!!.maximumLength)
            assertEquals(definition.second,CommonDialect.messages[id]!!.crcExtra)
        }
    }
    @Test fun wrongCrcExtraIsRejectedEvenWhenPayloadAndHeaderAreIntact() {
        val good=hex(GoldenVectors.RX_HEARTBEAT)
        val wrong=good.copyOf()
        wrong.putU16(wrong.size-2,MavlinkCrc.x25(wrong,1,wrong.size-3,51)) // HEARTBEAT extra must be 50
        val parser=Mavlink2Parser()
        val received=parser.feed(wrong+good)
        assertEquals(1,received.size)
        assertEquals(1L,parser.diagnostics.crcErrors)
        assertEquals(0,received.single().messageId)
    }
    @Test fun signedReferenceIsRejectedAcrossEverySplitAndFollowingFrameSurvives() {
        val signed=hex(GoldenVectors.RX_SIGNED_HEARTBEAT)
        val good=hex(GoldenVectors.RX_HEARTBEAT)
        for(split in 0..signed.size) {
            val parser=Mavlink2Parser()
            val frames=parser.feed(signed.copyOfRange(0,split))+parser.feed(signed.copyOfRange(split,signed.size)+good)
            assertEquals(1,frames.size)
            assertEquals(1L,parser.diagnostics.signedRejected)
            assertEquals(0L,parser.diagnostics.crcErrors)
        }
    }
    @Test fun zeroLengthFrameCannotInventTelemetryAndParserRecovers() {
        val empty=byteArrayOf(0xfd.toByte(),0,0,0,0,1,1,30,0,0,0,0)
        val parser=Mavlink2Parser()
        val frames=parser.feed(empty+hex(GoldenVectors.RX_HEARTBEAT))
        assertEquals(1,frames.size)
        assertEquals(1L,parser.diagnostics.malformedFrames)
        assertTrue(TelemetryDecoder.decode(frames.single()) is Telemetry.Heartbeat)
    }

}
