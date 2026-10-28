package dev.drone.rcbench.transport

import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Bounded asynchronous app-private CSV. Disk IO never holds the control gate. */
class SessionLogger(folder:File):Closeable {
    val file:File
    private val queue=ArrayBlockingQueue<String>(128)
    val dropped=AtomicLong(0)
    @Volatile private var closing=false
    @Volatile var error:String?=null
        private set
    private val worker:Thread
    init {
        folder.mkdirs();file=File(folder,"rcbench-${System.currentTimeMillis()}-${System.nanoTime()}.csv")
        worker=Thread({
            try {
            file.bufferedWriter().use { out ->
                out.appendLine("time_monotonic_ms,tcp,armed,heartbeat_age_ms,rc_age_ms,enabled,tx_channels,rx_channels,override_count,crc_errors,reason,last_event")
                while(!closing||queue.isNotEmpty()) {
                    val row=queue.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS)
                    if(row!=null){out.appendLine(row);out.flush()}
                }
            }
            } catch(e:Exception) {
                error=e.message?.take(160)?:"Lỗi ghi log";closing=true;queue.clear()
            }
        },"rcbench-log").apply{isDaemon=true;start()}
    }
    fun record(state:SessionSnapshot,now:Long) {
        if(closing)return
        val e=state.engine
        fun cell(s:Any?)="\""+(s?.toString()?:"").replace("\"","\"\"").replace('\n',' ')+"\""
        val row=listOf(now,state.tcpStatus,e.arm,e.heartbeatAgeMs,e.rcAgeMs,e.enabled,e.lastSent?.joinToString(";"),e.received?.take(4)?.joinToString(";"),state.txOverrides,state.crcErrors,e.reason,e.logs.firstOrNull()).joinToString(","){cell(it)}
        if(!queue.offer(row))dropped.incrementAndGet()
    }
    override fun close(){closing=true}
}
