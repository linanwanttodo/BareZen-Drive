package com.linan.barezen_drive.platform

/**
 * Progress in the system notification shade - the netdisk-style "uploading
 * 42%" entry. Android posts a real ongoing notification per transfer; web has
 * no equivalent background channel, so it is a no-op there.
 */
expect object TransferNotifier {
    /** Creates/updates an ongoing progress entry. [fraction] null = indeterminate. */
    fun showProgress(tag: String, title: String, text: String?, fraction: Float?)

    /** Final state: a short-lived success/failure entry (auto-cancellable). */
    fun showFinished(tag: String, title: String, text: String, ok: Boolean)

    /** Removes the entry (e.g. when the finished list is cleared). */
    fun dismiss(tag: String)
}
