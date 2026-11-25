package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.EvidenceMedia
import com.linan.barezen_drive.data.evidence.SESSION_ID
import com.linan.barezen_drive.data.evidence.VAULT_ID

const val SEGMENT_CHUNK_BYTES = 1 shl 20
const val SEGMENT_FIXED_HEADER_BYTES = 84
const val MAX_SEGMENT_CHUNKS = 0xffff_ffffL

data class SegmentHeader(
    val vaultId: String,
    val sessionId: String,
    val segmentNumber: Int,
    val media: EvidenceMedia,
    val plaintextLength: Long,
    val chunkSize: Int,
    val chunkCount: Long,
    val startedAtMs: Long,
    val durationMs: Long,
    val dekVersion: Int,
)

fun segmentChunkCount(plaintextLength: Long): Long {
    require(plaintextLength > 0) { "empty segment" }
    require(plaintextLength <= MAX_SEGMENT_CHUNKS * SEGMENT_CHUNK_BYTES) { "segment too large" }
    return (plaintextLength - 1) / SEGMENT_CHUNK_BYTES + 1
}

fun segmentChunkPlaintextLength(header: SegmentHeader, chunkIndex: Long): Int {
    require(chunkIndex in 0 until header.chunkCount)
    val remaining = header.plaintextLength - chunkIndex * SEGMENT_CHUNK_BYTES
    return remaining.coerceAtMost(SEGMENT_CHUNK_BYTES.toLong()).toInt()
}

fun segmentCiphertextLength(header: SegmentHeader): Long =
    header.encodedSize() + header.chunkCount * (12L + SEGMENT_CHUNK_BYTES + 16L) -
        (SEGMENT_CHUNK_BYTES - segmentChunkPlaintextLength(header, header.chunkCount - 1))

fun SegmentHeader.encodedSize(): Int =
    SEGMENT_FIXED_HEADER_BYTES + sessionId.encodeToByteArray().size

fun encodeSegmentHeader(header: SegmentHeader): ByteArray {
    require(header.vaultId.matches(VAULT_ID))
    require(header.sessionId.matches(SESSION_ID))
    require(header.segmentNumber in 1..Int.MAX_VALUE)
    require(header.chunkSize == SEGMENT_CHUNK_BYTES)
    require(header.chunkCount == segmentChunkCount(header.plaintextLength))
    require(header.startedAtMs >= 0 && header.durationMs >= 0 && header.dekVersion == DEK_VERSION)
    val session = header.sessionId.encodeToByteArray()
    require(session.size in 1..128)
    val out = ByteArray(header.encodedSize())
    var p = 0
    fun putByte(value: Int) { out[p++] = value.toByte() }
    fun putShort(value: Int) {
        out[p++] = (value ushr 8).toByte()
        out[p++] = value.toByte()
    }
    fun putInt(value: Int) {
        out[p++] = (value ushr 24).toByte()
        out[p++] = (value ushr 16).toByte()
        out[p++] = (value ushr 8).toByte()
        out[p++] = value.toByte()
    }
    fun putLong(value: Long) {
        for (shift in 56 downTo 0 step 8) out[p++] = (value ushr shift).toByte()
    }
    "BZEF".encodeToByteArray().copyInto(out, p); p += 4
    putShort(EVIDENCE_FORMAT_VERSION)
    putShort(0)
    putByte(if (header.media == EvidenceMedia.VIDEO) 2 else 1)
    putByte(0)
    header.vaultId.encodeToByteArray().copyInto(out, p); p += 32
    putInt(header.segmentNumber)
    putLong(header.plaintextLength)
    putInt(header.chunkSize)
    putInt(header.chunkCount.toInt())
    putLong(header.startedAtMs)
    putLong(header.durationMs)
    putInt(header.dekVersion)
    putShort(session.size)
    session.copyInto(out, p)
    return out
}

fun decodeSegmentHeader(bytes: ByteArray): SegmentHeader {
    require(bytes.size >= SEGMENT_FIXED_HEADER_BYTES) { "truncated header" }
    var p = 0
    fun byte(): Int = bytes[p++].toInt() and 0xff
    fun short(): Int = (byte() shl 8) or byte()
    fun int(): Int = (short() shl 16) or short()
    fun long(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or byte().toLong() }
        return value
    }
    if (bytes.copyOfRange(0, 4).decodeToString() != "BZEF") {
        throw IllegalStateException("invalid evidence magic")
    }
    p = 4
    require(short() == EVIDENCE_FORMAT_VERSION) { "unsupported evidence version" }
    require(short() == 0) { "unsupported evidence flags" }
    val media = when (byte()) {
        1 -> EvidenceMedia.AUDIO
        2 -> EvidenceMedia.VIDEO
        else -> throw IllegalStateException("unsupported evidence media")
    }
    require(byte() == 0) { "invalid reserved byte" }
    val vault = bytes.copyOfRange(p, p + 32).decodeToString(); p += 32
    val segment = int()
    val length = long()
    val chunkSize = int()
    val chunks = int().toLong() and 0xffff_ffffL
    val started = long()
    val duration = long()
    val dekVersion = int()
    val sessionLength = short()
    require(sessionLength in 1..128 && bytes.size == p + sessionLength) { "invalid session length" }
    val session = bytes.copyOfRange(p, p + sessionLength).decodeToString(throwOnInvalidSequence = true)
    require(vault.matches(VAULT_ID)) { "invalid vault id" }
    require(session.matches(SESSION_ID)) { "invalid session id" }
    require(segment > 0 && length > 0 && chunkSize == SEGMENT_CHUNK_BYTES && chunks > 0)
    require(chunks == segmentChunkCount(length)) { "invalid chunk count" }
    require(started >= 0 && duration >= 0 && dekVersion == DEK_VERSION) { "invalid header metadata" }
    return SegmentHeader(vault, session, segment, media, length, chunkSize, chunks, started, duration, dekVersion)
}

fun segmentAad(headerBytes: ByteArray, chunkIndex: Long): ByteArray {
    require(chunkIndex in 0 until MAX_SEGMENT_CHUNKS)
    val index = chunkIndex.toInt()
    return headerBytes + byteArrayOf(
        (index ushr 24).toByte(),
        (index ushr 16).toByte(),
        (index ushr 8).toByte(),
        index.toByte(),
    )
}
