package com.linan.barezen_drive.platform

/**
 * BCP-47 tag of the device language, for example "en-US" or "zh-CN". Read
 * once at startup to pick the initial UI language when the user has not
 * chosen one explicitly.
 */
expect fun systemLanguageTag(): String
