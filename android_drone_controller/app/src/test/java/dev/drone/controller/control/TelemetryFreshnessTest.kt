package dev.drone.controller.control

import dev.drone.controller.mavlink.*
import org.junit.Assert.*
import org.junit.Test

class TelemetryFreshnessTest {
    @Test fun duplicateOrReorderedHeartbeatCannotKeepDeadFlightControllerFresh() {
        for(disposition in listOf(SequenceDisposition.DUPLICATE,SequenceDisposition.REORDERED)) {
            var now=0L;val engine=BenchEngine(MonotonicClock{now})
            engine.connected();engine.foreground(true)
            val heartbeat=MavFrame(10,42,1,0,byteArrayOf(0,0,0,0,2,3,0,3,3))
            engine.onFrame(heartbeat)
            assertEquals(ArmState.DISARMED,engine.snapshot().arm)
            now=1400;engine.onFrame(heartbeat.copy(sequenceDisposition=disposition))
            assertEquals(1400L,engine.snapshot().heartbeatAgeMs)
            now=1500;assertEquals(ArmState.UNKNOWN,engine.snapshot().arm)
        }
    }
    @Test fun staleRcPacketCannotRefreshEchoOrOverwriteCurrentReading() {
        var now=0L;val engine=BenchEngine(MonotonicClock{now})
        engine.connected();engine.foreground(true)
        engine.onFrame(MavFrame(10,42,1,0,byteArrayOf(0,0,0,0,2,3,0,3,3)))
        fun rc(pwm:Int,status:SequenceDisposition)=MavFrame(11,42,1,65,ByteArray(42).apply {
            this[4]=pwm.toByte();this[5]=(pwm ushr 8).toByte();this[40]=4
        },sequenceDisposition=status)
        engine.onFrame(rc(1500,SequenceDisposition.IN_ORDER))
        now=100;engine.onFrame(rc(1550,SequenceDisposition.DUPLICATE))
        assertEquals(1500,engine.snapshot().received!![0])
        assertEquals(100L,engine.snapshot().rcAgeMs)
        engine.onFrame(rc(1550,SequenceDisposition.IN_ORDER))
        assertEquals(1550,engine.snapshot().received!![0])
        assertEquals(0L,engine.snapshot().rcAgeMs)
    }
}
