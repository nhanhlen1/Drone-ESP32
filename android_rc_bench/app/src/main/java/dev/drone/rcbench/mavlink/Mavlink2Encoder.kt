package dev.drone.rcbench.mavlink

/** BENCH ONLY. The entire public outbound surface is a GCS heartbeat and RC override.
 * No generic message/COMMAND_LONG/arm/disarm/mode/actuator encoder is exposed.
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
