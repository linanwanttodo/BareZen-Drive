package com.linan.barezen_drive.platform

actual class Sha256er private constructor(private val impl: PureSha256) {
    actual fun update(bytes: ByteArray) {
        impl.update(bytes)
    }

    actual fun digestHex(): String = impl.digestHex()

    actual companion object {
        actual fun newInstance(): Sha256er = Sha256er(PureSha256())
    }
}
