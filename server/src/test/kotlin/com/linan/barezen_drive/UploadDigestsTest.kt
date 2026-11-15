package com.linan.barezen_drive

import com.linan.barezen_drive.files.UploadDigests
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S3 的 x-amz-checksum-* 直接落这里的字节，所以编码形状必须钉死：CRC 是 4 字节
 * 大端，不是十进制字符串也不是小端。期望值用公开的标准校验向量，不用被测代码
 * 自己算一遍。
 */
class UploadDigestsTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sha256IsAlwaysPresent() {
        val d = UploadDigests()
        d.update("abc".toByteArray(), 3)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(d.raw()["SHA256"]!!),
        )
    }

    @Test
    fun extraAlgorithmsMatchTheStandardVectors() {
        val d = UploadDigests(setOf("MD5", "SHA1"))
        d.update("abc".toByteArray(), 3)
        val raw = d.raw()
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(raw["MD5"]!!))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(raw["SHA1"]!!))
    }

    @Test
    fun crcsAreFourBigEndianBytes() {
        val d = UploadDigests(setOf("CRC32", "CRC32C"))
        d.update("123456789".toByteArray(), 9)
        val raw = d.raw()
        assertContentEquals(byteArrayOf(0xcb.toByte(), 0xf4.toByte(), 0x39, 0x26), raw["CRC32"]!!)
        assertContentEquals(byteArrayOf(0xe3.toByte(), 0x06, 0x92.toByte(), 0x83.toByte()), raw["CRC32C"]!!)
    }

    @Test
    fun updateHonoursTheLengthArgument() {
        val buf = "abcXXXXX".toByteArray()
        val d = UploadDigests(setOf("CRC32"))
        d.update(buf, 3)
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())), hex(d.raw()["SHA256"]!!))
    }

    @Test
    fun rawIsIdempotent() {
        val d = UploadDigests(setOf("CRC32C"))
        d.update("abc".toByteArray(), 3)
        val first = d.raw()
        // MessageDigest.digest() 会重置状态：第二次调用必须给同一份结果，不是空摘要。
        val second = d.raw()
        assertEquals(first.keys, second.keys)
        first.forEach { (k, v) -> assertContentEquals(v, second[k]!!, k) }
        assertTrue(first["SHA256"]!!.isNotEmpty())
    }

    @Test
    fun anUnsupportedAlgorithmIsRejected() {
        assertFailsWith<IllegalArgumentException> { UploadDigests(setOf("SHA3-256")) }
    }
}