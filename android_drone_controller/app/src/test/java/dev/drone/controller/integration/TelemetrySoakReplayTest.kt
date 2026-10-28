package dev.drone.controller.integration

import dev.drone.controller.mavlink.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TelemetrySoakReplayTest {
    @Test fun officialSitlThirtyMinuteSimulatedTraceSurvivesFragmentedJvmReplay() {
        val bytes=checkNotNull(javaClass.getResourceAsStream("/soak-afeb9c49.mavlink")).use{it.readBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        assertEquals("0c65b55c1322a10073f6b76240659aa8ed5585ac97aa7dd86248eea6eb0ecc05",hash)
        val parser=Mavlink2Parser();var offset=0;var step=1;var first:Long?=null;var last=0L;var armed=0
        while(offset<bytes.size) {
            val length=minOf(step,bytes.size-offset)
            for(frame in parser.feed(bytes,offset,length)) when(val data=TelemetryDecoder.decode(frame)) {
                is Telemetry.Attitude->{if(first==null)first=data.timeBootMs;last=data.timeBootMs}
                is Telemetry.Heartbeat->if(data.armed)armed++
                else->Unit
            }
            assertTrue(parser.bufferedBytes<=280)
            offset+=length;step=if(step==257)1 else step+1
        }
        assertEquals(14667L,parser.diagnostics.validFrames)
        assertEquals(0L,parser.diagnostics.crcErrors)
        assertEquals(0L,parser.diagnostics.unknownMessages)
        assertEquals(0L,parser.diagnostics.sequenceGaps)
        assertEquals(0,armed)
        assertTrue(last-checkNotNull(first)>=1_800_000L)
    }
}
