package com.linan.barezen_drive.platform

/**
 * Human-readable device family name used to organize album uploads
 * ("Web", "Android", ...). Photos from one device land in a same-named
 * subfolder of the dedicated album tree.
 */
expect fun deviceName(): String

/**
 * The folder name earlier builds used for this device (the raw factory model
 * code), or null when it matches the current name. Lets the album tree rename
 * legacy folders instead of forking a second device tree.
 */
expect fun legacyDeviceName(): String?
