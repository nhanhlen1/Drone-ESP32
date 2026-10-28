package dev.drone.rcbench.control

import dev.drone.rcbench.mavlink.MavFrame
import dev.drone.rcbench.mavlink.Telemetry
import dev.drone.rcbench.mavlink.TelemetryDecoder
import kotlin.math.abs
import kotlin.math.roundToInt

/** All time values are local monotonic milliseconds, not vehicle boot time. */
fun interface MonotonicClock { fun nowMs(): Long }
enum class ArmState { UNKNOWN, DISARMED, ARMED }
data class ChannelCalibration(val minimum:Int=1100,val maximum:Int=1900,val trim:Int=1500,val verified:Boolean=false) {
    fun valid() = minimum in 900..2100 && maximum in 900..2100 && trim > minimum && trim < maximum
    fun pwm(value:Float, throttle:Boolean):Int {
        require(valid() && value.isFinite())
        val x=if(throttle)value.coerceIn(0f,1f) else value.coerceIn(-1f,1f)
        return (if(throttle)minimum+x*(maximum-minimum) else if(x>=0)trim+x*(maximum-trim) else trim+x*(trim-minimum)).roundToInt().coerceIn(minimum,maximum)
    }
}
data class ControlPermit(val epoch:Long,val targetSystem:Int,val targetComponent:Int,val channels:IntArray)
data class EngineSnapshot(
    val connected:Boolean,val arm:ArmState,val heartbeatAgeMs:Long?,val rcAgeMs:Long?,
    val targetSystem:Int?,val targetComponent:Int?,val ready:Boolean,val enabled:Boolean,
    val deadman:Boolean,val modeChannel:Int,val reason:String,val channels:List<ChannelCalibration>,
    val lastSent: IntArray?,val received:IntArray?,val telemetry:Map<String,Telemetry>,val logs:List<String>,
    val lastOverrideMs:Long?,val overrides:Long,val lastRxMs:Long?,val sequenceGaps:Long,
)

/** BENCH ONLY. A lease never survives a fault, focus loss, reconnect, or mode change.
 * Fresh unsigned CRC-valid telemetry is NOT cryptographic authentication or an
 * atomic proof of the aircraft's current state between HEARTBEAT packets.
 */
class BenchEngine(private val clock:MonotonicClock) {
    companion object {
        const val HEARTBEAT_TIMEOUT_MS=1500L
        const val RC_TIMEOUT_MS=1200L
        const val DEADMAN_TIMEOUT_MS=200L
        const val SEND_INTERVAL_MS=50L // maximum 20Hz, never catch up missed ticks
        const val FEEDBACK_TIMEOUT_MS=750L
        const val YAW_BENCH_SCALE=.25f // Never send full rudder; ARMING_RUDDER is unverified.
    }
    private var connected=false
    private var foreground=false
    private var targetSystem:Int?=null
    private var targetComponent:Int?=null
    private var heartbeat:Telemetry.Heartbeat?=null
    private var heartbeatAt:Long?=null
    private var rcAt:Long?=null
    private var rxAt:Long?=null
    private var actual:IntArray?=null
    private var enabled=false
    private var held=false
    private var pulseAt:Long?=null
    private var epoch=0L
    private var mode=0
    private var input=floatArrayOf(0f,0f,0f,0f) // roll,pitch,throttle,yaw
    private var single=0f
    private var reason="Chưa kết nối; không có quyền phát RC"
    private var calibration=List(4){ChannelCalibration()}
    private val telemetry=linkedMapOf<String,Telemetry>()
    private val logs=ArrayDeque<String>()
    private var lastSend:Long?=null
    private var sent:IntArray?=null
    private var overrides=0L
    private var firstOverride:Long?=null
    private var feedbackAt:Long?=null
    private var previousSeq:Int?=null
    private var sequenceGaps=0L
    private val history=ArrayDeque<Pair<Long,IntArray>>()

    @Synchronized fun log(message:String) {
        logs.addFirst("${clock.nowMs()}ms  ${message.take(220)}")
        while(logs.size>100)logs.removeLast()
    }
    private fun age(at:Long?):Long?=at?.let{(clock.nowMs()-it).coerceAtLeast(0)}
    private fun armState():ArmState {
        if(!connected || heartbeatAt==null || age(heartbeatAt)!!>=HEARTBEAT_TIMEOUT_MS)return ArmState.UNKNOWN
        return if(heartbeat!!.armed)ArmState.ARMED else if(heartbeat!!.systemStatus in 3..4)ArmState.DISARMED else ArmState.UNKNOWN
    }
    private fun healthReason():String? {
        if(!connected)return "TCP chưa kết nối"
        if(!foreground)return "App không ở foreground/có focus"
        when(armState()) {
            ArmState.ARMED->return "ARMED: cấm toàn bộ RC override"
            ArmState.UNKNOWN->return "HEARTBEAT chưa rõ/đã quá hạn: cấm RC"
            else->Unit
        }
        if(age(rcAt)==null || age(rcAt)!!>=RC_TIMEOUT_MS)return "RC_CHANNELS chưa có/đã quá hạn"
        if(actual==null)return "Chưa có bản tin RC_CHANNELS"
        val selected=if(mode==0)(0..3).toList() else listOf(mode-1)
        if(selected.any{!calibration[it].valid()||!calibration[it].verified})return "Đối chiếu MIN/MAX/TRIM và xác nhận từng kênh trước"
        // ArduPilot chancount is physical HAL receiver count; RC_OPTIONS=33 may
        // keep it zero even while raw radio_in contains applied overrides.
        // Permit one bounded bootstrap; never treat matching telemetry as an ACK.
        return null
    }
    @Synchronized fun connected() {
        connected=true; targetSystem=null;targetComponent=null;heartbeat=null;heartbeatAt=null;rcAt=null;rxAt=null;actual=null
        previousSeq=null;sequenceGaps=0;telemetry.clear();sent=null;lastSend=null;overrides=0
        calibration=calibration.map{it.copy(verified=false)}
        stop("TCP kết nối; cần HEARTBEAT DISARMED, RC_CHANNELS và xác nhận mới")
    }
    @Synchronized fun disconnected(why:String) {
        connected=false;heartbeat=null;heartbeatAt=null;rcAt=null;actual=null;targetSystem=null;targetComponent=null
        stop(why)
    }
    @Synchronized fun foreground(active:Boolean) {
        foreground=active
        if(!active)stop("Mất focus/foreground: đã ngừng RC; phải kiểm tra timeout ArduPilot")
    }
    @Synchronized fun stop(why:String) {
        enabled=false;held=false;pulseAt=null;epoch++;input=floatArrayOf(0f,0f,0f,0f);single=0f
        firstOverride=null;feedbackAt=null;history.clear();reason=why
        log(why)
    }
    @Synchronized fun streamFault(why:String) {
        heartbeat=null;heartbeatAt=null
        stop(why)
    }
    @Synchronized fun configure(channel:Int,c:ChannelCalibration):Boolean {
        if(channel !in 1..4 || !c.valid()){log("Cấu hình RC không hợp lệ");return false}
        stop("Thay cấu hình kênh; phải bật kiểm tra lại")
        calibration=calibration.mapIndexed{i,old->if(i==channel-1)c else old}
        return true
    }
    @Synchronized fun selectMode(channel:Int) {
        if(channel !in 0..4)return
        stop("Đổi chế độ/kênh; không giữ giá trị joystick cũ")
        mode=channel
    }
    @Synchronized fun enableTest():Boolean {
        val error=healthReason()
        if(error!=null){stop(error);return false}
        stop("Đã bật quyền kiểm tra DISARMED; giữ deadman để phát")
        enabled=true;return true
    }
    @Synchronized fun deadman(active:Boolean) {
        if(!active){stop("Nhả deadman: không gửi trung tính/release; kiểm tra RC_OVERRIDE_TIME=0.5s phía ArduPilot");return}
        if(!enabled)return
        if(healthReason()!=null){stop(healthReason()!!);return}
        held=true;pulseAt=clock.nowMs()
    }
    @Synchronized fun sticks(roll:Float,pitch:Float,throttle:Float,yaw:Float) {
        if(!enabled)return
        if(!listOf(roll,pitch,throttle,yaw).all{it.isFinite()}){stop("Joystick không hữu hạn");return}
        input=floatArrayOf(roll.coerceIn(-1f,1f),pitch.coerceIn(-1f,1f),throttle.coerceIn(0f,1f),yaw.coerceIn(-1f,1f))
    }
    @Synchronized fun singleChannel(channel:Int,value:Float) {
        if(!enabled || mode!=channel)return
        if(!value.isFinite()){stop("Kênh thử không hữu hạn");return}
        single=if(channel==3)value.coerceIn(0f,1f) else value.coerceIn(-1f,1f)
    }
    @Synchronized fun onFrame(frame:MavFrame) {
        val data=TelemetryDecoder.decode(frame) ?: return
        if(data is Telemetry.Heartbeat) {
            if(frame.systemId !in 1..254 || frame.componentId!=1 || data.autopilot!=3 || data.vehicleType==6 || data.mavlinkVersion!=3)return
            if(targetSystem!=null && (targetSystem!=frame.systemId || targetComponent!=frame.componentId)){log("Bỏ HEARTBEAT từ autopilot khác");return}
            targetSystem=frame.systemId;targetComponent=frame.componentId;heartbeat=data;heartbeatAt=clock.nowMs()
            if(data.armed)stop("Nhận HEARTBEAT ARMED: đã thu hồi quyền RC")
            else if(data.systemStatus !in 3..4)stop("Autopilot chưa ổn định/boot/lỗi: cấm RC")
        }
        if(frame.systemId!=targetSystem || frame.componentId!=targetComponent)return
        rxAt=clock.nowMs()
        previousSeq?.let { previous ->
            val delta=(frame.sequence-previous+256)%256
            if(delta in 2..127)sequenceGaps+=(delta-1)
        }
        previousSeq=frame.sequence
        telemetry[data.javaClass.simpleName]=data
        when(data) {
            is Telemetry.RcChannels->{
                if(data.channelCount !in 0..18) {
                    actual=null;rcAt=null;stop("RC_CHANNELS chancount không hợp lệ");return
                }
                actual=data.channels.copyOf();rcAt=clock.nowMs()
                if(enabled && history.any{(at,expected)->clock.nowMs()-at<=500 && (0..3).all{i->expected[i]==65535 || abs(expected[i]-actual!!.getOrElse(i){65535})<=25}})feedbackAt=clock.nowMs()
            }
            is Telemetry.StatusText->log("STATUSTEXT ${data.severity} [${data.id}:${data.chunkSequence}]: ${data.text}")
            else->Unit
        }
    }
    @Synchronized fun tick() {
        if(!enabled)return
        healthReason()?.let{stop(it);return}
        if(held && (age(pulseAt)==null || age(pulseAt)!!>=DEADMAN_TIMEOUT_MS)){stop("Deadman quá hạn; không phát lại lệnh cũ");return}
        val start=feedbackAt ?: firstOverride
        if(start!=null && clock.nowMs()-start>=FEEDBACK_TIMEOUT_MS)stop("RC thực không khớp/phản hồi quá hạn: mất quyền điều khiển; kiểm tra ArduPilot")
    }
    @Synchronized fun planOverride():ControlPermit? {
        tick()
        if(!enabled || !held || healthReason()!=null)return null
        val now=clock.nowMs()
        if(lastSend!=null && now-lastSend!!<SEND_INTERVAL_MS)return null
        val values=IntArray(4){65535}
        if(mode==0)for(i in 0..3)values[i]=calibration[i].pwm(input[i]*(if(i==3)YAW_BENCH_SCALE else 1f),i==2)
        else values[mode-1]=calibration[mode-1].pwm(single*(if(mode==4)YAW_BENCH_SCALE else 1f),mode==3)
        return ControlPermit(epoch,targetSystem!!,targetComponent!!,values)
    }
    @Synchronized fun permitValid(permit:ControlPermit):Boolean {
        tick()
        return enabled && held && permit.epoch==epoch && healthReason()==null && age(pulseAt)!!<DEADMAN_TIMEOUT_MS
    }
    @Synchronized fun sent(permit:ControlPermit) {
        if(!permitValid(permit))return
        lastSend=clock.nowMs();sent=permit.channels.copyOf();overrides++
        if(firstOverride==null)firstOverride=lastSend
        history.addLast(lastSend!! to permit.channels.copyOf())
        while(history.isNotEmpty()&&clock.nowMs()-history.first().first>500)history.removeFirst()
    }
    @Synchronized fun snapshot():EngineSnapshot {
        tick()
        return EngineSnapshot(connected,armState(),age(heartbeatAt),age(rcAt),targetSystem,targetComponent,healthReason()==null,enabled,held,mode,reason,calibration.toList(),sent?.copyOf(),actual?.copyOf(),telemetry.toMap(),logs.toList(),lastSend,overrides,rxAt,sequenceGaps)
    }
}
