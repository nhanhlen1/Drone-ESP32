package dev.drone.controller.control

import dev.drone.controller.mavlink.MavFrame
import dev.drone.controller.mavlink.Telemetry
import dev.drone.controller.mavlink.TelemetryDecoder
import dev.drone.controller.mavlink.SequenceDisposition
import kotlin.math.abs
import kotlin.math.roundToInt

/** Deadlines always use a local monotonic clock, never remote boot time or wall time. */
fun interface MonotonicClock { fun nowMs():Long }
enum class ArmState { UNKNOWN, DISARMED, ARMED }
enum class SystemHealth { UNKNOWN, UNINITIALISED, BOOT, CALIBRATING, STANDBY, ACTIVE, CRITICAL, EMERGENCY, POWEROFF, FLIGHT_TERMINATION }
enum class OutboundKind { GCS_HEARTBEAT, TELEMETRY_REQUEST, RC_CH1_BENCH }

/** Rights are deliberately explicit. No state authorizes ARM, motor test or flight control. */
enum class ControlState {
    DISCONNECTED, CONNECTING, CONNECTED_UNVERIFIED, TELEMETRY_READY, RC_DIAGNOSTIC,
    RC_BENCH_READY, RC_BENCH_ACTIVE, RC_INPUT_VERIFIED, CONTROL_LOCKED, LINK_LOST,
    FAILSAFE, ARMED_MONITORING;

    val allowedOutbound:Set<OutboundKind> get() = when(this) {
        DISCONNECTED, CONNECTING -> emptySet()
        RC_BENCH_ACTIVE -> setOf(OutboundKind.GCS_HEARTBEAT,OutboundKind.TELEMETRY_REQUEST,OutboundKind.RC_CH1_BENCH)
        else -> setOf(OutboundKind.GCS_HEARTBEAT,OutboundKind.TELEMETRY_REQUEST)
    }
}

data class BenchConditions(
    val propsRemoved:Boolean=false,
    val escPowerIsolated:Boolean=false,
    val operatorAuthorised:Boolean=false,
    val criticalRcDiagnosticAuthorised:Boolean=false,
) { val complete:Boolean get()=propsRemoved && escPowerIsolated && operatorAuthorised }

data class ChannelCalibration(
    val minimum:Int=1100,val maximum:Int=1900,val trim:Int=1500,val verified:Boolean=false,
) {
    fun valid()=minimum in 900..2100 && maximum in 900..2100 && trim>minimum && trim<maximum
    fun pwm(value:Float,throttle:Boolean):Int {
        require(valid() && value.isFinite())
        val x=if(throttle)value.coerceIn(0f,1f) else value.coerceIn(-1f,1f)
        return (if(throttle)minimum+x*(maximum-minimum) else if(x>=0)trim+x*(maximum-trim) else trim+x*(trim-minimum))
            .roundToInt().coerceIn(minimum,maximum)
    }
}

/** Transport must recheck this capability immediately before each partial write. */
data class ControlPermit(
    val epoch:Long,val targetSystem:Int,val targetComponent:Int,val channels:IntArray,
    val issuedAtMs:Long=0,val serial:Long=0,
)

data class EngineSnapshot(
    val connected:Boolean,val arm:ArmState,val heartbeatAgeMs:Long?,val rcAgeMs:Long?,
    val targetSystem:Int?,val targetComponent:Int?,val ready:Boolean,val enabled:Boolean,
    val deadman:Boolean,val modeChannel:Int,val reason:String,val channels:List<ChannelCalibration>,
    val lastSent:IntArray?,val received:IntArray?,val telemetry:Map<String,Telemetry>,val logs:List<String>,
    val lastOverrideMs:Long?,val overrides:Long,val lastRxMs:Long?,val sequenceGaps:Long,
    val state:ControlState,val benchConditions:BenchConditions,val feedbackCorrelated:Boolean,
    val inputVerified:Boolean,val previewChannels:IntArray,val duplicateIdentity:Boolean,
    val generatedOverrides:Long,val telemetryAgeMs:Map<String,Long>,
    val parameters:Map<String,Telemetry.ParameterValue>,
    val systemHealth:SystemHealth,val criticalRcDiagnosticEligible:Boolean,val criticalRcDiagnosticActive:Boolean,
)

/**
 * Initial acceptance tier: telemetry and one small, finite CH1 diagnostic only.
 * No initial RC_CHANNELS is required: a receiverless FC can have no RC input yet.
 * A matching RC_CHANNELS is correlation, NOT ACK or proof of application.
 * It cannot extend a lease or unlock dual-stick control/ARM. Physical separation
 * of ESC power remains necessary because HEARTBEAT cannot prove the present
 * ARM state atomically between packets, and unsigned MAVLink is unauthenticated.
 */
class BenchEngine(private val clock:MonotonicClock) {
    companion object {
        const val HEARTBEAT_TIMEOUT_MS=1500L
        const val RC_TIMEOUT_MS=1200L
        const val DEADMAN_TIMEOUT_MS=200L
        const val SEND_INTERVAL_MS=50L
        const val BENCH_LEASE_MS=750L
        const val BENCH_START_TIMEOUT_MS=5000L
        const val FEEDBACK_TIMEOUT_MS=BENCH_LEASE_MS
        const val CH1_MAX_EXCURSION_US=20
        const val SYS_STATUS_TIMEOUT_MS=1500L
        private const val RC_RECEIVER_BIT=1L shl 16
        private const val PREARM_BIT=1L shl 28
        const val YAW_BENCH_SCALE=0f // Live yaw is locked at this acceptance tier.
    }
    private var connected=false
    private var connecting=false
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
    private var enabledAt:Long?=null
    private var firstGeneratedAt:Long?=null
    private var epoch=0L
    private var mode=1
    private var input=floatArrayOf(0f,0f,0f,0f)
    private var single=0f
    private var reason="Chưa kết nối; không có quyền phát RC"
    private var calibration=List(4){ChannelCalibration()}
    private var conditions=BenchConditions()
    private var duplicateIdentity=false
    private var fault=false
    private var wasLinkLost=false
    private var correlated=false
    private var criticalRcLease=false
    private val telemetry=linkedMapOf<String,Telemetry>()
    private val telemetryAt=linkedMapOf<String,Long>()
    private val parameters=linkedMapOf<String,Telemetry.ParameterValue>()
    private val logs=ArrayDeque<String>()
    private var lastSend:Long?=null
    private var sent:IntArray?=null
    private var overrides=0L
    private var generated=0L
    private var permitSerial=0L
    private var lastConsumedSerial=0L
    private var previousSeq:Int?=null
    private var sequenceGaps=0L
    private val history=ArrayDeque<Pair<Long,Int>>()

    @Synchronized fun log(message:String) {
        logs.addFirst("${clock.nowMs()}ms  ${message.take(220)}")
        while(logs.size>100)logs.removeLast()
    }
    private fun age(at:Long?):Long?=at?.let{(clock.nowMs()-it).coerceAtLeast(0)}
    private fun armState():ArmState {
        if(!connected || heartbeatAt==null || age(heartbeatAt)!!>=HEARTBEAT_TIMEOUT_MS)return ArmState.UNKNOWN
        // MAV_MODE_FLAG_SAFETY_ARMED and MAV_STATE describe different facts.
        // A disarmed FC can correctly report CRITICAL because radio is absent.
        return if(heartbeat!!.armed)ArmState.ARMED else ArmState.DISARMED
    }
    private fun systemHealth():SystemHealth {
        if(armState()==ArmState.UNKNOWN)return SystemHealth.UNKNOWN
        return when(heartbeat!!.systemStatus) {
            0->SystemHealth.UNINITIALISED;1->SystemHealth.BOOT;2->SystemHealth.CALIBRATING
            3->SystemHealth.STANDBY;4->SystemHealth.ACTIVE;5->SystemHealth.CRITICAL
            6->SystemHealth.EMERGENCY;7->SystemHealth.POWEROFF;8->SystemHealth.FLIGHT_TERMINATION
            else->SystemHealth.UNKNOWN
        }
    }
    /** Narrow diagnostic pattern, not proof that radio is the only CRITICAL cause.
     * Other failsafes/internal errors need not be represented in SYS_STATUS bits.
     * The separate explicit consent and physical ESC isolation remain mandatory.
     */
    private fun criticalRcEligible(allowLeaseFeedback:Boolean=false):Boolean {
        if(systemHealth()!=SystemHealth.CRITICAL || armState()!=ArmState.DISARMED)return false
        val status=telemetry["SysStatus"] as? Telemetry.SysStatus ?: return false
        if((age(telemetryAt["SysStatus"])?:Long.MAX_VALUE)>=SYS_STATUS_TIMEOUT_MS)return false
        val unhealthy=status.sensorsEnabled and status.sensorsHealthy.inv() and 0xffffffffL
        val unhealthyExtended=status.sensorsEnabledExtended and status.sensorsHealthyExtended.inv() and 0xffffffffL
        if((unhealthy and RC_RECEIVER_BIT)==0L || (unhealthy and (RC_RECEIVER_BIT or PREARM_BIT).inv())!=0L || unhealthyExtended!=0L)return false
        val initialRcAbsent=actual==null || actual!!.take(4).all{it==0}
        return initialRcAbsent || (allowLeaseFeedback && criticalRcLease)
    }
    private fun healthReason():String? {
        if(!connected)return "TCP chưa kết nối"
        if(duplicateIdentity)return "Nhiều định danh autopilot trên cùng luồng: đã khóa; kết nối lại sau khi xác minh nguồn"
        if(fault)return "Luồng MAVLink lỗi; cần HEARTBEAT mới và xác nhận kiểm thử mới"
        if(!foreground)return "App không ở foreground/có focus"
        when(armState()) {
            ArmState.ARMED -> return "ARMED: chỉ giám sát, cấm toàn bộ RC Bench"
            ArmState.UNKNOWN -> return "HEARTBEAT chưa rõ/đã quá hạn: cấm RC"
            else -> Unit
        }
        when(systemHealth()) {
            SystemHealth.STANDBY,SystemHealth.ACTIVE -> Unit
            SystemHealth.CRITICAL -> {
                if(enabled && !criticalRcLease)return "FC vừa chuyển CRITICAL: đã thu hồi quyền; cần chẩn đoán và chấp thuận mới"
                if(!conditions.criticalRcDiagnosticAuthorised)return "FC báo CRITICAL: cần chấp thuận riêng cho chẩn đoán RC giới hạn; vẫn cấm ARM và điều khiển bay"
                if(!criticalRcEligible(enabled))return "CRITICAL chưa đạt mẫu chẩn đoán RC: cần SYS_STATUS mới, chỉ lỗi RC/PreArm và chưa có RC input ban đầu; giữ khóa"
            }
            else -> return "Trạng thái hệ thống ${systemHealth()}: chưa được phép thử RC"
        }
        if(mode!=1)return "Chỉ phép thử CH1 nhỏ được phép; điều khiển hai joystick và CH2–CH4 chưa được nghiệm thu"
        if(!calibration[0].valid() || !calibration[0].verified)return "Đối chiếu và xác nhận MIN/MAX/TRIM của CH1 trước"
        if(!conditions.complete)return "Cần xác nhận riêng: đã tháo cánh, ngắt vật lý nguồn ESC và cho phép phép thử CH1"
        return null
    }
    private fun state():ControlState = when {
        connecting -> ControlState.CONNECTING
        !connected -> if(wasLinkLost)ControlState.LINK_LOST else ControlState.DISCONNECTED
        duplicateIdentity -> ControlState.CONTROL_LOCKED
        fault -> ControlState.FAILSAFE
        heartbeatAt!=null && age(heartbeatAt)!!>=HEARTBEAT_TIMEOUT_MS -> ControlState.LINK_LOST
        armState()==ArmState.ARMED -> ControlState.ARMED_MONITORING
        armState()==ArmState.UNKNOWN -> ControlState.CONNECTED_UNVERIFIED
        enabled && held -> ControlState.RC_BENCH_ACTIVE
        enabled || healthReason()==null -> ControlState.RC_BENCH_READY
        systemHealth()==SystemHealth.CRITICAL || systemHealth()==SystemHealth.EMERGENCY -> ControlState.FAILSAFE
        actual!=null -> ControlState.RC_DIAGNOSTIC
        else -> ControlState.TELEMETRY_READY
    }

    @Synchronized fun connecting() {
        stop("Đang kết nối TCP; không có quyền phát RC");connecting=true
    }
    @Synchronized fun connected() {
        connected=true;connecting=false;wasLinkLost=false;duplicateIdentity=false;fault=false
        targetSystem=null;targetComponent=null;heartbeat=null;heartbeatAt=null;rcAt=null;rxAt=null;actual=null
        previousSeq=null;sequenceGaps=0;telemetry.clear();telemetryAt.clear();parameters.clear();sent=null;lastSend=null;overrides=0;generated=0
        calibration=calibration.map{it.copy(verified=false)}
        stop("TCP kết nối; cần HEARTBEAT DISARMED và xác nhận mới trước phép thử CH1")
    }
    @Synchronized fun disconnected(why:String,linkLost:Boolean=false) {
        wasLinkLost=linkLost;connected=false;connecting=false;heartbeat=null;heartbeatAt=null;rcAt=null;actual=null
        targetSystem=null;targetComponent=null;telemetry.clear();telemetryAt.clear();parameters.clear();rxAt=null
        stop(why)
    }
    @Synchronized fun foreground(active:Boolean) {
        foreground=active
        if(!active)stop("Mất focus/foreground: đã dừng phát; xác minh timeout tại ArduPilot")
    }
    @Synchronized fun stop(why:String) {
        enabled=false;held=false;pulseAt=null;enabledAt=null;firstGeneratedAt=null;epoch++
        conditions=BenchConditions();criticalRcLease=false;input=floatArrayOf(0f,0f,0f,0f);single=0f;history.clear();correlated=false
        reason=why;log(why)
    }
    @Synchronized fun streamFault(why:String) {
        heartbeat=null;heartbeatAt=null;fault=true;stop(why)
    }
    @Synchronized fun configure(channel:Int,c:ChannelCalibration):Boolean {
        if(channel !in 1..4 || !c.valid()){log("Cấu hình RC không hợp lệ");return false}
        stop("Thay cấu hình RC; cần xác nhận kiểm thử mới")
        calibration=calibration.mapIndexed{i,old->if(i==channel-1)c else old};return true
    }
    @Synchronized fun selectMode(channel:Int) {
        if(channel !in 0..4)return
        stop("Đổi chế độ; xóa giá trị joystick và quyền kiểm thử cũ");mode=channel
    }
    @Synchronized fun confirmBenchConditions(propsRemoved:Boolean,escPowerIsolated:Boolean,operatorAuthorised:Boolean,criticalRcDiagnosticAuthorised:Boolean=false):Boolean {
        stop("Đã ghi nhận xác nhận điều kiện kiểm thử; chưa tự phát RC")
        conditions=BenchConditions(propsRemoved,escPowerIsolated,operatorAuthorised,criticalRcDiagnosticAuthorised)
        return conditions.complete
    }
    @Synchronized fun enableTest():Boolean {
        if(enabled){log("Phép thử đang có hiệu lực; không gia hạn quyền bằng thao tác bật lại");return false}
        val error=healthReason()
        if(error!=null){stop(error);return false}
        criticalRcLease=systemHealth()==SystemHealth.CRITICAL
        enabled=true;enabledAt=clock.nowMs();reason=if(criticalRcLease)
            "CRITICAL vẫn hiện hữu: chỉ cho phép chẩn đoán CH1 ±20 µs/750 ms đã chấp thuận riêng; chưa xác minh đầy đủ nguyên nhân"
            else "Đã cho phép một phép thử CH1 ±20 µs, tối đa 750 ms; giữ deadman trong 5 giây"
        log(reason);return true
    }
    @Synchronized fun deadman(active:Boolean) {
        if(!active){stop("Nhả deadman: đã dừng phát; không giả định gói trung tính bảo đảm an toàn");return}
        tick()
        if(!enabled)return
        healthReason()?.let{stop(it);return}
        held=true;pulseAt=clock.nowMs()
    }
    /** Offline Mode2 preview. It is intentionally disconnected from live bench transmission. */
    @Synchronized fun sticks(roll:Float,pitch:Float,throttle:Float,yaw:Float) {
        if(!listOf(roll,pitch,throttle,yaw).all{it.isFinite()}){stop("Joystick không hữu hạn: đã dừng phát");return}
        input=floatArrayOf(roll.coerceIn(-1f,1f),pitch.coerceIn(-1f,1f),throttle.coerceIn(0f,1f),yaw.coerceIn(-1f,1f))
    }
    @Synchronized fun singleChannel(channel:Int,value:Float) {
        if(channel!=1 || mode!=1 || !enabled)return
        if(!value.isFinite()){stop("Giá trị CH1 không hữu hạn: đã dừng phát");return}
        val next=value.coerceIn(-1f,1f)
        if(next!=single){single=next;epoch++}
    }

    @Synchronized fun onFrame(frame:MavFrame) {
        if(!connected || frame.incompatFlags!=0 || frame.sequenceDisposition in setOf(SequenceDisposition.DUPLICATE,SequenceDisposition.REORDERED))return
        val data=TelemetryDecoder.decode(frame)
        if(data is Telemetry.Heartbeat) {
            if(frame.systemId !in 1..254 || data.autopilot!=3 || data.vehicleType==6 || data.mavlinkVersion!=3)return
            // Any second ArduPilot heartbeat identity is ambiguous, even from a
            // different component. A GCS heartbeat never participates in discovery.
            if(targetSystem!=null && (targetSystem!=frame.systemId || targetComponent!=frame.componentId)) {
                duplicateIdentity=true;stop("Phát hiện nhiều định danh autopilot; đã khóa RC đến khi kết nối lại");return
            }
            if(frame.componentId!=1)return
            targetSystem=frame.systemId;targetComponent=frame.componentId;heartbeat=data;heartbeatAt=clock.nowMs();fault=false
            if(data.armed)stop("Nhận HEARTBEAT ARMED: chỉ giám sát, đã thu hồi quyền RC")
            else if(data.systemStatus !in 3..5)stop("Autopilot đang boot/lỗi/chưa rõ trạng thái: cấm RC")
        }
        if(frame.systemId!=targetSystem || frame.componentId!=targetComponent)return
        rxAt=clock.nowMs()
        previousSeq?.let{previous ->
            val delta=(frame.sequence-previous+256)%256
            if(delta in 2..127)sequenceGaps+=(delta-1)
        }
        previousSeq=frame.sequence
        // Count every parser-verified target frame, including messages the UI
        // does not decode. Otherwise ordinary extra telemetry looks like loss.
        if(data==null)return
        telemetry[data.javaClass.simpleName]=data
        telemetryAt[data.javaClass.simpleName]=clock.nowMs()
        when(data) {
            is Telemetry.RcChannels -> {
                if(data.channelCount !in 0..18){actual=null;rcAt=null;stop("RC_CHANNELS có số kênh không hợp lệ");return}
                actual=data.channels.copyOf();rcAt=clock.nowMs()
                // Matching a prior non-neutral observation is still not acceptance
                // proof. Narrow tolerance avoids calling an unchanged trim a match
                // for a small +/-20us diagnostic excursion.
                if(enabled && history.any{(at,expected)->clock.nowMs()-at<=500 && abs(expected-(actual?.getOrNull(0)?:0))<=3}) {
                    correlated=true;log("RC_CHANNELS tương quan với CH1 đã gửi; chưa chứng minh FC áp dụng")
                }
            }
            is Telemetry.StatusText -> log("STATUSTEXT ${data.severity} [${data.id}:${data.chunkSequence}]: ${data.text}")
            is Telemetry.ParameterValue -> {
                // The app requests a finite diagnostic subset. Bound unsolicited
                // distinct names too; no remote stream can grow this cache forever.
                if(data.name.isNotEmpty()) {
                    parameters.remove(data.name);parameters[data.name]=data
                    if(parameters.size>256)parameters.remove(parameters.keys.first())
                }
            }
            else -> Unit
        }
    }
    @Synchronized fun tick() {
        if(!enabled)return
        healthReason()?.let{stop(it);return}
        if(held && (age(pulseAt)==null || age(pulseAt)!!>=DEADMAN_TIMEOUT_MS)) {
            stop("Deadman quá hạn: đã thu hồi quyền, không phát lại lệnh cũ");return
        }
        if(firstGeneratedAt!=null && age(firstGeneratedAt)!!>=BENCH_LEASE_MS) {
            stop("Đã hết phép thử CH1 750 ms; cần bằng chứng FC và xác nhận riêng cho lần tiếp theo");return
        }
        if(firstGeneratedAt==null && age(enabledAt)!!>=BENCH_START_TIMEOUT_MS)
            stop("Quá 5 giây chưa bắt đầu phép thử; đã thu hồi xác nhận")
    }
    private fun desiredChannels():IntArray {
        val c=calibration[0]
        val pwm=(c.trim+single*CH1_MAX_EXCURSION_US).roundToInt().coerceIn(c.minimum,c.maximum)
        return intArrayOf(pwm,65535,65535,65535)
    }
    @Synchronized fun planOverride():ControlPermit? {
        tick()
        if(!enabled || !held || healthReason()!=null)return null
        val now=clock.nowMs()
        if(lastSend!=null && now-lastSend!!<SEND_INTERVAL_MS)return null
        if(firstGeneratedAt==null)firstGeneratedAt=now
        generated++;permitSerial++
        return ControlPermit(epoch,targetSystem!!,targetComponent!!,desiredChannels(),now,permitSerial)
    }
    @Synchronized fun permitValid(permit:ControlPermit):Boolean {
        tick()
        return enabled && held && permit.epoch==epoch && permit.serial>lastConsumedSerial &&
            permit.serial==permitSerial && permit.targetSystem==targetSystem && permit.targetComponent==targetComponent &&
            clock.nowMs()-permit.issuedAtMs in 0L until SEND_INTERVAL_MS && permit.channels.contentEquals(desiredChannels()) &&
            healthReason()==null && (age(pulseAt)?:Long.MAX_VALUE)<DEADMAN_TIMEOUT_MS
    }
    @Synchronized fun sent(permit:ControlPermit) {
        if(!permitValid(permit))return
        lastConsumedSerial=permit.serial;lastSend=clock.nowMs();sent=permit.channels.copyOf();overrides++
        history.addLast(lastSend!! to permit.channels[0])
        while(history.isNotEmpty() && clock.nowMs()-history.first().first>500)history.removeFirst()
    }
    @Synchronized fun snapshot():EngineSnapshot {
        tick()
        return EngineSnapshot(connected,armState(),age(heartbeatAt),age(rcAt),targetSystem,targetComponent,
            healthReason()==null,enabled,held,mode,reason,calibration.toList(),sent?.copyOf(),actual?.copyOf(),
            telemetry.toMap(),logs.toList(),lastSend,overrides,rxAt,sequenceGaps,state(),conditions,correlated,
            false,IntArray(4){i->calibration[i].pwm(input[i],i==2)},duplicateIdentity,generated,
            telemetryAt.mapValues{(_,at)->(clock.nowMs()-at).coerceAtLeast(0)},parameters.toMap(),
            systemHealth(),criticalRcEligible(enabled),enabled && criticalRcLease)
    }
}
