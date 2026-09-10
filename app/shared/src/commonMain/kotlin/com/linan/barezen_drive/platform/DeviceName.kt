package com.linan.barezen_drive.platform

/**
 * Human-readable device family name used to organize album uploads
 * ("Web", "Android", ...). Photos from one device land in a same-named
 * subfolder of the dedicated album tree.
 */
expect fun deviceName(): String
