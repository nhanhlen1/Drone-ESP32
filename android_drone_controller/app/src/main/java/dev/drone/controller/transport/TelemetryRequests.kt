package dev.drone.controller.transport

import dev.drone.controller.mavlink.*

/** Finite read-only discovery per connection. An ACK names command 511, not its
 * message-id parameter; late ACKs are never used to mark a stream as delivered. */
internal class TelemetryRequests {
    private val streams = linkedMapOf(65 to 200_000,1 to 1_000_000,30 to 200_000,
        24 to 1_000_000,33 to 500_000,29 to 1_000_000,147 to 1_000_000,
        36 to 1_000_000,193 to 1_000_000,148 to 5_000_000)
    private val parameters=listOf("MAV_GCS_SYSID","MAV_GCS_SYSID_HI","MAV_OPTIONS",
        "RC_OPTIONS","RC_OVERRIDE_TIME","ARMING_RUDDER","FLTMODE_CH",
        "FS_THR_ENABLE","FS_GCS_ENABLE","FS_GCS_TIMEOUT","RC_FS_TIMEOUT") +
        (1..4).flatMap { listOf("RC${it}_MIN","RC${it}_TRIM","RC${it}_MAX","RC${it}_REVERSED") }
    private val requested=linkedSetOf<Int>()
    private val observed=linkedSetOf<Int>()
    private var parameterIndex=0
    private var nextAt=0L
    private var awaitingAckUntil:Long?=null
    var lastAck:String?=null
        private set
    @Synchronized fun observe(frame:MavFrame,now:Long) {
        if(frame.sequenceDisposition in setOf(SequenceDisposition.DUPLICATE,SequenceDisposition.REORDERED))return
        observed.add(frame.messageId)
        val ack=TelemetryDecoder.decode(frame) as? Telemetry.CommandAck ?: return
        if(ack.targetSystem !in listOf(0,255) || ack.targetComponent !in listOf(0,190))return
        val result=when(ack.result){0->"chấp nhận";1->"tạm từ chối";2->"từ chối";3->"không hỗ trợ";4->"thất bại";5->"đang xử lý";6->"hủy";else->"mã ${ack.result}"}
        lastAck="COMMAND_ACK ${ack.command}: $result; chưa chứng minh có telemetry hoặc RC áp dụng"
        if(ack.command==511 && ack.result!=5 && awaitingAckUntil!=null) {
            awaitingAckUntil=null;nextAt=maxOf(nextAt,now+100)
        }
    }
    @Synchronized fun next(encoder:Mavlink2Encoder,system:Int,component:Int,now:Long):ByteArray? {
        if(now<nextAt)return null
        awaitingAckUntil?.let { if(now<it)return null else {awaitingAckUntil=null;lastAck="Quá hạn chờ ACK yêu cầu stream; không tự gửi lại"} }
        val id=streams.keys.firstOrNull { it !in requested }
        if(id!=null) {
            requested.add(id);nextAt=now+150;awaitingAckUntil=now+800
            return encoder.setMessageInterval(system,component,id,streams.getValue(id))
        }
        if(parameterIndex<parameters.size) {
            nextAt=now+150
            return encoder.parameterRead(system,component,parameters[parameterIndex++])
        }
        return null
    }
    @Synchronized fun summary():List<String> = streams.keys.map {
        "Message $it: "+ when { it in observed->"đã quan sát bản tin";it in requested->"đã tạo yêu cầu; chưa thấy bản tin";else->"chưa yêu cầu" }
    }
}
