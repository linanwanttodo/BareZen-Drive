package com.linan.barezen_drive.platform

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Notification-tap deep link: tapping a transfer/backup notification asks the
 * app to open the transfer centre. Android raises the flag from the activity
 * intent; the shared App() consumes it by pushing the screen. Kept in common
 * so the navigation stack (which lives there) can own the response without a
 * platform callback hook.
 */
object TransferDeepLink {
    val openTransfers = MutableStateFlow(false)

    fun request() {
        openTransfers.value = true
    }

    fun consume() {
        openTransfers.value = false
    }
}
