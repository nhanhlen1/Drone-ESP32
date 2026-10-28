package dev.drone.controller.transport

import org.junit.Assert.*
import org.junit.Test

class TransmitTimingTest {
    @Test fun thirtyMinutesOfNominalScheduleStaysBoundedAndKeepsMeasuredIntervals() {
        val timing=TransmitTiming()
        // Virtual 30-minute clock: a bounded-statistics test, not 30 minutes of real Wi-Fi.
        repeat(36_001){timing.record(it*50L)}
        val s=timing.snapshot()
        assertEquals(256,s.samples)
        assertEquals(50.0,s.intervalP50Ms!!,0.0)
        assertEquals(50.0,s.intervalP95Ms!!,0.0)
        assertEquals(50.0,s.intervalP99Ms!!,0.0)
        assertEquals(0.0,s.jitterP95Ms!!,0.0)
    }
    @Test fun delayIsReportedAndNeverConvertedToCatchUpSamples() {
        val timing=TransmitTiming()
        listOf(0L,50,100,150,200,250,300,350,400,450,950).forEach(timing::record)
        val s=timing.snapshot()
        assertEquals(10,s.samples)
        assertEquals(50.0,s.intervalP50Ms!!,0.0)
        assertEquals(500.0,s.intervalP95Ms!!,0.0)
        assertEquals(450.0,s.jitterP95Ms!!,0.0)
        timing.reset();assertNull(timing.snapshot().intervalP50Ms)
    }
}
