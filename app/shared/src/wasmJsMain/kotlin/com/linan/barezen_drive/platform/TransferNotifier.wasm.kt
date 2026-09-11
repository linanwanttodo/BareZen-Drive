package com.linan.barezen_drive.platform

/**
 * The browser has no background notification channel equivalent, so transfer
 * progress stays in-app (the transfer centre screen).
 */
actual object TransferNotifier {
    actual fun showProgress(tag: String, title: String, text: String?, fraction: Float?) = Unit
    actual fun showFinished(tag: String, title: String, text: String, ok: Boolean) = Unit
    actual fun dismiss(tag: String) = Unit
}
