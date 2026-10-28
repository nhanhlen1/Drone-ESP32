package dev.drone.controller.mavlink

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DialectParserPropertyTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun packet(id:Int=0,seq:Int=0,sys:Int=42,comp:Int=1,payload:ByteArray=byteArrayOf(0,0,0,0,2,3,0,3,3),flags:Int=0,extra:Int=CommonDialect.messages.getValue(id).crcExtra):ByteArray {
        val out=ByteArray(payload.size+12+if(flags and 1!=0)13 else 0)
        out[0]=0xfd.toByte();out[1]=payload.size.toByte();out[2]=flags.toByte()
        out[4]=seq.toByte();out[5]=sys.toByte();out[6]=comp.toByte()
        out[7]=id.toByte();out[8]=(id ushr 8).toByte();out[9]=(id ushr 16).toByte()
        payload.copyInto(out,10);out.putU16(10+payload.size,MavlinkCrc.x25(out,1,payload.size+9,extra))
        return out
    }
    @Test fun fullPinnedDialectIncludesCommonArduPilotAndTransitiveVendorMessages() {
        assertEquals(301,CommonDialect.messages.size)
        assertEquals("SYS_STATUS",CommonDialect.messages.getValue(1).name)
        assertEquals("SENSOR_OFFSETS",CommonDialect.messages.getValue(150).name)
        assertEquals(134,CommonDialect.messages.getValue(150).crcExtra)
        assertTrue(CommonDialect.messages.values.all{it.crcExtra in 0..255 && it.maximumLength in 1..255 && it.minimumLength in 1..it.maximumLength})
        assertEquals("ICAROUS_HEARTBEAT",CommonDialect.messages.getValue(42000).name)
    }
    @Test fun userReported1470WireValueHasIndependentGoldenVectorWithoutClaimingHardwareTest() {
        assertArrayEquals(hex(DialectVectors.TX_OVERRIDE_1470),Mavlink2Encoder().rcOverride(1,1,intArrayOf(1470,1500,1100,1500)))
    }
    @Test fun knownDialectButUnsupportedTelemetryUsesCorrectCrcAndSeparateCounter() {
        val refs=listOf(DialectVectors.RX_VFR_HUD,DialectVectors.RX_SENSOR_OFFSETS,DialectVectors.RX_QUATERNION,DialectVectors.RX_AHRS,DialectVectors.RX_RC_CHANNELS_RAW)
        val p=Mavlink2Parser();val frames=refs.flatMap{p.feed(hex(it))}
        assertEquals(listOf(74,150,31,163,35),frames.map{it.messageId})
        assertEquals(5L,p.diagnostics.validFrames);assertEquals(5L,p.diagnostics.unsupportedMessages)
        assertEquals(0L,p.diagnostics.crcErrors);assertEquals(0L,p.diagnostics.unknownMessages)
        assertTrue(frames.all{TelemetryDecoder.decode(it)==null})
    }
    @Test fun genuineCorruptArduPilotMessageCountsCrcButUnknownDialectNeverGuessesZeroExtra() {
        val bad=hex(DialectVectors.RX_SENSOR_OFFSETS).also{it[12]=(it[12].toInt() xor 1).toByte()}
        val unknown=packet(id=999999,payload=ByteArray(50){0xfd.toByte()},extra=0)
        var time=0L;val p=Mavlink2Parser(clockMs={time})
        p.feed(bad)
        // A corrupt payload contains 0xFE/0xFF which resembles a long V1 frame.
        // The parser must not scan its unverified body for nested "good" frames;
        // the explicit fragment watchdog bounds recovery instead.
        time=1500;p.expirePartialFrame()
        val result=p.feed(unknown+packet())
        assertEquals(1,result.size);assertEquals(1L,p.diagnostics.crcErrors)
        assertEquals(1L,p.diagnostics.unknownMessages);assertEquals(0L,p.diagnostics.unsupportedMessages)
    }
    @Test fun sequencesAreTrackedPerSystemAndComponentRatherThanGlobalStream() {
        val p=Mavlink2Parser()
        listOf(packet(seq=10,sys=1),packet(seq=200,sys=2),packet(seq=100,sys=1,comp=2),
            packet(seq=11,sys=1),packet(seq=201,sys=2),packet(seq=101,sys=1,comp=2)).forEach{p.feed(it)}
        assertEquals(3,p.sourceSequences.size);assertEquals(0L,p.diagnostics.sequenceGaps)
        assertTrue(p.sourceSequences.values.all{it.frames==2L && it.sequenceGaps==0L})
    }
    @Test fun duplicatesAndReorderNeverMoveHighWaterMarkBackwards() {
        val p=Mavlink2Parser()
        listOf(100,102,101,102,103).forEach{p.feed(packet(seq=it))}
        val d=p.sourceSequences.getValue(MavSource(42,1))
        assertEquals(103,d.lastAcceptedSequence);assertEquals(1L,d.sequenceGaps)
        assertEquals(1L,d.reordered);assertEquals(1L,d.duplicates);assertEquals(5L,d.frames)
        assertEquals(1L,p.diagnostics.sequenceGaps);assertEquals(1L,p.diagnostics.reorderedFrames)
        assertEquals(1L,p.diagnostics.duplicateFrames)
    }
    @Test fun sequenceWrapIsNotLossAndInvalidCrcCannotAdvanceSequence() {
        val p=Mavlink2Parser()
        listOf(254,255,0,1).forEach{p.feed(packet(seq=it))}
        assertEquals(0L,p.diagnostics.sequenceGaps)
        val bad=packet(seq=2).also{it[it.size-1]=(it.last().toInt() xor 1).toByte()}
        p.feed(bad+packet(seq=3))
        assertEquals(1L,p.diagnostics.crcErrors);assertEquals(1L,p.diagnostics.sequenceGaps)
        assertEquals(5L,p.sourceSequences.getValue(MavSource(42,1)).frames)
    }
    @Test fun skippedUnknownDialectCanExplainASequenceGapSoItIsNotRadioLoss() {
        val p=Mavlink2Parser()
        p.feed(packet(seq=10)+packet(id=999999,seq=11,extra=123)+packet(seq=12))
        assertEquals(1L,p.diagnostics.unknownMessages);assertEquals(0L,p.diagnostics.crcErrors)
        assertEquals(1L,p.diagnostics.sequenceGaps)
    }
    @Test fun backwardsSequenceAloneIsNotClassifiedAsVehicleReboot() {
        val p=Mavlink2Parser();p.feed(packet(seq=100)+packet(seq=5))
        assertEquals(1L,p.diagnostics.reorderedFrames)
        assertEquals(100,p.sourceSequences.getValue(MavSource(42,1)).lastAcceptedSequence)
        p.resetSourceSequence(42,1);p.feed(packet(seq=6))
        val d=p.sourceSequences.getValue(MavSource(42,1))
        assertEquals(6,d.lastAcceptedSequence);assertEquals(1L,d.explicitResets)
        assertEquals(0L,d.sequenceGaps)
    }
    @Test fun connectionResetClearsSourceCountersAndDropsPartialPacket() {
        val p=Mavlink2Parser();val f=packet(seq=10)
        p.feed(f);p.feed(f.copyOfRange(0,7));p.reset();p.feed(packet(seq=1))
        assertEquals(1L,p.diagnostics.validFrames);assertEquals(0L,p.diagnostics.sequenceGaps)
        assertEquals(1,p.sourceSequences.size);assertEquals(0,p.bufferedBytes)
        assertEquals(packet(seq=1).size.toLong(),p.diagnostics.bytesReceived)
    }
    @Test fun partialPacketTimeoutDropsOnlyStalledFragmentAndAllowsFollowingFrame() {
        var time=0L;val p=Mavlink2Parser(clockMs={time},partialFrameTimeoutMs=1500)
        p.feed(packet().copyOfRange(0,8));time=1499;assertFalse(p.expirePartialFrame())
        time=1500;assertTrue(p.expirePartialFrame());assertEquals(0,p.bufferedBytes)
        assertEquals(1,p.feed(packet()).size);assertEquals(1L,p.diagnostics.partialTimeouts)
        assertEquals(1L,p.diagnostics.resynchronizations)
    }
    @Test fun feedAutomaticallyExpiresOldFragmentBeforeAcceptingNewBytes() {
        var time=0L;val p=Mavlink2Parser(clockMs={time})
        p.feed(packet().copyOfRange(0,11));time=1501
        assertEquals(1,p.feed(packet()).size)
        assertEquals(1L,p.diagnostics.partialTimeouts);assertEquals(0L,p.diagnostics.crcErrors)
    }
    @Test fun signedFramesConsumeSignatureAndDoNotUpdatePerSourceSequence() {
        val p=Mavlink2Parser();val signed=hex(GoldenVectors.RX_SIGNED_HEARTBEAT)
        p.feed(signed)
        assertEquals(1L,p.diagnostics.signedFrames);assertEquals(1L,p.diagnostics.signedRejected)
        assertTrue(p.sourceSequences.isEmpty())
        p.feed(packet(seq=10));assertEquals(10,p.sourceSequences.getValue(MavSource(42,1)).lastAcceptedSequence)
    }
    @Test fun unsupportedFlagFrameCannotInjectHeadersFromSignatureOrPayload() {
        val f=packet(flags=3,payload=ByteArray(9){0xfd.toByte()})
        for(i in f.size-13 until f.size)f[i]=0xfd.toByte()
        val p=Mavlink2Parser();val frames=p.feed(f+packet())
        assertEquals(1,frames.size);assertEquals(1L,p.diagnostics.unsupportedFlags)
        assertEquals(1L,p.diagnostics.signedFrames);assertEquals(0L,p.diagnostics.crcErrors)
    }
    @Test fun randomNoiseContainingMagicIsBoundedAndEventuallyResynchronizes() {
        val random=Random(20261029);val p=Mavlink2Parser()
        repeat(1000) {
            val n=random.nextInt(1,150);val bytes=ByteArray(n){random.nextInt(256).toByte()}
            p.feed(bytes);assertTrue(p.bufferedBytes in 0..280)
        }
        // Flush any length-delimited unknown or v1 fragment; their payload is not
        // scanned for nested headers, which would invent frames in signed data.
        p.feed(ByteArray(600));val frames=p.feed(hex(GoldenVectors.RX_HEARTBEAT))
        assertEquals(1,frames.size);assertEquals(0,frames.single().messageId)
        assertTrue(p.diagnostics.bytesReceived>50000);assertEquals(0,p.bufferedBytes)
    }
    @Test fun everyDefinedMessageAtItsMaximumLengthSurvivesRandomTcpFragments() {
        val random=Random(21);val p=Mavlink2Parser();var count=0
        for((id,d) in CommonDialect.messages) {
            val payload=ByteArray(d.maximumLength){random.nextInt(256).toByte()}
            val encoded=packet(id=id,seq=count and 255,payload=payload)
            val result=mutableListOf<MavFrame>();var i=0
            while(i<encoded.size) {
                val n=minOf(random.nextInt(1,18),encoded.size-i)
                result+=p.feed(encoded,i,n);i+=n;assertTrue(p.bufferedBytes<=280)
            }
            assertEquals(1,result.size);assertEquals(id,result.single().messageId)
            assertArrayEquals(payload,result.single().payload);count++
        }
        assertEquals(301,count);assertEquals(0L,p.diagnostics.crcErrors)
        assertEquals(0L,p.diagnostics.malformedFrames);assertEquals(0,p.bufferedBytes)
    }
    @Test fun sourceSequenceStorageIsBoundedUnderManySenderIdentities() {
        val p=Mavlink2Parser()
        for(sys in 1..200)p.feed(packet(seq=10,sys=sys))
        assertEquals(128,p.sourceSequences.size);assertEquals(72L,p.diagnostics.sequenceSourceEvictions)
        assertEquals(0L,p.diagnostics.sequenceGaps)
    }
    @Test fun coalescedFramesCarryOrderDispositionWithoutDroppingDiagnosticDuplicates() {
        val p=Mavlink2Parser()
        val stream=listOf(100,100,102,101,103).map{packet(seq=it)}.reduce{a,b->a+b}
        val frames=p.feed(stream)
        assertEquals(listOf(SequenceDisposition.FIRST,SequenceDisposition.DUPLICATE,
            SequenceDisposition.GAP,SequenceDisposition.REORDERED,SequenceDisposition.IN_ORDER),
            frames.map{it.sequenceDisposition})
        assertEquals(5,frames.size);assertEquals(5L,p.diagnostics.validFrames)
        assertEquals(1L,p.diagnostics.duplicateFrames);assertEquals(1L,p.diagnostics.reorderedFrames)
    }
    @Test fun frameOrderDispositionScopesMixedSourcesAndHandlesWrap() {
        val stream=packet(seq=255,sys=1)+packet(seq=50,sys=2)+packet(seq=0,sys=1,comp=2)+
            packet(seq=0,sys=1)+packet(seq=50,sys=2)+packet(seq=255,sys=1,comp=2)
        val frames=Mavlink2Parser().feed(stream)
        assertEquals(listOf(SequenceDisposition.FIRST,SequenceDisposition.FIRST,SequenceDisposition.FIRST,
            SequenceDisposition.IN_ORDER,SequenceDisposition.DUPLICATE,SequenceDisposition.REORDERED),
            frames.map{it.sequenceDisposition})
    }
    @Test fun fragmentedStreamTagsExactlyAsCoalescedStreamAndExplicitResetMakesNewFirst() {
        val stream=packet(seq=254)+packet(seq=255)+packet(seq=255)+packet(seq=0)+packet(seq=254)
        val coalesced=Mavlink2Parser().feed(stream)
        val p=Mavlink2Parser();val fragmented=stream.flatMap{p.feed(byteArrayOf(it))}
        assertEquals(coalesced.map{it.sequenceDisposition},fragmented.map{it.sequenceDisposition})
        assertEquals(SequenceDisposition.REORDERED,fragmented.last().sequenceDisposition)
        p.resetSourceSequence(42,1)
        assertEquals(SequenceDisposition.FIRST,p.feed(packet(seq=5)).single().sequenceDisposition)
    }
    @Test fun manualFrameBuildersRemainCompatibleButParserAlwaysTagsFirst() {
        assertEquals(SequenceDisposition.IN_ORDER,MavFrame(0,42,1,0,byteArrayOf(1)).sequenceDisposition)
        assertEquals(SequenceDisposition.FIRST,Mavlink2Parser().feed(packet()).single().sequenceDisposition)
    }

}
