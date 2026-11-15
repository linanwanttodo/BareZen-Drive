package com.linan.barezen_drive.files

import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Checksum

/**
 * 合并循环里顺带算多算法摘要的 sink。恒含 SHA-256（blob 键就是它），调用方按需
 * 加 MD5/SHA-1/CRC32/CRC32C。放在 files 包而不是 s3 包：上传管线不该反过来依赖
 * 协议层。
 */
class UploadDigests(extra: Set<String> = emptySet()) {
    private val md = LinkedHashMap<String, MessageDigest>()
    private val crc = LinkedHashMap<String, Checksum>()
    private var finished: Map<String, ByteArray>? = null

    init {
        add("SHA256")
        extra.forEach { add(it) }
    }

    private fun add(name: String) {
        when (name) {
            "SHA256" -> md[name] = MessageDigest.getInstance("SHA-256")
            "SHA1" -> md[name] = MessageDigest.getInstance("SHA-1")
            "MD5" -> md[name] = MessageDigest.getInstance("MD5")
            "CRC32" -> crc[name] = CRC32()
            "CRC32C" -> crc[name] = CRC32C()
            else -> throw IllegalArgumentException("unsupported digest: $name")
        }
    }

    fun update(buf: ByteArray, len: Int) {
        md.values.forEach { it.update(buf, 0, len) }
        crc.values.forEach { it.update(buf, 0, len) }
    }

    /** 只调一次：MessageDigest.digest() 会重置状态。 */
    fun raw(): Map<String, ByteArray> {
        finished?.let { return it }
        val out = LinkedHashMap<String, ByteArray>()
        md.forEach { (name, digest) -> out[name] = digest.digest() }
        crc.forEach { (name, checksum) ->
            val v = checksum.value
            out[name] = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        }
        finished = out
        return out
    }
}