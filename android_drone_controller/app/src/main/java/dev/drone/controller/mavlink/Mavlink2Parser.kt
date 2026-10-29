package dev.drone.controller.mavlink

/** Counters describe observed framing, never authentication or radio-link loss. */
data class ParserDiagnostics(
    val validFrames:Long=0,val crcErrors:Long=0,val unknownMessages:Long=0,
    val signedRejected:Long=0,val unsupportedFlags:Long=0,val mavlink1Ignored:Long=0,
    val garbageBytes:Long=0,val malformedFrames:Long=0,
    val bytesReceived:Long=0,val signedFrames:Long=0,val resynchronizations:Long=0,
    val unsupportedMessages:Long=0,val partialTimeouts:Long=0,
    val sequenceGaps:Long=0,val duplicateFrames:Long=0,val reorderedFrames:Long=0,
    val sequenceSourceEvictions:Long=0,
)
data class MavSource(val systemId:Int,val componentId:Int)
data class SourceSequenceDiagnostics(
    val lastAcceptedSequence:Int?,val frames:Long=0,val sequenceGaps:Long=0,
    val duplicates:Long=0,val reordered:Long=0,val explicitResets:Long=0,
)

/** Incremental MAVLink2 parser, confined to the transport reader thread.
 * Signed frames include the full13-byte signature, then are rejected because no
 * signing-key/replay verifier is provisioned. MAVLink1 is identified and skipped
 * whole. Unknown dialect frames are skipped whole without a guessed CRC_EXTRA.
 * The pending buffer is280 bytes and sequence histories are capped at128 sources.
 */
class Mavlink2Parser(
    private val clockMs:()->Long={System.nanoTime()/1_000_000},
    private val partialFrameTimeoutMs:Long=1500,
) {
    init {require(partialFrameTimeoutMs>0)}
    private val pending=ByteArray(280)
    private var used=0
    private var partialStartedAt:Long?=null
    private val sequences=linkedMapOf<MavSource,SourceSequenceDiagnostics>()
    @Volatile var diagnostics=ParserDiagnostics()
        private set
    val bufferedBytes:Int get()=used
    val sourceSequences:Map<MavSource,SourceSequenceDiagnostics> get()=sequences.toMap()

    fun reset() {
        used=0;partialStartedAt=null;diagnostics=ParserDiagnostics();sequences.clear()
    }
    /** Call only after an independently established vehicle reboot; a backwards
     * sequence alone is equally consistent with reordered/duplicated frames. */
    fun resetSourceSequence(systemId:Int,componentId:Int) {
        val key=MavSource(systemId,componentId)
        sequences[key]?.let { sequences[key]=it.copy(lastAcceptedSequence=null,explicitResets=it.explicitResets+1) }
    }
    /** A stalled TCP fragment cannot pin the next session's bytes indefinitely.
     * Transport can also call this during idle ticks; feed always checks it first. */
    fun expirePartialFrame():Boolean {
        val started=partialStartedAt ?: return false
        if(used==0 || clockMs()-started<partialFrameTimeoutMs)return false
        used=0;partialStartedAt=null
        diagnostics=diagnostics.copy(partialTimeouts=diagnostics.partialTimeouts+1,
            resynchronizations=diagnostics.resynchronizations+1)
        return true
    }
    fun feed(bytes:ByteArray,offset:Int=0,length:Int=bytes.size-offset):List<MavFrame> {
        require(offset>=0 && length>=0 && offset<=bytes.size-length)
        expirePartialFrame()
        diagnostics=diagnostics.copy(bytesReceived=diagnostics.bytesReceived+length)
        val frames=ArrayList<MavFrame>()
        for(i in offset until offset+length) {
            if(used==pending.size) {malformed();discard(1)}
            if(used==0)partialStartedAt=clockMs()
            pending[used++]=bytes[i]
            drain(frames)
            if(used==0)partialStartedAt=null
        }
        return frames
    }
    private fun malformed() {
        diagnostics=diagnostics.copy(malformedFrames=diagnostics.malformedFrames+1,
            resynchronizations=diagnostics.resynchronizations+1)
    }
    private fun drain(frames:MutableList<MavFrame>) {
        while(used>0) {
            val magic=pending.u8(0)
            if(magic!=0xfd && magic!=0xfe) {
                diagnostics=diagnostics.copy(garbageBytes=diagnostics.garbageBytes+1);discard(1);continue
            }
            if(used<2)return
            val length=pending.u8(1)
            if(magic==0xfd && length==0) {malformed();discard(1);continue}
            if(magic==0xfe) {
                val size=length+8
                if(used<size)return
                diagnostics=diagnostics.copy(mavlink1Ignored=diagnostics.mavlink1Ignored+1)
                discard(size);continue
            }
            if(used<3)return
            val incompatibility=pending.u8(2)
            val signed=incompatibility and 1!=0
            val size=12+length+if(signed)13 else 0
            if(used<10)return
            val id=pending.u8(7) or (pending.u8(8) shl 8) or (pending.u8(9) shl 16)
            val definition=CommonDialect.messages[id]
            if(definition!=null && length>definition.maximumLength) {malformed();discard(1);continue}
            if(used<size)return
            if(signed)diagnostics=diagnostics.copy(signedFrames=diagnostics.signedFrames+1)
            if(incompatibility and 0xfe!=0) {
                diagnostics=diagnostics.copy(unsupportedFlags=diagnostics.unsupportedFlags+1)
                discard(size);continue
            }
            if(definition==null) {
                diagnostics=diagnostics.copy(unknownMessages=diagnostics.unknownMessages+1)
                discard(size);continue
            }
            val actualCrc=pending.u16(10+length)
            val expectedCrc=MavlinkCrc.x25(pending,1,9+length,definition.crcExtra)
            if(actualCrc!=expectedCrc) {
                diagnostics=diagnostics.copy(crcErrors=diagnostics.crcErrors+1,
                    resynchronizations=diagnostics.resynchronizations+1)
                discard(1);continue
            }
            if(signed) {
                diagnostics=diagnostics.copy(signedRejected=diagnostics.signedRejected+1)
                discard(size);continue
            }
            val sequence=pending.u8(4);val systemId=pending.u8(5);val componentId=pending.u8(6)
            val disposition=countSequence(sequence,systemId,componentId)
            val frame=MavFrame(sequence,systemId,componentId,id,
                pending.copyOfRange(10,10+length),incompatibility,pending.u8(3),disposition)
            frames.add(frame)
            diagnostics=diagnostics.copy(validFrames=diagnostics.validFrames+1,
                unsupportedMessages=diagnostics.unsupportedMessages+if(id !in TYPED_TELEMETRY_IDS)1 else 0)
            discard(size)
        }
    }
    private fun countSequence(sequence:Int,systemId:Int,componentId:Int):SequenceDisposition {
        val key=MavSource(systemId,componentId)
        val previous=sequences.remove(key)
        if(previous==null) {
            if(sequences.size>=128) {
                sequences.remove(sequences.keys.first())
                diagnostics=diagnostics.copy(sequenceSourceEvictions=diagnostics.sequenceSourceEvictions+1)
            }
            sequences[key]=SourceSequenceDiagnostics(sequence,frames=1);return SequenceDisposition.FIRST
        }
        val delta=previous.lastAcceptedSequence?.let { (sequence-it+256)%256 }
        val duplicate=if(delta==0)1L else 0L
        val reordered=if(delta!=null && delta>=128)1L else 0L
        val gap=if(delta!=null && delta in 2..127)(delta-1).toLong() else 0L
        // Late frames never move the high-water mark backwards and invent a
        // second gap when the next in-order frame arrives.
        val accepted=if(delta==null || delta in 1..127)sequence else previous.lastAcceptedSequence
        sequences[key]=previous.copy(lastAcceptedSequence=accepted,frames=previous.frames+1,
            sequenceGaps=previous.sequenceGaps+gap,duplicates=previous.duplicates+duplicate,
            reordered=previous.reordered+reordered)
        diagnostics=diagnostics.copy(sequenceGaps=diagnostics.sequenceGaps+gap,
            duplicateFrames=diagnostics.duplicateFrames+duplicate,reorderedFrames=diagnostics.reorderedFrames+reordered)
        return when {
            delta==null -> SequenceDisposition.FIRST
            delta==0 -> SequenceDisposition.DUPLICATE
            delta==1 -> SequenceDisposition.IN_ORDER
            delta in 2..127 -> SequenceDisposition.GAP
            else -> SequenceDisposition.REORDERED
        }
    }
    private fun discard(count:Int) {
        if(count<used)pending.copyInto(pending,0,count,used)
        used-=count
        if(used==0)partialStartedAt=null
    }
    companion object {
        private val TYPED_TELEMETRY_IDS=setOf(0,1,22,24,29,30,33,36,65,77,147,148,193,245,253)
    }
}
