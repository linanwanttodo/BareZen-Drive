package com.linan.barezen_drive.evidence

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

data class ParsedSegmentFile(
    val header: SegmentHeader,
    val encodedHeader: ByteArray,
)

data class VerifiedSegment(
    val header: SegmentHeader,
    val plaintextSha256: String,
)

data class EncryptedSegment(
    val ciphertextSha256: String,
    val plaintextSha256: String,
    val ciphertextLength: Long,
)

private const val NONCE_BYTES = 12
private const val TAG_BYTES = 16

fun encryptSegment(
    plaintext: File,
    ciphertext: File,
    dek: ByteArray,
    metadata: SegmentHeader,
): EncryptedSegment {
    require(!ciphertext.exists()) { "ciphertext already exists" }
    val header = metadata.copy(
        plaintextLength = plaintext.length(),
        chunkCount = segmentChunkCount(plaintext.length()),
    )
    val headerBytes = encodeSegmentHeader(header)
    val part = File(ciphertext.path + ".part")
    require(!part.exists()) { "encryption already in progress" }
    val plainDigest = MessageDigest.getInstance("SHA-256")
    val cipherDigest = MessageDigest.getInstance("SHA-256")
    try {
        FileInputStream(plaintext).use { input ->
            FileOutputStream(part).use { output ->
                output.write(headerBytes)
                cipherDigest.update(headerBytes)
                repeat(header.chunkCount.toInt()) { index ->
                    val plain = readUpTo(input, segmentChunkPlaintextLength(header, index.toLong()))
                    plainDigest.update(plain)
                    val sealed = EvidenceCrypto.seal(dek, plain, segmentAad(headerBytes, index.toLong()))
                    plain.fill(0)
                    output.write(sealed.nonce)
                    output.write(sealed.ciphertextAndTag)
                    cipherDigest.update(sealed.nonce)
                    cipherDigest.update(sealed.ciphertextAndTag)
                }
                if (input.read() != -1) error("plaintext changed while encrypting")
                output.fd.sync()
            }
        }
        require(part.length() == segmentCiphertextLength(header)) { "invalid ciphertext length" }
        if (!part.renameTo(ciphertext)) error("cannot publish ciphertext")
        return EncryptedSegment(cipherDigest.digest().toHex(), plainDigest.digest().toHex(), ciphertext.length())
    } catch (failure: Throwable) {
        part.delete()
        throw failure
    }
}

fun readSegmentHeader(ciphertext: File): ParsedSegmentFile = RandomAccessFile(ciphertext, "r").use { file ->
    if (file.length() < SEGMENT_FIXED_HEADER_BYTES) {
        throw SecurityException("truncated evidence header")
    }
    val fixed = ByteArray(SEGMENT_FIXED_HEADER_BYTES)
    file.readFully(fixed)
    val sessionLength = ((fixed[82].toInt() and 0xff) shl 8) or (fixed[83].toInt() and 0xff)
    if (sessionLength !in 1..128) throw SecurityException("invalid session length")
    val session = ByteArray(sessionLength)
    file.readFully(session)
    val encoded = fixed + session
    try {
        ParsedSegmentFile(decodeSegmentHeader(encoded), encoded)
    } catch (failure: RuntimeException) {
        throw SecurityException("invalid evidence header", failure)
    }
}

fun decryptSegmentToCache(ciphertext: File, cache: File, dek: ByteArray) {
    val parsed = readSegmentHeader(ciphertext)
    val part = File(cache.path + ".part")
    require(!part.exists()) { "decryption already in progress" }
    cache.parentFile?.mkdirs()
    try {
        FileInputStream(ciphertext).use { input ->
            input.channel.position(parsed.encodedHeader.size.toLong())
            FileOutputStream(part).use { output ->
                repeat(parsed.header.chunkCount.toInt()) { index ->
                    val sealed = readSealed(input, segmentChunkPlaintextLength(parsed.header, index.toLong()))
                    val plain = EvidenceCrypto.open(dek, sealed, segmentAad(parsed.encodedHeader, index.toLong()))
                    output.write(plain)
                    plain.fill(0)
                }
                if (input.read() != -1) throw SecurityException("trailing evidence data")
                output.fd.sync()
            }
        }
        if (cache.exists() && !cache.delete()) error("cannot replace decrypted cache")
        if (!part.renameTo(cache)) error("cannot publish decrypted cache")
    } catch (failure: Throwable) {
        part.delete()
        throw failure
    }
}

fun verifySegment(ciphertext: File, dek: ByteArray): VerifiedSegment {
    val parsed = readSegmentHeader(ciphertext)
    RandomAccessFile(ciphertext, "r").use { input ->
        input.seek(parsed.encodedHeader.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256")
        repeat(parsed.header.chunkCount.toInt()) { index ->
            val sealed = readSealed(input, segmentChunkPlaintextLength(parsed.header, index.toLong()))
            val plain = EvidenceCrypto.open(dek, sealed, segmentAad(parsed.encodedHeader, index.toLong()))
            digest.update(plain)
            plain.fill(0)
        }
        if (input.filePointer != input.length()) throw SecurityException("trailing evidence data")
        return VerifiedSegment(parsed.header, digest.digest().toHex())
    }
}

fun readAuthenticatedRange(ciphertext: File, dek: ByteArray, offset: Long, length: Int): ByteArray {
    require(offset >= 0 && length >= 0)
    val parsed = readSegmentHeader(ciphertext)
    require(offset <= parsed.header.plaintextLength && length <= parsed.header.plaintextLength - offset) {
        "range outside segment"
    }
    if (length == 0) return ByteArray(0)
    val firstChunk = offset / SEGMENT_CHUNK_BYTES
    val lastChunk = (offset + length - 1) / SEGMENT_CHUNK_BYTES
    val output = ByteArrayOutputStream(length)
    RandomAccessFile(ciphertext, "r").use { input ->
        input.seek(cipherChunkOffset(parsed.encodedHeader.size, firstChunk))
        for (index in firstChunk..lastChunk) {
            val sealed = readSealed(input, segmentChunkPlaintextLength(parsed.header, index))
            val plain = EvidenceCrypto.open(dek, sealed, segmentAad(parsed.encodedHeader, index))
            val chunkOffset = index * SEGMENT_CHUNK_BYTES
            val from = (offset - chunkOffset).coerceAtLeast(0).toInt()
            val to = ((offset + length - chunkOffset).coerceAtMost(plain.size.toLong())).toInt()
            if (from < to) output.write(plain, from, to - from)
            plain.fill(0)
        }
    }
    return output.toByteArray()
}

fun File.sha256Hex(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toHex()
}

private fun cipherChunkOffset(headerSize: Int, chunkIndex: Long): Long =
    headerSize + chunkIndex * (NONCE_BYTES + SEGMENT_CHUNK_BYTES + TAG_BYTES)

private fun readSealed(input: RandomAccessFile, plaintextLength: Int): EvidenceCrypto.SealedBytes {
    val ciphertext = ByteArray(NONCE_BYTES + plaintextLength + TAG_BYTES)
    try {
        input.readFully(ciphertext)
    } catch (_: java.io.EOFException) {
        throw SecurityException("truncated evidence chunk")
    }
    return EvidenceCrypto.SealedBytes(
        nonce = ciphertext.copyOfRange(0, NONCE_BYTES),
        ciphertextAndTag = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size),
    )
}

private fun readSealed(input: FileInputStream, plaintextLength: Int): EvidenceCrypto.SealedBytes {
    val ciphertext = readUpTo(input, NONCE_BYTES + plaintextLength + TAG_BYTES)
    if (ciphertext.size != NONCE_BYTES + plaintextLength + TAG_BYTES) {
        throw SecurityException("truncated evidence chunk")
    }
    return EvidenceCrypto.SealedBytes(
        nonce = ciphertext.copyOfRange(0, NONCE_BYTES),
        ciphertextAndTag = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size),
    )
}

private fun readUpTo(input: FileInputStream, max: Int): ByteArray {
    val output = ByteArray(max)
    var total = 0
    while (total < max) {
        val count = input.read(output, total, max - total)
        if (count == -1) break
        total += count
    }
    return if (total == max) output else output.copyOf(total)
}
