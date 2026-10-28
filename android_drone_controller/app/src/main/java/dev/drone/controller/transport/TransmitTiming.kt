package dev.drone.controller.transport

import kotlin.math.abs
import kotlin.math.ceil

data class TransmitTimingSnapshot(
    val samples:Int=0,val intervalP50Ms:Double?=null,val intervalP95Ms:Double?=null,
    val intervalP99Ms:Double?=null,val jitterP95Ms:Double?=null,val largestIntervalMs:Long?=null,
)

/** Bounded observed socket-write timing, never a claim of network latency or FC acceptance. */
internal class TransmitTiming(private val capacity:Int=256,private val nominalMs:Long=50) {
    init { require(capacity>0 && nominalMs>0) }
    private val intervals=ArrayDeque<Long>()
    private var previous:Long?=null
    @Synchronized fun record(now:Long) {
        previous?.let { old ->
            if(now>=old) {
                intervals.addLast(now-old)
                if(intervals.size>capacity)intervals.removeFirst()
            }
        }
        previous=now
    }
    @Synchronized fun reset(){intervals.clear();previous=null}
    @Synchronized fun snapshot():TransmitTimingSnapshot {
        if(intervals.isEmpty())return TransmitTimingSnapshot()
        val sorted=intervals.sorted()
        fun percentile(values:List<Long>,p:Double)=values[(ceil(values.size*p).toInt()-1).coerceAtLeast(0)].toDouble()
        return TransmitTimingSnapshot(sorted.size,percentile(sorted,.5),percentile(sorted,.95),
            percentile(sorted,.99),percentile(intervals.map{abs(it-nominalMs)}.sorted(),.95),sorted.last())
    }
}
