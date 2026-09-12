package com.linan.barezen_drive.data.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cross-screen signal that a newer version is available: drives the red dot
 * on the settings tab and the update row in Settings. Set by the startup
 * check; cleared once the user handles the update or the check comes back
 * up to date.
 */
object UpdateBadge {
    private val _available = MutableStateFlow(false)
    val available = _available.asStateFlow()

    fun set(value: Boolean) {
        _available.value = value
    }
}
