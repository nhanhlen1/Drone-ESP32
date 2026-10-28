package dev.drone.rcbench.mavlink

/** Counts are stream diagnostics, not proof of an authenticated autopilot. */
data class ParserDiagnostics(
    val validFrames: Long = 0,
    val crcErrors: Long = 0,
    val unknownMessages: Long = 0,
    val signedRejected: Long = 0,
    val unsupportedFlags: Long = 0,
    val mavlink1Ignored: Long = 0,
    val garbageBytes: Long = 0,
    val malformedFrames: Long = 0,
)

/** Incremental, bounded-memory MAVLink 2 TCP decoder. Confine to one reader thread.
 * Split/coalesced reads are equivalent. A partial packet remains buffered until
 * completion or [reset]; TCP disconnect MUST call reset or discard the parser.
 * Signed packets are consumed in full, including the 13-byte signature, but are
 * rejected: no signing key, replay verifier or authenticated-control claim exists.
 * V1 packets are skipped as whole packets so embedded FD bytes are not mistaken
 * for V2 frames. Corrupt V2 packets resynchronize one byte at a time after CRC failure.
 */
class Mavlink2Parser {
    private val pending = ByteArray(280) // 10-byte header + 255-byte payload + CRC + signature
    private var used = 0
    var diagnostics = ParserDiagnostics()
        private set

    fun reset() {
        used = 0
        diagnostics = ParserDiagnostics()
    }

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<MavFrame> {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val frames = ArrayList<MavFrame>()
        for (i in offset until offset + length) {
            // Every legal frame fits pending; reaching this branch means malformed state.
            if (used == pending.size) {
                diagnostics = diagnostics.copy(malformedFrames = diagnostics.malformedFrames + 1)
                discard(1)
            }
            pending[used++] = bytes[i]
            drain(frames)
        }
        return frames
    }

    private fun drain(frames: MutableList<MavFrame>) {
        while (used > 0) {
            val magic = pending.u8(0)
            if (magic != 0xfd && magic != 0xfe) {
                diagnostics = diagnostics.copy(garbageBytes = diagnostics.garbageBytes + 1)
                discard(1)
                continue
            }
            if (used < 2) return
            val payloadLength = pending.u8(1)
            if (magic == 0xfe) {
                val size = payloadLength + 8
                if (used < size) return
                diagnostics = diagnostics.copy(mavlink1Ignored = diagnostics.mavlink1Ignored + 1)
                discard(size)
                continue
            }
            if (used < 3) return
            val incompatibility = pending.u8(2)
            val signed = incompatibility and 1 != 0
            val size = 12 + payloadLength + if (signed) 13 else 0
            if (used < 10) return
            val id = pending.u8(7) or (pending.u8(8) shl 8) or (pending.u8(9) shl 16)
            val definition = CommonDialect.messages[id]
            // A corrupt length byte in a known packet must not delay recovery
            // until 255 bogus payload bytes arrive on a sparse heartbeat stream.
            if (definition != null && payloadLength > definition.maximumLength) {
                diagnostics = diagnostics.copy(malformedFrames = diagnostics.malformedFrames + 1)
                discard(1)
                continue
            }
            if (used < size) return
            if (incompatibility and 0xfe != 0) {
                diagnostics = diagnostics.copy(unsupportedFlags = diagnostics.unsupportedFlags + 1)
                discard(size)
                continue
            }
            if (definition == null) {
                diagnostics = diagnostics.copy(unknownMessages = diagnostics.unknownMessages + 1)
                discard(size)
                continue
            }
            val actualCrc = pending.u16(10 + payloadLength)
            val expectedCrc = MavlinkCrc.x25(pending, 1, 9 + payloadLength, definition.crcExtra)
            if (actualCrc != expectedCrc) {
                diagnostics = diagnostics.copy(crcErrors = diagnostics.crcErrors + 1)
                discard(1)
                continue
            }
            if (signed) {
                diagnostics = diagnostics.copy(signedRejected = diagnostics.signedRejected + 1)
                discard(size)
                continue
            }
            frames.add(MavFrame(
                sequence = pending.u8(4), systemId = pending.u8(5), componentId = pending.u8(6),
                messageId = id, payload = pending.copyOfRange(10, 10 + payloadLength),
                incompatFlags = incompatibility, compatFlags = pending.u8(3),
            ))
            diagnostics = diagnostics.copy(validFrames = diagnostics.validFrames + 1)
            discard(size)
        }
    }

    private fun discard(count: Int) {
        if (count < used) pending.copyInto(pending, 0, count, used)
        used -= count
    }
}
