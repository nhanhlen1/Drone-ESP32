package dev.drone.controller.transport

import dev.drone.controller.mavlink.*
import org.junit.Assert.*
import org.junit.Test

class TelemetryRequestsTest {
    @Test fun duplicateAckCannotCompleteOrAdvanceOutstandingStreamRequest() {
        val requests=TelemetryRequests();val encoder=Mavlink2Encoder()
        assertNotNull(requests.next(encoder,42,1,0))
        requests.observe(MavFrame(10,42,1,77,byteArrayOf(255.toByte(),1,0),sequenceDisposition=SequenceDisposition.DUPLICATE),200)
        assertNull(requests.lastAck)
        assertNull(requests.next(encoder,42,1,300))
        assertNotNull(requests.next(encoder,42,1,800))
    }
    @Test fun finiteReadOnlyRequestsNoRetryAndNoSuccessFromAckAlone() {
        val requests=TelemetryRequests();val encoder=Mavlink2Encoder();val frames=mutableListOf<MavFrame>()
        val parser=Mavlink2Parser()
        for(time in 0L..60_000L step 50) requests.next(encoder,42,1,time)?.let { frames+=parser.feed(it) }
        assertEquals(11,frames.count{it.messageId==76}) // Adds EXTENDED_SYS_STATE for landed-state evidence.
        assertEquals(36,frames.count{it.messageId==20}) // Includes mapping, legacy SYSID and auxiliary-function guards.
        assertTrue(frames.all{it.messageId==76 || it.messageId==20})
        assertNull(requests.next(encoder,42,1,120_000))
        val ack=byteArrayOf(0xff.toByte(),1,0,0,0,0,0,0,255.toByte(),190.toByte())
        requests.observe(MavFrame(1,42,1,77,ack),120_001)
        assertTrue(requests.lastAck!!.contains("chấp nhận"))
        assertTrue(requests.summary().none{it.contains("đã quan sát")})
        requests.observe(MavFrame(2,42,1,65,ByteArray(42)),120_002)
        assertTrue(requests.summary().first().contains("đã quan sát"))
    }
    @Test fun oneCommandAwaitedAndUnsupportedProgressTimeoutStayBounded() {
        val requests=TelemetryRequests();val encoder=Mavlink2Encoder()
        assertNotNull(requests.next(encoder,42,1,0))
        assertNull(requests.next(encoder,42,1,799))
        requests.observe(MavFrame(1,42,1,77,byteArrayOf(255.toByte(),1,5)),799)
        assertNotNull(requests.next(encoder,42,1,800))
        // A denied request doesn't trigger an unbounded retry or grant control.
        requests.observe(MavFrame(2,42,1,77,byteArrayOf(255.toByte(),1,2)),810)
        assertNull(requests.next(encoder,42,1,900))
        assertNotNull(requests.next(encoder,42,1,950))
        assertTrue(requests.summary().none{it.contains("đã quan sát")})
    }
}
