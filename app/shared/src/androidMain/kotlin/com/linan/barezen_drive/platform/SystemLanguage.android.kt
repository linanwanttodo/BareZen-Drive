package com.linan.barezen_drive.platform

import java.util.Locale

actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()
