package com.linan.barezen_drive.platform

/**
 * Copies text to the system clipboard; returns false when the platform
 * refused (browser permission, insecure context without fallback).
 * Suspend because the web Clipboard API is promise-based.
 */
expect suspend fun copyToClipboard(text: String): Boolean
