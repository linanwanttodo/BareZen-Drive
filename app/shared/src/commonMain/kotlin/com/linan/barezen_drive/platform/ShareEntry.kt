package com.linan.barezen_drive.platform

/**
 * Share-link entry: the /s/<token> path this app instance was opened with.
 * Web reads window.location at startup; other platforms have no share URLs
 * (mobile browsers hit the SPA directly) and return null.
 */
expect fun initialShareToken(): String?
