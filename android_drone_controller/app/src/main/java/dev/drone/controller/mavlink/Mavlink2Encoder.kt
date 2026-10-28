package dev.drone.controller.mavlink

/** BENCH ONLY. Outbound commands are restricted to telemetry reads and bench RC.
 * No generic COMMAND_LONG/arm/disarm/mode/actuator encoder is exposed.
 * Connection lifecycle owns this object; create a new instance on reconnect.
 */
class Mavlink2Encoder(
    private val senderSystemId: Int = 255,
    private val senderComponentId: Int = 190,
) {
    init {
        require(senderSystemId in 1..255 && senderComponentId in 1..255)
    }
    private var sequence = 0

    fun heartbeat(): ByteArray {
        val payload = byteArrayOf(0, 0, 0, 0, 6, 8, 0, 4, 3)
        return encode(0, payload)
    }

    /** Channels 1..4 only. 65535 means ignore, never release.
     * Target IDs come from a validated autopilot heartbeat, never broadcast 0.
     * Channels 5..8 use 65535; 9..18 use 0 (their extension-field ignore value).
     * Stopping transmission is the only automatic release behavior of this app.
     */
    fun rcOverride(targetSystem: Int, targetComponent: Int, channels: IntArray): ByteArray {
        require(targetSystem in 1..255 && targetComponent in 1..255)
        require(channels.size == 4)
        require(channels.all { it in 800..2200 || it == 65535 })
        val payload = ByteArray(38)
        for (i in 0..7) payload.putU16(i * 2, if (i < 4) channels[i] else 65535)
        payload[16] = targetSystem.toByte()
        payload[17] = targetComponent.toByte()
        // ByteArray initializes channels 9..18 to zero: ignore, not release.
        return encode(70, payload)
    }

    /** Read-only telemetry requests. Interval must be 200 ms..10 s; no unbounded streams. */
    fun setMessageInterval(targetSystem: Int, targetComponent: Int, messageId: Int, intervalUs: Int): ByteArray {
        require(messageId in READABLE_MESSAGES && intervalUs in 200_000..10_000_000)
        return telemetryCommand(targetSystem, targetComponent, 511, messageId.toFloat(), intervalUs.toFloat())
    }

    fun requestMessage(targetSystem: Int, targetComponent: Int, messageId: Int): ByteArray {
        require(messageId in READABLE_MESSAGES)
        return telemetryCommand(targetSystem, targetComponent, 512, messageId.toFloat(), 0f)
    }

    /** Exact name read only: never PARAM_SET, never change a vehicle setting. */
    fun parameterRead(targetSystem: Int, targetComponent: Int, name: String): ByteArray {
        require(targetSystem in 1..255 && targetComponent in 1..255)
        require(name.length in 1..16 && name.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
        val p=ByteArray(20)
        p.putU16(0, 65535) // index=-1; select parameter by name
        p[2]=targetSystem.toByte();p[3]=targetComponent.toByte()
        name.toByteArray(Charsets.US_ASCII).copyInto(p,4)
        return encode(20,p)
    }

    /** Protocol evaluation only; production session never selects this method.
     * MAVLink x=pitch, y=roll, z=throttle 0..1000, r=yaw. No buttons/extensions. */
    fun manualControl(targetSystem: Int, pitch: Int, roll: Int, throttle: Int, yaw: Int): ByteArray {
        require(targetSystem in 1..255)
        require(pitch in -1000..1000 && roll in -1000..1000 && throttle in 0..1000 && yaw in -1000..1000)
        val p=ByteArray(30)
        p.putU16(0,pitch);p.putU16(2,roll);p.putU16(4,throttle);p.putU16(6,yaw)
        p[10]=targetSystem.toByte()
        return encode(69,p)
    }

    private fun telemetryCommand(targetSystem: Int,targetComponent: Int,command: Int,param1: Float,param2: Float): ByteArray {
        require(targetSystem in 1..255 && targetComponent in 1..255)
        check(command==511 || command==512)
        val p=ByteArray(33)
        p.putF32(0,param1);p.putF32(4,param2);p.putU16(28,command)
        p[30]=targetSystem.toByte();p[31]=targetComponent.toByte()
        return encode(76,p)
    }

    companion object {
        val READABLE_MESSAGES=setOf(0,1,22,24,29,30,33,36,65,147,148,193,253)
    }

    private fun encode(messageId: Int, payload: ByteArray): ByteArray {
        val definition = checkNotNull(CommonDialect.messages[messageId])
        require(payload.size <= definition.maximumLength)
        // MAVLink 2 truncates zero tail bytes, including base fields; keep at least one byte.
        var payloadLength = payload.size
        while (payloadLength > 1 && payload[payloadLength - 1] == 0.toByte()) payloadLength--
        val frame = ByteArray(payloadLength + 12)
        frame[0] = 0xfd.toByte()
        frame[1] = payloadLength.toByte()
        frame[4] = sequence.toByte()
        sequence = (sequence + 1) and 0xff
        frame[5] = senderSystemId.toByte()
        frame[6] = senderComponentId.toByte()
        frame[7] = messageId.toByte()
        frame[8] = (messageId ushr 8).toByte()
        frame[9] = (messageId ushr 16).toByte()
        payload.copyInto(frame, 10, 0, payloadLength)
        frame.putU16(10 + payloadLength, MavlinkCrc.x25(frame, 1, payloadLength + 9, definition.crcExtra))
        return frame
    }
}
