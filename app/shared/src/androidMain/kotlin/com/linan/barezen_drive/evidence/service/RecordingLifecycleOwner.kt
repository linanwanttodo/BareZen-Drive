package com.linan.barezen_drive.evidence.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class RecordingLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() { registry.currentState = Lifecycle.State.STARTED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}
