package dev.drone.controller.mavlink

/** Full official ardupilotmega/common include closure, generated from pinned XML.
 * Typed telemetry remains a deliberately smaller subset. Unknown dialect IDs are
 * counted separately and never validated using an invented CRC_EXTRA of zero.
 * See docs/MAVLINK_DIALECT_PROVENANCE.json and scripts/protocol/generate_dialect.py.
 */
internal data class MessageDefinition(
    val maximumLength:Int,val crcExtra:Int,val minimumLength:Int=1,val name:String="",
)
internal object CommonDialect {
    val messages=GeneratedArduPilotDialect.messages
}

/** Sequence order is scoped to a sender and connection, not authentication.
 * Diagnostic consumers may inspect every CRC-valid frame. Control freshness and
 * command/RC feedback must ignore DUPLICATE and REORDERED frames. */
enum class SequenceDisposition { FIRST, IN_ORDER, GAP, DUPLICATE, REORDERED }

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
    val sequenceDisposition: SequenceDisposition = SequenceDisposition.IN_ORDER,
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

internal fun ByteArray.putI32(offset: Int, value: Int) {
    for (i in 0..3) this[offset+i] = (value ushr (8*i)).toByte()
}
internal fun ByteArray.putF32(offset: Int, value: Float) = putI32(offset, value.toRawBits())
