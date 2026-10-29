package dev.drone.controller.command

import dev.drone.controller.mavlink.Telemetry

/** Semantic intent only. No MAVLink byte encoder, force-arm/disarm option or TCP access. */
data class ArmCommandIntent(val id:Long,val targetSystem:Int,val targetComponent:Int,val desiredArmed:Boolean) {
    val command:Int get()=400 // MAV_CMD_COMPONENT_ARM_DISARM
}

/** Test/architecture boundary. Returning true means an intent was handed off,
 * never that bytes reached a socket, FC accepted it, or the arm state changed. */
fun interface ArmIntentSink { fun offer(intent:ArmCommandIntent):Boolean }
fun interface HardwareTierApproval { fun independentlyApproved():Boolean }

data class ArmTelemetryEvidence(
    val systemId:Int,val componentId:Int,val heartbeatAtMs:Long,val armed:Boolean?,
    val rcInputVerified:Boolean,val preArmClear:Boolean,val onGroundVerified:Boolean,
)

enum class ArmCommandStatus {
    IDLE, INTENT_CREATED, INTENT_ACCEPTED, ACK_ACCEPTED_WAITING_HEARTBEAT,
    IN_PROGRESS, CONFIRMED, DENIED, UNSUPPORTED, FAILED, CANCELLED, TIMED_OUT, ABORTED,
}

data class ArmCommandSnapshot(
    val status:ArmCommandStatus,val intent:ArmCommandIntent?,val reason:String,
    val intentAccepted:Boolean=false,val ackResult:Int?=null,val observedArmed:Boolean?=null,
    val heartbeatConfirmed:Boolean=false,val socketWritten:Boolean=false,
)
sealed class ArmRequestResult {
    data class Accepted(val intentId:Long):ArmRequestResult()
    data class Rejected(val reason:String):ArmRequestResult()
}

/** Default approval and sink deny requests; the production session injects
 * explicit commissioning consent and its bounded command hand-off.
 * Tests can inject a simulated approval and semantic sink without creating bytes.
 * All methods use a local monotonic clock. No request is retried or auto-created.
 */
class ArmCommandService(
    private val nowMs:()->Long,
    private val approval:HardwareTierApproval=HardwareTierApproval{false},
    private val sink:ArmIntentSink=ArmIntentSink{false},
    private val gcsSystem:Int=255,
    private val gcsComponent:Int=190,
) {
    companion object {
        const val HEARTBEAT_FRESH_MS=1500L
        const val COMMAND_DEADLINE_MS=3000L
    }
    private var nextId=0L
    private var requestedAt:Long?=null
    private var acceptedAckAt:Long?=null
    private var state=ArmCommandSnapshot(ArmCommandStatus.IDLE,null,"Chức năng ARM/DISARM trực tiếp đang khóa")
    init { require(gcsSystem in 1..255 && gcsComponent in 1..255) }

    private fun pending()=state.status in setOf(ArmCommandStatus.INTENT_CREATED,ArmCommandStatus.INTENT_ACCEPTED,
        ArmCommandStatus.ACK_ACCEPTED_WAITING_HEARTBEAT,ArmCommandStatus.IN_PROGRESS)
    private fun fresh(at:Long)=nowMs()-at in 0 until HEARTBEAT_FRESH_MS

    @Synchronized fun request(desiredArmed:Boolean,explicitConfirmation:Boolean,evidence:ArmTelemetryEvidence):ArmRequestResult {
        tick()
        fun reject(message:String)=ArmRequestResult.Rejected(message)
        if(pending())return reject("Một yêu cầu đang chờ; không gửi lệnh cạnh tranh")
        if(!approval.independentlyApproved())return reject("Chưa nghiệm thu riêng nguồn động lực và failsafe; ARM/DISARM đang khóa")
        if(!explicitConfirmation)return reject("Cần xác nhận chủ động riêng cho từng yêu cầu")
        if(evidence.systemId !in 1..254 || evidence.componentId !in 1..255)return reject("Đích flight controller không hợp lệ")
        if(!fresh(evidence.heartbeatAtMs) || evidence.armed==null)return reject("HEARTBEAT quá hạn hoặc trạng thái ARM chưa rõ")
        if(evidence.armed==desiredArmed)return reject("Trạng thái thực đã đúng; không phát lệnh thừa")
        if(!evidence.onGroundVerified)return reject("Chưa xác minh đang trên mặt đất; không cho lệnh ARM/DISARM trong kiến trúc bench")
        if(desiredArmed && !evidence.rcInputVerified)return reject("Chưa xác minh nguồn RC được áp dụng")
        if(desiredArmed && !evidence.preArmClear)return reject("Còn cảnh báo PreArm hoặc chưa xác minh đã hết")
        val intent=ArmCommandIntent(++nextId,evidence.systemId,evidence.componentId,desiredArmed)
        requestedAt=nowMs();acceptedAckAt=null
        state=ArmCommandSnapshot(ArmCommandStatus.INTENT_CREATED,intent,"Đã tạo ý định lệnh; chưa có bằng chứng ghi socket",observedArmed=evidence.armed)
        val accepted=runCatching{sink.offer(intent)}.getOrDefault(false)
        state=state.copy(status=if(accepted)ArmCommandStatus.INTENT_ACCEPTED else ArmCommandStatus.FAILED,
            intentAccepted=accepted,reason=if(accepted)"Ý định được tiếp nhận; chưa chứng minh socket/FC/trạng thái thực" else "Không có đường phát lệnh; chưa gửi ARM/DISARM")
        return if(accepted)ArmRequestResult.Accepted(intent.id) else reject(state.reason)
    }

    @Synchronized fun markSocketWritten(intentId:Long) {
        if(state.intent?.id==intentId && pending())state=state.copy(socketWritten=true,reason="Đã ghi đầy đủ socket; chờ ACK và HEARTBEAT")
    }

    /** Source FC must match the outstanding intent; ACK target extensions may be
     * zero (not supplied) or this GCS. A late ACK never resurrects an expired intent. */
    @Synchronized fun onAck(sourceSystem:Int,sourceComponent:Int,ack:Telemetry.CommandAck) {
        tick()
        val intent=state.intent ?:return
        if(!pending() || sourceSystem!=intent.targetSystem || sourceComponent!=intent.targetComponent || ack.command!=400)return
        if(ack.targetSystem !in listOf(0,gcsSystem) || ack.targetComponent !in listOf(0,gcsComponent))return
        when(ack.result) {
            0 -> {
                acceptedAckAt=nowMs()
                state=state.copy(status=ArmCommandStatus.ACK_ACCEPTED_WAITING_HEARTBEAT,ackResult=0,
                    reason="FC ACK chấp nhận; phải nhận HEARTBEAT mới xác nhận trạng thái thực")
            }
            1,2 -> state=state.copy(status=ArmCommandStatus.DENIED,ackResult=ack.result,reason="FC từ chối; không tự thử lại")
            3 -> state=state.copy(status=ArmCommandStatus.UNSUPPORTED,ackResult=3,reason="FC không hỗ trợ lệnh")
            5 -> state=state.copy(status=ArmCommandStatus.IN_PROGRESS,ackResult=5,reason="FC đang xử lý; thời hạn tuyệt đối không kéo dài")
            6 -> state=state.copy(status=ArmCommandStatus.CANCELLED,ackResult=6,reason="FC hủy lệnh")
            else -> state=state.copy(status=ArmCommandStatus.FAILED,ackResult=ack.result,reason="FC báo lệnh thất bại hoặc kết quả không biết")
        }
    }

    /** A state observed without accepted ACK is retained but never attributed to
     * our intent. Confirmation requires a fresh later FC HEARTBEAT after the ACK. */
    @Synchronized fun onHeartbeat(sourceSystem:Int,sourceComponent:Int,armed:Boolean?,receivedAtMs:Long) {
        tick()
        val intent=state.intent ?:return
        if(!pending() || sourceSystem!=intent.targetSystem || sourceComponent!=intent.targetComponent)return
        if(!fresh(receivedAtMs) || receivedAtMs <= (requestedAt ?:Long.MAX_VALUE))return
        state=state.copy(observedArmed=armed)
        if(armed==null) {
            state=state.copy(status=ArmCommandStatus.ABORTED,reason="Trạng thái ARM không rõ; không phát lại lệnh")
            return
        }
        val ackAt=acceptedAckAt
        if(ackAt!=null && receivedAtMs>ackAt && armed==intent.desiredArmed) {
            state=state.copy(status=ArmCommandStatus.CONFIRMED,heartbeatConfirmed=true,
                reason="Đã có ACK và HEARTBEAT mới xác nhận trạng thái ARM thực")
        } else if(armed==intent.desiredArmed && ackAt==null) {
            state=state.copy(reason="Đã quan sát trạng thái mong muốn; chưa có ACK nên không quy kết do lệnh ứng dụng")
        }
    }

    @Synchronized fun tick() {
        if(pending() && nowMs()-(requestedAt ?:nowMs())>=COMMAND_DEADLINE_MS)
            state=state.copy(status=ArmCommandStatus.TIMED_OUT,reason="Quá hạn xác nhận; không tự gửi lại, cần đọc trạng thái FC")
    }
    @Synchronized fun cancel(reason:String="Người dùng hủy theo dõi lệnh") {
        if(pending())state=state.copy(status=ArmCommandStatus.CANCELLED,reason="$reason; hủy theo dõi không thu hồi lệnh đã đến FC")
    }
    @Synchronized fun linkLost() {
        if(pending())state=state.copy(status=ArmCommandStatus.ABORTED,reason="Mất link; kết quả lệnh chưa xác minh, không tự phát lại khi nối lại")
    }
    @Synchronized fun snapshot():ArmCommandSnapshot { tick();return state }
}
