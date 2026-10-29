package dev.drone.controller.transport

import dev.drone.controller.command.*
import dev.drone.controller.control.*
import dev.drone.controller.mavlink.*

/** Profiles are mutually exclusive; the socket actor remains the sole scheduler. */
enum class ControlProfile { READ_ONLY, BENCH_4CH, CONTROL }
data class ModeCommandSnapshot(val requestedMode:Int?=null,val status:String="Chưa yêu cầu chế độ",val ackResult:Int?=null,val heartbeatConfirmed:Boolean=false,val socketWritten:Boolean=false)
data class LiveControlSnapshot(
    val profile:ControlProfile=ControlProfile.READ_ONLY,val authority:AuthorityDecision=ControlAuthority(MonotonicClock{0}).decision(),val armCommand:ArmCommandSnapshot=ArmCommandService({0}).snapshot(),
    val modeCommand:ModeCommandSnapshot=ModeCommandSnapshot(),val lastRequested:List<Int> = listOf(1500,1500,1100,1500),val lastSent:List<Int>?=null,
    val lastEcho:List<Int>?=null,val rcWriteCount:Long=0,val armBlocker:String?=null,
)
internal data class LivePermit(val decision:AuthorityDecision,val issuedAt:Long)
internal data class OperatorCommand(val id:Long,val generation:Long,val createdAt:Long,val arm:ArmCommandIntent?=null,val mode:Int?=null)

/** Only called under BenchSession's common engine monitor. No IO and no timer.
 * Propeller-off consent is an operator assertion, never a hardware certificate.
 */
internal class LiveControlRuntime(private val clock:MonotonicClock) {
    val authority=ControlAuthority(clock)
    var profile=ControlProfile.READ_ONLY; private set
    private var epoch=0L
    private var generation=0L
    private var active=false
    private var focused=false
    private var streamFaulted=false
    private var sessionConsent=false
    private var command:OperatorCommand?=null
    private var commandId=0L
    private var mode=ModeCommandSnapshot()
    private var modeRequestedAt=0L
    private var modeAckAt:Long?=null
    private var modePending=false
    private val parameterAt=linkedMapOf<String,Long>()
    private val controlParameters=setOf("MAV_GCS_SYSID","MAV_GCS_SYSID_HI","SYSID_MYGCS","MAV_OPTIONS","ARMING_RUDDER","FLTMODE_CH","RC_OPTIONS","RC_OVERRIDE_TIME","RCMAP_ROLL","RCMAP_PITCH","RCMAP_THROTTLE","RCMAP_YAW") + (1..4).flatMap{n->listOf("RC${n}_MIN","RC${n}_MAX","RC${n}_TRIM","RC${n}_REVERSED","RC${n}_OPTION")}
    private var lastSent:List<Int>?=null
    private var lastEcho:List<Int>?=null
    private var writes=0L
    private var nextRcAt=0L
    private var telemetry:EngineSnapshot?=null
    private var telemetryAt=0L
    private val arm=ArmCommandService({clock.nowMs()},HardwareTierApproval{sessionConsent},ArmIntentSink{intent->
        if(command!=null || !active) false else { command=OperatorCommand(intent.id,generation,clock.nowMs(),arm=intent);true }
    })
    fun connecting(connectionGeneration:Long) {
        lost();streamFaulted=false;generation=connectionGeneration;epoch=authority.beginConnection(true)
        parameterAt.clear();lastSent=null;lastEcho=null;writes=0;nextRcAt=0
    }
    fun connected(){active=true;authority.tcpConnected(epoch)}
    fun lost() {
        active=false;sessionConsent=false;command=null;arm.linkLost();authority.disconnect();modePending=false
        if(mode.requestedMode!=null&&!mode.heartbeatConfirmed)mode=mode.copy(status="Mất link; không tự phát lại lệnh chế độ")
        telemetry=null
    }
    fun foreground(value:Boolean){focused=value;authority.foreground(value);if(!value)stop("Mất focus/foreground; cần xác nhận phiên mới")}
    fun stop(reason:String){modePending=false;authority.stop(reason);sessionConsent=false;command=null;arm.cancel(reason);if(mode.requestedMode!=null&&!mode.heartbeatConfirmed)mode=mode.copy(status="$reason; kết quả chưa xác minh")}
    fun fault(reason:String){streamFaulted=true;modePending=false;authority.fault(reason);sessionConsent=false;command=null;arm.linkLost()}
    fun select(next:ControlProfile) {
        stop("Đổi chế độ điều khiển; xóa ga và quyền phiên cũ");profile=next
        authority.selectMode(when(next){ControlProfile.READ_ONLY->AuthorityMode.READ_ONLY;ControlProfile.BENCH_4CH->AuthorityMode.BENCH_4CH;ControlProfile.CONTROL->AuthorityMode.OPERATOR_CONTROL})
    }
    fun input(roll:Float,pitch:Float,throttle:Float,yaw:Float) {
        if(profile==ControlProfile.READ_ONLY)return
        val values=listOf(roll,pitch,throttle,yaw)
        val c=calibrations()?:return
        if(values.any{!it.isFinite()}){fault("Đầu vào joystick không hữu hạn");return}
        authority.input(values.mapIndexed{i,v->c[i].pwm(v,i==2)})
    }
    fun pulse(){if(profile!=ControlProfile.READ_ONLY)authority.deadman(true)}
    fun enable(propsRemoved:Boolean,escIsolated:Boolean,operatorConfirmed:Boolean):Boolean {
        if(profile==ControlProfile.READ_ONLY)return false
        if(streamFaulted || !active || !focused){authority.fault("Cần kết nối, focus và HEARTBEAT mới sau lỗi luồng");return false}
        if(!quadSupported()){authority.fault("Đường điều khiển chỉ hỗ trợ ArduCopter quad; phương tiện này chỉ được giám sát");return false}
        if(telemetry?.parameters?.get("ARMING_RUDDER")?.value!=0f){authority.stop("Cần đọc ARMING_RUDDER = 0 để chặn ARM/DISARM bằng joystick; ứng dụng không tự sửa tham số FC");return false}
        val modeChannel=telemetry?.parameters?.get("FLTMODE_CH")?.value
        if(modeChannel !in (listOf(0f)+(5..16).map{it.toFloat()})){authority.stop("Cần đọc FLTMODE_CH = 0 hoặc 5–16; CH1–CH4 không được dùng làm cần chọn chế độ");return false}
        if((1..4).any{telemetry?.parameters?.get("RC${it}_OPTION")?.value!=0f}){authority.stop("Cần đọc RC1_OPTION đến RC4_OPTION = 0 để tránh kích hoạt chức năng phụ bằng joystick; ứng dụng không tự sửa FC");return false}
        refreshSafety()
        val ok=authority.enableOperatorSession(propsRemoved,escIsolated,operatorConfirmed)
        sessionConsent=ok && profile==ControlProfile.CONTROL
        return ok
    }
    fun refreshReadback(){parameterAt.clear();authority.invalidateParameters("Đang đọc lại toàn bộ điều kiện RC; chưa cấp quyền") }
    private fun fresh(key:String,maximum:Long=1500L)=telemetry?.telemetryAgeMs?.get(key)?.let{it+clock.nowMs()-telemetryAt in 0 until maximum}==true
    private fun refreshSafety() {
        val s=telemetry?.telemetry?.get("SysStatus") as? Telemetry.SysStatus
        val unhealthy=s?.let{it.sensorsEnabled and it.sensorsHealthy.inv() and 0xffffffffL}
        val rcBit=1L shl 16;val preArm=1L shl 28
        authority.receiverlessCriticalPattern(profile==ControlProfile.BENCH_4CH && fresh("SysStatus") && unhealthy!=null && unhealthy and rcBit!=0L && unhealthy and (rcBit or preArm).inv()==0L && s.sensorsEnabledExtended and s.sensorsHealthyExtended.inv()==0L)
    }
    fun observe(frame:MavFrame,snapshot:EngineSnapshot) {
        val previousParameters=telemetry?.parameters
        telemetry=snapshot;telemetryAt=clock.nowMs()
        if(frame.incompatFlags!=0 || frame.sequenceDisposition in setOf(SequenceDisposition.DUPLICATE,SequenceDisposition.REORDERED))return
        val id=if(snapshot.targetSystem!=null&&snapshot.targetComponent!=null)AuthorityIdentity(snapshot.targetSystem,snapshot.targetComponent)else return
        val data=TelemetryDecoder.decode(frame)
        if(data is Telemetry.Heartbeat && frame.systemId in 1..254 && data.autopilot==3 && data.vehicleType!=6 && data.mavlinkVersion==3) {
            // Preserve detection of a second autopilot identity, rather than filter it away.
            authority.observeHeartbeat(epoch,AuthorityIdentity(frame.systemId,frame.componentId),data.armed,snapshot.systemHealth,clock.nowMs())
            if(!snapshot.duplicateIdentity && frame.systemId==id.systemId && frame.componentId==id.componentId)streamFaulted=false
            if(data.vehicleType==2)arm.onHeartbeat(frame.systemId,frame.componentId,data.armed,clock.nowMs())
        }
        if(frame.systemId!=id.systemId || frame.componentId!=id.componentId)return
        when(data) {
            is Telemetry.ParameterValue -> {
                val previous=previousParameters?.get(data.name)?.value
                if(data.name in controlParameters && previous!=null && previous!=data.value) {
                    stop("FC thay đổi ${data.name}; thu hồi quyền, cần đối chiếu lại")
                    authority.invalidateParameters("Thông số điều khiển đã thay đổi; chưa cấp lại quyền")
                }
                parameterAt.keys.retainAll(snapshot.parameters.keys)
                parameterAt.remove(data.name);parameterAt[data.name]=clock.nowMs()
                while(parameterAt.size>256)parameterAt.remove(parameterAt.keys.first())
                readParameters(id,snapshot.parameters)
            }
            is Telemetry.RcChannels -> if(data.channelCount in 4..18){lastEcho=data.channels.take(4);authority.observeRcEcho(epoch,id,lastEcho!!,clock.nowMs())}
            is Telemetry.CommandAck -> {
                if(arm.snapshot().socketWritten)arm.onAck(frame.systemId,frame.componentId,data)
                if(data.command==176 && mode.socketWritten && data.targetSystem in listOf(0,255) && data.targetComponent in listOf(0,190) && modePending && clock.nowMs()-modeRequestedAt<3000) {
                    mode=mode.copy(ackResult=data.result,status=if(data.result==0)"FC ACK chấp nhận; chờ HEARTBEAT chế độ mới"else "FC trả kết quả ${data.result}; không tự gửi lại")
                    if(data.result==0)modeAckAt=clock.nowMs() else if(data.result!=5){modeAckAt=null;modePending=false}
                }
            }
            is Telemetry.Heartbeat -> if(data.vehicleType==2 && data.autopilot==3 && data.mavlinkVersion==3 && modePending && modeAckAt!=null && clock.nowMs()>modeAckAt!! && data.customMode==mode.requestedMode?.toLong()) {mode=mode.copy(status="ACK và HEARTBEAT mới xác nhận chế độ",heartbeatConfirmed=true);modePending=false}
            else -> Unit
        }
        refreshSafety()
    }
    private fun calibrations():List<ChannelCalibration>? {
        val p=telemetry?.parameters?:return null
        return (1..4).map { n->
            fun int(name:String)=p[name]?.value?.takeIf{it.isFinite()&&it%1f==0f}?.toInt()
            val min=int("RC${n}_MIN")?:return null;val max=int("RC${n}_MAX")?:return null;val trim=int("RC${n}_TRIM")?:return null
            val reversed=int("RC${n}_REVERSED")?:return null
            if(reversed!=0)return null
            ChannelCalibration(min,max,trim,true).takeIf{it.valid()}?:return null
        }
    }
    private fun readParameters(id:AuthorityIdentity,p:Map<String,Telemetry.ParameterValue>) {
        val c=calibrations()?:return
        val required=mutableListOf<String>()
        fun value(vararg names:String):Float? {
            val name=names.firstOrNull{p.containsKey(it)}?:return null
            required+=name;return p[name]?.value
        }
        fun integer(vararg names:String):Int?=value(*names)?.takeIf{it.isFinite()&&it%1f==0f&&it.toDouble()>=Int.MIN_VALUE.toDouble()&&it.toDouble()<=Int.MAX_VALUE.toDouble()}?.toInt()
        val low=integer("MAV_GCS_SYSID","SYSID_MYGCS")?:return
        val high=if(p.containsKey("MAV_GCS_SYSID_HI"))integer("MAV_GCS_SYSID_HI")?:return else 0
        val rudder=integer("ARMING_RUDDER")?:return
        val modeChannel=integer("FLTMODE_CH")?:return
        val auxiliary=(1..4).map{integer("RC${it}_OPTION")?:return}
        val options=integer("RC_OPTIONS")?:return;val timeout=value("RC_OVERRIDE_TIME")?:return
        val maps=listOf("RCMAP_ROLL","RCMAP_PITCH","RCMAP_THROTTLE","RCMAP_YAW").map{integer(it)?:return}
        required+=(1..4).flatMap{n->listOf("RC${n}_MIN","RC${n}_MAX","RC${n}_TRIM","RC${n}_REVERSED","RC${n}_OPTION")}
        val oldest=required.map{parameterAt[it]?:return}.minOrNull()?:return
        authority.observeRcParameters(AuthorityRcParameters(epoch,id,oldest,low,high,options,timeout,c,maps==listOf(1,2,3,4),rudder,modeChannel,auxiliary))
    }
    fun plan():LivePermit? {
        refreshSafety();val d=authority.decision()
        if(profile==ControlProfile.READ_ONLY || !d.canTransmit || clock.nowMs()<nextRcAt)return null
        return LivePermit(d,clock.nowMs())
    }
    fun valid(permit:LivePermit):Boolean {
        refreshSafety();val d=authority.decision()
        return d.canTransmit && permit.decision.connectionEpoch==d.connectionEpoch && permit.decision.authorityEpoch==d.authorityEpoch && permit.decision.channels==d.channels && clock.nowMs()-permit.issuedAt in 0 until 50
    }
    fun written(permit:LivePermit) {
        // Bytes already reached the socket: retain that observation even when a
        // monotonic deadline crossed during the final nonblocking write.
        lastSent=permit.decision.channels.toList();writes++;nextRcAt=clock.nowMs()+50
        authority.frameWritten(permit.decision.connectionEpoch,permit.decision.authorityEpoch,permit.decision.channels)
    }
    private fun preArmBlocker():String? {
        val s=telemetry?:return "Chưa có telemetry FC"
        val d=authority.decision()
        if(!quadSupported())return "Đường lệnh chỉ hỗ trợ ArduCopter quad"
        if(profile!=ControlProfile.CONTROL || !sessionConsent || !d.canTransmit)return "Chưa có phiên điều khiển đang phát hợp lệ"
        if(!d.rcEchoVerified)return "Chưa có RC_CHANNELS mới khớp bốn kênh đã gửi"
        val c=calibrations()?:return "Chưa đọc cấu hình RC"
        if(d.channels[2]!=c[2].minimum)return "Ga chưa ở mức tối thiểu"
        if(listOf(0,1,3).any{d.channels[it]!=c[it].trim})return "Roll, pitch và yaw phải ở vị trí giữa trước khi ARM"
        if(s.systemHealth !in setOf(SystemHealth.STANDBY,SystemHealth.ACTIVE))return "FC chưa khỏe; không ARM"
        val health=s.telemetry["SysStatus"] as? Telemetry.SysStatus?:return "Chưa có SYS_STATUS"
        if(!fresh("SysStatus") || !health.preArmCheckPresent || !health.preArmCheckEnabled || !health.preArmCheckHealthy)return "PreArm chưa được FC xác nhận đạt"
        if(health.unhealthyEnabledSensors!=0L || health.unhealthyEnabledSensorsExtended!=0L)return "FC còn cảm biến/failsafe không khỏe"
        val ekf=s.telemetry["EkfStatus"] as? Telemetry.EkfStatus?:return "Chưa có trạng thái EKF"
        if(!fresh("EkfStatus") || ekf.flags and 1==0 || ekf.flags and 1024!=0)return "EKF chưa xác minh attitude hợp lệ"
        if(!onGround())return "Chưa xác nhận FC đang trên mặt đất"
        return null
    }
    private fun quadSupported()=(telemetry?.telemetry?.get("Heartbeat") as? Telemetry.Heartbeat)?.let{it.vehicleType==2&&it.autopilot==3}==true
    private fun heartbeatAge():Long?=telemetry?.heartbeatAgeMs?.let{it+clock.nowMs()-telemetryAt}
    private fun onGround()=fresh("ExtendedSysState") && (telemetry?.telemetry?.get("ExtendedSysState") as? Telemetry.ExtendedSysState)?.onGround==true
    fun requestArm(desired:Boolean,confirmed:Boolean):ArmRequestResult {
        val s=telemetry
        if(streamFaulted || !active || !focused || s?.duplicateIdentity==true || !quadSupported())return ArmRequestResult.Rejected("Chưa có định danh ArduCopter quad duy nhất và kết nối hợp lệ")
        if(desired) preArmBlocker()?.let{return ArmRequestResult.Rejected(it)}
        // Ground-only commissioning release: never force-disarm a flying vehicle.
        if(!onGround())return ArmRequestResult.Rejected("FC chưa báo đang trên mặt đất; không ARM/DISARM")
        if(!desired && confirmed)sessionConsent=true
        val result=arm.request(desired,confirmed,ArmTelemetryEvidence(s?.targetSystem?:0,s?.targetComponent?:0,clock.nowMs()-(heartbeatAge()?:Long.MAX_VALUE),when(s?.arm){ArmState.ARMED->true;ArmState.DISARMED->false;else->null},authority.decision().rcEchoVerified,preArmBlocker()==null,onGround()))
        return result
    }
    fun requestMode(custom:Int,confirmed:Boolean):Boolean {
        if(streamFaulted || !focused || modePending || telemetry?.duplicateIdentity==true || !quadSupported())return false
        if(!confirmed || custom !in setOf(0,1,2,3,4,5,6,7,9,11,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27) || !active || command!=null || heartbeatAge()?.let{it<1500}!=true)return false
        val s=telemetry?:return false
        if(s.targetSystem==null||s.targetComponent==null)return false
        command=OperatorCommand(++commandId,generation,clock.nowMs(),mode=custom);modeRequestedAt=clock.nowMs();modeAckAt=null
        modePending=true;mode=ModeCommandSnapshot(custom,"Ý định chế độ được tiếp nhận; chưa ghi socket")
        return true
    }
    fun queued():OperatorCommand?=command?.takeIf{validCommand(it)}
    fun validCommand(c:OperatorCommand):Boolean {
        if(telemetry?.duplicateIdentity==true || c.mode!=null&&!modePending)return false
        if(streamFaulted || !active || !focused || c.generation!=generation || clock.nowMs()-c.createdAt !in 0 until 1000)return false
        if(c.arm!=null) {
            if(arm.snapshot().intent?.id!=c.arm.id || arm.snapshot().status !in setOf(ArmCommandStatus.INTENT_ACCEPTED,ArmCommandStatus.INTENT_CREATED))return false
            if(c.arm.desiredArmed && preArmBlocker()!=null)return false
            if(!onGround())return false
        }
        return heartbeatAge()?.let{it<1500}==true
    }
    fun commandWritten(c:OperatorCommand) {
        if(command==c)command=null
        if(c.arm!=null)arm.markSocketWritten(c.arm.id) else mode=mode.copy(status="Đã ghi đầy đủ socket; chờ COMMAND_ACK và HEARTBEAT",socketWritten=true)
    }
    fun snapshot():LiveControlSnapshot {
        arm.tick()
        if(modePending && clock.nowMs()-modeRequestedAt>=3000){mode=mode.copy(status="Quá hạn xác nhận chế độ; không tự thử lại");modePending=false;modeAckAt=null}
        if(command?.let{!validCommand(it)}==true)command=null
        val d=authority.decision()
        return LiveControlSnapshot(profile,d,arm.snapshot(),mode,d.channels,lastSent,lastEcho,writes,preArmBlocker())
    }
}
