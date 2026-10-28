package dev.drone.rcbench.mavlink

/** Audited subset of the official MAVLink common.xml dialect, wire order and CRC_EXTRA.
 * https://github.com/mavlink/mavlink/blob/master/message_definitions/v1.0/common.xml
 * Audited against mavlink/mavlink commit 9324a919111e64449ee2e49b839f75730e733da3.
 * SYS_STATUS accepts its current 43-byte maximum and the legacy 31-byte base.
 * This bounded subset does not promise compatibility with arbitrary future extensions.
 * Unknown messages are never presented as CRC-verified telemetry.
 */
internal data class MessageDefinition(val maximumLength: Int, val crcExtra: Int)

internal object CommonDialect {
    val messages = mapOf(
        0 to MessageDefinition(9, 50),       // HEARTBEAT (minimal.xml, included by common.xml)
        1 to MessageDefinition(43, 124),     // SYS_STATUS; extensions begin at byte 31
        24 to MessageDefinition(52, 24),     // GPS_RAW_INT; extensions begin at byte 30
        30 to MessageDefinition(28, 39),     // ATTITUDE
        65 to MessageDefinition(42, 118),    // RC_CHANNELS
        70 to MessageDefinition(38, 124),    // RC_CHANNELS_OVERRIDE; extensions at byte 18
        253 to MessageDefinition(54, 83),    // STATUSTEXT; extensions at byte 51
    )
}

/** A frame emitted by [Mavlink2Parser] has valid X25 CRC and is unsigned.
 * MAVLink CRC detects corruption; it provides no authentication or encryption.
 */
data class MavFrame(
    val sequence: Int,
    val systemId: Int,
    val componentId: Int,
    val messageId: Int,
    val payload: ByteArray,
    val incompatFlags: Int = 0,
    val compatFlags: Int = 0,
)

object MavlinkCrc {
    fun x25(bytes: ByteArray, offset: Int, length: Int, crcExtra: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        require(crcExtra in 0..255)
        var crc = 0xffff
        for (i in offset until offset + length) crc = accumulate(bytes[i].toInt() and 0xff, crc)
        return accumulate(crcExtra, crc)
    }

    private fun accumulate(value: Int, crc: Int): Int {
        var tmp = value xor (crc and 0xff)
        tmp = (tmp xor (tmp shl 4)) and 0xff
        return ((crc ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)) and 0xffff
    }
}

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff
internal fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
internal fun ByteArray.i16(offset: Int): Int = u16(offset).toShort().toInt()
internal fun ByteArray.i32(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8) or
    (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)
internal fun ByteArray.u32(offset: Int): Long = i32(offset).toLong() and 0xffffffffL
internal fun ByteArray.i64(offset: Int): Long {
    var value = 0L
    for (i in 0..7) value = value or (u8(offset + i).toLong() shl (8 * i))
    return value
}
internal fun ByteArray.f32(offset: Int): Float = Float.fromBits(i32(offset))
internal fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}
