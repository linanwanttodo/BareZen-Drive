package com.linan.barezen_drive

import com.linan.barezen_drive.platform.Sha256er
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    @Test
    fun abcVector() {
        val h = Sha256er.newInstance()
        h.update("abc".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h.digestHex())
    }

    @Test
    fun emptyVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256er.newInstance().digestHex())
    }

    @Test
    fun chunkedSameAsWhole() {
        val data = (0 until 1000).map { (it % 251).toByte() }.toByteArray()
        val whole = Sha256er.newInstance().apply { update(data) }.digestHex()
        val chunked = Sha256er.newInstance().apply {
            update(data.copyOfRange(0, 7))
            update(data.copyOfRange(7, 64))
            update(data.copyOfRange(64, data.size))
        }.digestHex()
        assertEquals(whole, chunked)
    }
}
