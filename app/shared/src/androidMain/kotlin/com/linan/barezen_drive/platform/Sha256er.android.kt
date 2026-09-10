package com.linan.barezen_drive.platform

import java.security.MessageDigest

actual class Sha256er private constructor(private val md: MessageDigest) {
    actual fun update(bytes: ByteArray) {
        md.update(bytes)
    }

    actual fun digestHex(): String =
        md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    actual companion object {
        actual fun newInstance(): Sha256er = Sha256er(MessageDigest.getInstance("SHA-256"))
    }
}
