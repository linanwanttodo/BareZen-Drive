// Ported verbatim from Kyant0/AndroidLiquidGlass (Apache-2.0)
// app/src/androidMain/kotlin/com/kyant/backdrop/catalog/utils/Coroutines.kt
// Only the package declaration was adjusted.
package com.linan.barezen_drive.ui.glass.utils

import kotlinx.coroutines.android.awaitFrame

actual suspend fun awaitFrame() {
    awaitFrame()
}
