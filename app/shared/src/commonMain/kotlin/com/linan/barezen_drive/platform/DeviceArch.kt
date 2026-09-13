package com.linan.barezen_drive.platform

/**
 * The device's primary ABI, named like Android APK splits (arm64-v8a,
 * armeabi-v7a, x86_64). The update check matches it against the release
 * manifest so each device downloads its own build; "any" where the concept
 * does not apply.
 */
expect fun cpuArch(): String
