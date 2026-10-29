package dev.drone.controller.control

/** Identity selected from a parser-verified FC heartbeat, never TCP connect alone. */
data class AuthorityIdentity(val systemId:Int,val componentId:Int) {
    fun valid()=systemId in 1..254 && componentId in 1..255
}

enum class AuthorityMode { READ_ONLY, BENCH, FLIGHT_CANDIDATE, BENCH_4CH, OPERATOR_CONTROL }
enum class AuthorityStage {
    DISCONNECTED, WIFI_BOUND, TCP_CONNECTED, FC_HEARTBEAT_VERIFIED, TELEMETRY_READY,
    BENCH_READY, BENCH_TX_ACTIVE, RC_ECHO_VERIFIED, FLIGHT_CANDIDATE_READY,
    ARMED_CONTROL_CANDIDATE, LINK_LOST, FAILSAFE, LOCKED,
}

/** Independent records, not a flag inferred from a successful write or unit test.
 * The production default provider below supplies no such record. A simulation
 * may inject explicitly named simulation evidence; that never qualifies hardware.
 */
data class FlightQualification(
    val identity:AuthorityIdentity,val connectionEpoch:Long,val evidenceReference:String,
    val validUntilMs:Long,
    val softwareTested:Boolean=false,val sitlTested:Boolean=false,
    val hardwareRcInputVerified:Boolean=false,val overrideTimeoutVerified:Boolean=false,
    val linkLossFailsafeVerified:Boolean=false,val independentStopVerified:Boolean=false,
    val powertrainMappingVerified:Boolean=false,val operatorQualificationApproved:Boolean=false,
) {
    internal fun validFor(target:AuthorityIdentity,epoch:Long,now:Long)=
        identity==target && connectionEpoch==epoch && evidenceReference.isNotBlank() && now<validUntilMs &&
        softwareTested && sitlTested && hardwareRcInputVerified && overrideTimeoutVerified &&
        linkLossFailsafeVerified && independentStopVerified && powertrainMappingVerified && operatorQualificationApproved
}

fun interface FlightQualificationProvider {
    fun qualification(identity:AuthorityIdentity,connectionEpoch:Long):FlightQualification?
}

/** Read-only parameter snapshot for the current FC and connection. Wire channel
 * order is CH1 roll, CH2 pitch, CH3 throttle, CH4 yaw; other mappings need a
 * separately verified adapter, rather than silently controlling an auxiliary.
 */
data class AuthorityRcParameters(
    val connectionEpoch:Long,val identity:AuthorityIdentity,val receivedAtMs:Long,
    val gcsSystemLow:Int,val gcsSystemHigh:Int,val rcOptions:Int,
    val overrideTimeoutSeconds:Float,val channels:List<ChannelCalibration>,
    val mappingVerified:Boolean=false,
) {
    internal fun usable(gcsSystem:Int):Boolean {
        val accepts=if(gcsSystemHigh>gcsSystemLow)gcsSystem in gcsSystemLow..gcsSystemHigh else gcsSystem==gcsSystemLow
        return gcsSystemLow in 1..255 && gcsSystemHigh in 0..255 && accepts && rcOptions>=0 &&
            rcOptions and 2==0 && overrideTimeoutSeconds.isFinite() && overrideTimeoutSeconds>0f &&
            overrideTimeoutSeconds<=1f && channels.size==4 && channels.all{it.valid()&&it.verified} && mappingVerified
    }
}

data class AuthorityBenchConditions(
    val propsRemoved:Boolean=false,val escPowerIsolated:Boolean=false,val operatorConfirmed:Boolean=false,
) { internal fun complete()=propsRemoved&&escPowerIsolated&&operatorConfirmed }

/** A stop intent is not a motor command. The adapter may release only bench
 * channels it owned; flight loss never automatically releases, sets minimum
 * throttle, ARM/DISARM or invents a successful landing.
 */
enum class AuthorityStopEffect { STOP_RC_TRANSMISSION, RELEASE_BENCH_CHANNELS }
data class AuthorityDecision(
    val canTransmit:Boolean,val connectionEpoch:Long,val authorityEpoch:Long,
    val mode:AuthorityMode,val stage:AuthorityStage,val target:AuthorityIdentity?,
    val channels:List<Int>,val reason:String,val rcEchoVerified:Boolean,
    val stopEffect:AuthorityStopEffect,val transitions:List<String>,
)

/** Control boundary used by the live TCP scheduler.
 * It has no socket/encoder/ARM access.
 * A single latest-only scheduler must recheck both epochs and canTransmit before
 * each write. Its production qualification provider denies flight by default.
 * Bench and flight authority are intentionally separate: an unexpected ARMED
 * revokes bench, while an already enabled qualified flight session continues.
 */
class ControlAuthority(
    private val clock:MonotonicClock,
    private val qualifications:FlightQualificationProvider=FlightQualificationProvider{_,_->null},
    private val gcsSystem:Int=255,
) {
    companion object {
        const val HEARTBEAT_FRESH_MS=1500L
        const val RC_ECHO_FRESH_MS=1200L
        const val INPUT_FRESH_MS=200L
        const val DEADMAN_FRESH_MS=200L
        const val PARAMETERS_START_FRESH_MS=10_000L
        const val BENCH_LEASE_MS=750L
        const val BENCH_START_WINDOW_MS=5000L
        const val BENCH_CH1_EXCURSION_US=30
    }
    private var epoch=0L
    private var authorityEpoch=0L
    private var wifi=false
    private var connected=false
    private var foreground=false
    private var mode=AuthorityMode.READ_ONLY
    private var stage=AuthorityStage.DISCONNECTED
    private var target:AuthorityIdentity?=null
    private var heartbeatAt:Long?=null
    private var armed:Boolean?=null
    private var health=SystemHealth.UNKNOWN
    private var parameters:AuthorityRcParameters?=null
    private var observedRc:List<Int>?=null
    private var echoAt:Long?=null
    private var enabled=false
    private var groundTestConsent=false
    private var rcOnlyCritical=false
    private var rcOnlyCriticalAt:Long?=null
    private var everEnabled=false
    private var enabledAt:Long?=null
    private var firstWriteAt:Long?=null
    private var deadmanAt:Long?=null
    private var inputAt:Long?=null
    private var requested=neutral()
    private var inputRejected=false
    private var duplicateIdentity=false
    private var reason="Chưa kết nối; không có quyền điều khiển"
    private var stopEffect=AuthorityStopEffect.STOP_RC_TRANSMISSION
    private var correlated=false
    private val written=ArrayDeque<Pair<Long,List<Int>>>()
    private val history=ArrayDeque<String>()

    init { require(gcsSystem in 1..255) }
    private fun fresh(at:Long?,window:Long)=at!=null && clock.nowMs()-at in 0 until window
    private fun neutral():List<Int> {
        val c=parameters?.channels
        return if(c!=null&&c.size==4&&c.all{it.valid()})c.mapIndexed{i,value->if(i==2)value.minimum else value.trim}
        else listOf(1500,1500,1100,1500)
    }
    private fun transition(next:AuthorityStage,message:String) {
        stage=next;reason=message
        history.addLast("${clock.nowMs()} $next: ${message.take(240)}")
        while(history.size>64)history.removeFirst()
    }
    private fun revoke(next:AuthorityStage,message:String) {
        stopEffect=if(enabled&&mode==AuthorityMode.BENCH&&firstWriteAt!=null)AuthorityStopEffect.RELEASE_BENCH_CHANNELS else AuthorityStopEffect.STOP_RC_TRANSMISSION
        enabled=false;authorityEpoch++;enabledAt=null;firstWriteAt=null;deadmanAt=null;inputAt=null
        requested=neutral();correlated=false;written.clear();groundTestConsent=false;transition(next,message)
    }

    /** The caller must already have bound the selected Wi-Fi Network. */
    @Synchronized fun beginConnection(wifiBound:Boolean):Long {
        rcOnlyCritical=false;rcOnlyCriticalAt=null
        epoch++;wifi=wifiBound;connected=false;target=null;heartbeatAt=null;armed=null;health=SystemHealth.UNKNOWN
        parameters=null;observedRc=null;echoAt=null;duplicateIdentity=false;inputRejected=false;everEnabled=false
        revoke(if(wifiBound)AuthorityStage.WIFI_BOUND else AuthorityStage.LOCKED,
            if(wifiBound)"Đã chọn Wi-Fi; chờ TCP và HEARTBEAT" else "Chưa bind đúng Wi-Fi; không kết nối điều khiển")
        return epoch
    }
    @Synchronized fun tcpConnected(connectionEpoch:Long):Boolean {
        if(connectionEpoch!=epoch || !wifi || connected)return false
        connected=true;transition(AuthorityStage.TCP_CONNECTED,"TCP đã kết nối; chưa xác minh FC");return true
    }
    @Synchronized fun disconnect(message:String="Mất liên kết; không tự nối lại quyền điều khiển") {
        rcOnlyCritical=false;rcOnlyCriticalAt=null
        epoch++;connected=false;wifi=false;target=null;heartbeatAt=null;armed=null;parameters=null;observedRc=null;echoAt=null
        revoke(AuthorityStage.LINK_LOST,message)
    }
    @Synchronized fun foreground(active:Boolean) {
        foreground=active
        if(!active)revoke(AuthorityStage.LOCKED,"Ứng dụng mất focus/ra nền; đã thu hồi quyền phát")
    }
    @Synchronized fun selectMode(next:AuthorityMode) {
        mode=next;inputRejected=false;revoke(if(connected)AuthorityStage.LOCKED else AuthorityStage.DISCONNECTED,"Đổi chế độ; cần xác nhận mới, ga về min")
    }
    @Synchronized fun observeHeartbeat(connectionEpoch:Long,identity:AuthorityIdentity,isArmed:Boolean,systemHealth:SystemHealth,receivedAtMs:Long) {
        if(connectionEpoch!=epoch || !connected || !identity.valid() || receivedAtMs>clock.nowMs())return
        if(target!=null&&target!=identity) {
            duplicateIdentity=true;revoke(AuthorityStage.LOCKED,"Phát hiện nhiều định danh FC; đã khóa đến kết nối mới");return
        }
        if(heartbeatAt!=null&&receivedAtMs<=heartbeatAt!!)return
        val previouslyArmed=armed
        target=identity;heartbeatAt=receivedAtMs;armed=isArmed;health=systemHealth
        if(previouslyArmed!=null&&previouslyArmed!=isArmed){correlated=false;written.clear()}
        if(enabled && mode in setOf(AuthorityMode.BENCH,AuthorityMode.BENCH_4CH) && isArmed) {
            revoke(AuthorityStage.LOCKED,"FC chuyển ARMED trong RC Bench; đã thu hồi quyền bench");return
        }
        if(enabled && mode in setOf(AuthorityMode.FLIGHT_CANDIDATE,AuthorityMode.OPERATOR_CONTROL) && previouslyArmed==true && !isArmed) {
            revoke(AuthorityStage.LOCKED,"FC đã DISARMED; không tự khôi phục quyền flight");return
        }
        if(!enabled&&!everEnabled)transition(AuthorityStage.FC_HEARTBEAT_VERIFIED,"Đã xác minh HEARTBEAT; chưa cấp quyền điều khiển")
        tick()
    }
    @Synchronized fun observeRcParameters(snapshot:AuthorityRcParameters) {
        if(!connected || snapshot.connectionEpoch!=epoch || snapshot.identity!=target || snapshot.receivedAtMs>clock.nowMs())return
        val old=parameters
        if(old!=null&&snapshot.receivedAtMs<old.receivedAtMs)return
        if(enabled && old!=null && old.copy(receivedAtMs=snapshot.receivedAtMs)!=snapshot)
            revoke(AuthorityStage.LOCKED,"Thông số RC thay đổi; cần đối chiếu và xác nhận lại")
        parameters=snapshot.copy(channels=snapshot.channels.toList())
        if(!enabled)requested=neutral()
        if(!enabled&&!everEnabled&&snapshot.usable(gcsSystem))transition(AuthorityStage.TELEMETRY_READY,"Đã đọc cấu hình RC phù hợp; chưa có quyền phát")
    }
    @Synchronized fun observeRcEcho(connectionEpoch:Long,identity:AuthorityIdentity,channels:List<Int>,receivedAtMs:Long) {
        if(!connected || connectionEpoch!=epoch || identity!=target || channels.size!=4 || receivedAtMs>clock.nowMs())return
        if(echoAt!=null&&receivedAtMs<=echoAt!!)return
        observedRc=channels.toList();echoAt=receivedAtMs
        val latest=written.lastOrNull{(at,_)->at<=receivedAtMs}
        correlated=enabled&&latest!=null&&receivedAtMs-latest.first<=500&&latest.second==outputChannels()&&
            latest.second.indices.all{i->latest.second[i]==65535||latest.second[i]==channels[i]}
        tick()
    }
    private fun commonBlocker():String? {
        if(!connected||!wifi)return "Chưa kết nối đúng mạng Wi-Fi/TCP"
        if(!foreground)return "Ứng dụng không ở foreground/có focus"
        if(duplicateIdentity)return "Định danh FC mơ hồ"
        if(target==null||!fresh(heartbeatAt,HEARTBEAT_FRESH_MS)||armed==null)return "HEARTBEAT quá hạn hoặc chưa có FC hợp lệ"
        if(health !in setOf(SystemHealth.STANDBY,SystemHealth.ACTIVE) && !(groundTestConsent && mode==AuthorityMode.BENCH_4CH && armed==false && health==SystemHealth.CRITICAL && rcOnlyCritical && fresh(rcOnlyCriticalAt,1500L)))return "Sức khỏe FC $health; giữ khóa quyền này"
        val p=parameters ?:return "Chưa đọc thông số RC trong phiên này"
        if(p.connectionEpoch!=epoch||p.identity!=target||!p.usable(gcsSystem))return "Thông số SYSID/RC_OPTIONS/timeout/calibration/mapping chưa phù hợp"
        return null
    }
    private fun atStartupMinimum(values:List<Int>):Boolean {
        val c=parameters?.channels ?:return false
        return values.size==4 && values.indices.all{i->values[i]==if(i==2)c[i].minimum else c[i].trim}
    }
    private fun inputValid(values:List<Int>):Boolean {
        val c=parameters?.channels ?:return false
        if(values.size!=4 || c.size!=4 || values.indices.any{values[it] !in c[it].minimum..c[it].maximum})return false
        if(mode==AuthorityMode.BENCH) return kotlin.math.abs(values[0]-c[0].trim)<=BENCH_CH1_EXCURSION_US && (1..3).all{values[it]==if(it==2)c[it].minimum else c[it].trim}
        if(mode in setOf(AuthorityMode.FLIGHT_CANDIDATE,AuthorityMode.OPERATOR_CONTROL) && armed==false && values[2]!=c[2].minimum)return false
        return true
    }
    private fun qualificationValid():Boolean {
        val id=target ?:return false
        return runCatching{qualifications.qualification(id,epoch)?.validFor(id,epoch,clock.nowMs())==true}.getOrDefault(false)
    }
    private fun startBlocker():String? {
        commonBlocker()?.let{return it}
        if(!fresh(parameters?.receivedAtMs,PARAMETERS_START_FRESH_MS))return "Cần đọc lại thông số RC trước khi cấp quyền"
        if(armed!=false)return "Chỉ bắt đầu quyền khi FC DISARMED; không tự tiếp quản máy đã ARM"
        if(inputRejected||!atStartupMinimum(requested))return "Bắt đầu với roll/pitch/yaw trim và throttle min"
        return null
    }
    @Synchronized fun enableBench(conditions:AuthorityBenchConditions):Boolean {
        tick()
        if(enabled)return false
        val blocker=if(mode!=AuthorityMode.BENCH)"Chưa chọn RC Bench" else startBlocker()
        if(blocker!=null||!conditions.complete()) {
            revoke(AuthorityStage.LOCKED,blocker?:"Cần tháo cánh, ngắt vật lý nguồn ESC và xác nhận riêng");return false
        }
        authorityEpoch++;enabled=true;everEnabled=true;enabledAt=clock.nowMs();inputAt=clock.nowMs();requested=neutral();stopEffect=AuthorityStopEffect.STOP_RC_TRANSMISSION
        transition(AuthorityStage.BENCH_READY,"Bench CH1 ±30 µs có hạn; chưa phát khi chưa giữ deadman");return true
    }
    @Synchronized fun enableFlight(operatorConfirmed:Boolean):Boolean {
        tick()
        if(enabled)return false
        val blocker=when {
            mode!=AuthorityMode.FLIGHT_CANDIDATE->"Chưa chọn chế độ ứng viên điều khiển"
            !operatorConfirmed->"Cần chủ động cho phép phiên điều khiển mới"
            !qualificationValid()->"Chưa đủ hồ sơ kiểm định riêng; CHƯA ĐƯỢC KIỂM ĐỊNH BAY"
            else->startBlocker()
        }
        if(blocker!=null){revoke(AuthorityStage.LOCKED,blocker);return false}
        val c=parameters!!.channels
        if(!fresh(echoAt,RC_ECHO_FRESH_MS)||observedRc==null||observedRc!!.indices.any{observedRc!![it] !in c[it].minimum..c[it].maximum}) {
            revoke(AuthorityStage.LOCKED,"Chưa có đầu vào RC hợp lệ và mới để bắt đầu phiên qualified");return false
        }
        authorityEpoch++;enabled=true;everEnabled=true;enabledAt=clock.nowMs();inputAt=clock.nowMs();requested=neutral();stopEffect=AuthorityStopEffect.STOP_RC_TRANSMISSION
        transition(AuthorityStage.FLIGHT_CANDIDATE_READY,"Đã cấp quyền có điều kiện; không tự ARM, chờ thao tác người vận hành");return true
    }
    /** Explicit propeller-off commissioning session; this is not a hardware or
     * flight qualification record. Original strict enableFlight remains separate.
     * Four-channel bench additionally requires isolated ESC power and never ARMs.
     */
    @Synchronized fun enableOperatorSession(propsRemoved:Boolean,escPowerIsolated:Boolean,operatorConfirmed:Boolean):Boolean {
        tick()
        if(enabled)return false
        groundTestConsent=propsRemoved&&operatorConfirmed&&(mode!=AuthorityMode.BENCH_4CH||escPowerIsolated)
        val blocker=when {
            mode !in setOf(AuthorityMode.BENCH_4CH,AuthorityMode.OPERATOR_CONTROL)->"Chưa chọn kiểm thử bốn kênh hoặc điều khiển"
            !groundTestConsent->"Cần tháo cánh, xác nhận phiên; RC Bench phải cách ly nguồn ESC"
            else->startBlocker()
        }
        if(blocker!=null){revoke(AuthorityStage.LOCKED,blocker);return false}
        authorityEpoch++;enabled=true;everEnabled=true;enabledAt=clock.nowMs();inputAt=clock.nowMs();requested=neutral()
        stopEffect=AuthorityStopEffect.STOP_RC_TRANSMISSION
        transition(AuthorityStage.FLIGHT_CANDIDATE_READY,"Phiên kiểm thử tháo cánh; chưa kiểm định phần cứng/bay; chưa tự ARM")
        return true
    }
    /** Fresh SYS_STATUS may narrowly identify the receiverless bootstrap pattern.
     * It never permits ARM and cannot bypass other health/failsafe checks. */
    @Synchronized fun receiverlessCriticalPattern(eligible:Boolean,receivedAtMs:Long=clock.nowMs()){rcOnlyCritical=eligible;rcOnlyCriticalAt=receivedAtMs.takeIf{it<=clock.nowMs()}}
    @Synchronized fun input(channels:List<Int>,receivedAtMs:Long=clock.nowMs()) {
        if(receivedAtMs>clock.nowMs() || inputAt!=null&&receivedAtMs<inputAt!!)return
        if(!inputValid(channels)) {
            inputRejected=true
            if(enabled)revoke(AuthorityStage.LOCKED,"Đầu vào ngoài giới hạn/cấp quyền; đã dừng phát")
            return
        }
        if(channels!=requested)correlated=false
        inputRejected=false;requested=channels.toList();inputAt=receivedAtMs;tick()
    }
    @Synchronized fun deadman(held:Boolean) {
        if(!held){revoke(AuthorityStage.LOCKED,"Nhả deadman; dừng theo chính sách riêng của chế độ");return}
        tick();if(enabled)deadmanAt=clock.nowMs()
    }
    /** Called only after the scheduler wrote a complete RC frame, not when planned. */
    @Synchronized fun frameWritten(connectionEpoch:Long,permitAuthorityEpoch:Long,channels:List<Int>,writtenAtMs:Long=clock.nowMs()) {
        val d=decision()
        if(!d.canTransmit || connectionEpoch!=epoch || permitAuthorityEpoch!=authorityEpoch || channels!=d.channels || writtenAtMs>clock.nowMs())return
        if(firstWriteAt==null)firstWriteAt=writtenAtMs
        written.addLast(writtenAtMs to channels.toList());while(written.size>20)written.removeFirst()
    }
    @Synchronized fun stop(message:String="Người vận hành dừng phát điều khiển")=revoke(AuthorityStage.LOCKED,message)
    @Synchronized fun fault(message:String)=revoke(AuthorityStage.FAILSAFE,message)
    private fun outputChannels()=if(mode==AuthorityMode.BENCH)listOf(requested[0],65535,65535,65535) else requested.toList()
    @Synchronized fun tick() {
        if(!enabled)return
        commonBlocker()?.let{revoke(if(!fresh(heartbeatAt,HEARTBEAT_FRESH_MS))AuthorityStage.LINK_LOST else AuthorityStage.FAILSAFE,it);return}
        if(deadmanAt!=null&&!fresh(deadmanAt,DEADMAN_FRESH_MS)){revoke(AuthorityStage.LOCKED,"Deadman quá hạn; không phát gói cũ");return}
        if((deadmanAt!=null||firstWriteAt!=null)&&!fresh(inputAt,INPUT_FRESH_MS)){revoke(AuthorityStage.LOCKED,"Đầu vào điều khiển quá hạn; không phát lại giá trị cũ");return}
        if(mode in setOf(AuthorityMode.BENCH,AuthorityMode.BENCH_4CH)) {
            if(armed!=false){revoke(AuthorityStage.LOCKED,"ARMED không được phát RC Bench");return}
            if(firstWriteAt!=null&&clock.nowMs()-firstWriteAt!!>=if(mode==AuthorityMode.BENCH)BENCH_LEASE_MS else 5000L){revoke(AuthorityStage.LOCKED,if(mode==AuthorityMode.BENCH)"Đã hết hạn CH1 Bench; cần xác nhận riêng mới"else "Đã hết phép thử bốn kênh 5 giây; cần xác nhận mới");return}
            if(firstWriteAt==null&&clock.nowMs()-(enabledAt?:clock.nowMs())>=BENCH_START_WINDOW_MS){revoke(AuthorityStage.LOCKED,"Quá hạn bắt đầu phép thử bench");return}
        }
        if(mode in setOf(AuthorityMode.FLIGHT_CANDIDATE,AuthorityMode.OPERATOR_CONTROL)) {
            if(mode==AuthorityMode.FLIGHT_CANDIDATE&&!qualificationValid()){revoke(AuthorityStage.LOCKED,"Hồ sơ kiểm định bị thu hồi/hết hạn; không tự cấp lại");return}
            if(!fresh(echoAt,RC_ECHO_FRESH_MS) && !(mode==AuthorityMode.OPERATOR_CONTROL && armed==false && clock.nowMs()-(firstWriteAt?:enabledAt?:clock.nowMs())<RC_ECHO_FRESH_MS)){revoke(AuthorityStage.FAILSAFE,"Phản hồi RC quá hạn; kết quả điều khiển chưa được xác minh");return}
        }
    }
    @Synchronized fun decision():AuthorityDecision {
        tick()
        val transmit=enabled&&fresh(deadmanAt,DEADMAN_FRESH_MS)&&fresh(inputAt,INPUT_FRESH_MS)&&commonBlocker()==null&&inputValid(requested)
        if(transmit) {
            val activeStage=when {
            mode in setOf(AuthorityMode.BENCH,AuthorityMode.BENCH_4CH)&&correlated->AuthorityStage.RC_ECHO_VERIFIED
            mode in setOf(AuthorityMode.BENCH,AuthorityMode.BENCH_4CH)->AuthorityStage.BENCH_TX_ACTIVE
            armed==true->AuthorityStage.ARMED_CONTROL_CANDIDATE
            else->AuthorityStage.FLIGHT_CANDIDATE_READY
            }
            if(stage!=activeStage)transition(activeStage,reason)
        }
        val output=outputChannels()
        return AuthorityDecision(transmit,epoch,authorityEpoch,mode,stage,target,output,reason,correlated,stopEffect,history.toList())
    }
}
