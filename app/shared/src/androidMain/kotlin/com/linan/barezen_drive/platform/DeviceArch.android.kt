package com.linan.barezen_drive.platform

import android.os.Build

actual fun cpuArch(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "any"
