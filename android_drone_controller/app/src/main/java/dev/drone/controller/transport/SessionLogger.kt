package dev.drone.controller.transport

import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Bounded asynchronous app-private CSV. Disk IO never holds the control gate. */
class SessionLogger(
    folder:File,
    private val maximumFileBytes:Long=8L*1024*1024,
    private val retainedFiles:Int=20,
    private val wallClockMs:()->Long=System::currentTimeMillis,
):Closeable {
    val file:File
    private val queue=ArrayBlockingQueue<String>(128)
    val dropped=AtomicLong(0)
    @Volatile private var closing=false
    @Volatile var error:String?=null
        private set
    @Volatile var sizeLimitReached=false
        private set
    private val worker:Thread
    init {
        require(maximumFileBytes>=256 && retainedFiles>=1)
        folder.mkdirs();file=File(folder,"rcbench-${System.currentTimeMillis()}-${System.nanoTime()}.csv")
        worker=Thread({
            try {
            // Only prune files created by this logger, inside its app-private folder.
            folder.listFiles()?.filter{it.isFile&&it.name.matches(Regex("rcbench-[0-9]+-[0-9]+\\.csv"))}
                ?.sortedByDescending{it.lastModified()}?.drop(retainedFiles-1)?.forEach{it.delete()}
            file.bufferedWriter().use { out ->
                val header="time_monotonic_ms,time_wall_ms,tcp,armed,heartbeat_age_ms,rc_age_ms,bench_enabled,bench_tx_channels,rx_channels,override_count,crc_errors,bench_reason,last_event,"+
                    "control_profile,authority_mode,authority_stage,connection_epoch,authority_epoch,control_tx_allowed,requested_channels,control_tx_channels,control_echo_channels,control_write_count,rc_echo_correlated,control_reason,authority_transition,"+
                    "arm_intent_id,arm_requested,arm_status,arm_socket_written,arm_ack_result,arm_heartbeat_confirmed,arm_reason,arm_blocker,requested_mode,mode_status,mode_ack_result,mode_heartbeat_confirmed,"+
                    "last_tx_age_ms,last_rx_age_ms,rc_interval_p50_ms,rc_interval_p95_ms,rc_interval_p99_ms,rc_jitter_p95_ms,gcs_heartbeats_written,sequence_gaps,duplicate_frames,reordered_frames,signed_rejected"
                out.appendLine(header)
                var bytes=header.toByteArray(Charsets.UTF_8).size.toLong()+1
                while(!closing||queue.isNotEmpty()) {
                    val row=queue.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS)
                    if(row!=null){
                        val size=row.toByteArray(Charsets.UTF_8).size.toLong()+1
                        if(sizeLimitReached||bytes+size>maximumFileBytes) {
                            sizeLimitReached=true;error="Nhật ký đạt giới hạn dung lượng; các dòng mới bị bỏ, cần xuất log"
                            dropped.incrementAndGet()
                        } else {out.appendLine(row);out.flush();bytes+=size}
                    }
                }
            }
            } catch(e:Exception) {
                error=e.message?.take(160)?:"Lỗi ghi log";closing=true;queue.clear()
            }
        },"rcbench-log").apply{isDaemon=true;start()}
    }
    fun record(state:SessionSnapshot,now:Long) {
        if(closing)return
        if(sizeLimitReached){dropped.incrementAndGet();return}
        val e=state.engine
        val live=state.live
        val authority=live?.authority
        val arm=live?.armCommand
        val mode=live?.modeCommand
        fun cell(s:Any?)="\""+(s?.toString()?:"").replace("\"","\"\"").replace('\n',' ')+"\""
        val row=listOf(now,wallClockMs(),state.tcpStatus,e.arm,e.heartbeatAgeMs,e.rcAgeMs,e.enabled,e.lastSent?.joinToString(";"),e.received?.take(4)?.joinToString(";"),state.txOverrides,state.crcErrors,e.reason,e.logs.firstOrNull(),
            live?.profile,authority?.mode,authority?.stage,authority?.connectionEpoch,authority?.authorityEpoch,authority?.canTransmit,
            live?.lastRequested?.joinToString(";"),live?.lastSent?.joinToString(";"),live?.lastEcho?.joinToString(";"),live?.rcWriteCount,authority?.rcEchoVerified,authority?.reason,authority?.transitions?.lastOrNull(),
            arm?.intent?.id,arm?.intent?.desiredArmed,arm?.status,arm?.socketWritten,arm?.ackResult,arm?.heartbeatConfirmed,arm?.reason,live?.armBlocker,
            mode?.requestedMode,mode?.status,mode?.ackResult,mode?.heartbeatConfirmed,state.lastTxAgeMs,state.lastRxAgeMs,
            state.rcTiming.intervalP50Ms,state.rcTiming.intervalP95Ms,state.rcTiming.intervalP99Ms,state.rcTiming.jitterP95Ms,state.gcsHeartbeatsWritten,
            state.parserDiagnostics.sequenceGaps,state.parserDiagnostics.duplicateFrames,state.parserDiagnostics.reorderedFrames,state.parserDiagnostics.signedRejected).joinToString(","){cell(it)}
        if(!queue.offer(row))dropped.incrementAndGet()
    }
    override fun close(){closing=true}
}
