package com.linan.barezen_drive.platform

import android.os.SystemClock

actual fun monotonicNowMs(): Long = SystemClock.elapsedRealtime()
