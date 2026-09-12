package com.linan.barezen_drive.i18n

/**
 * Every user-visible string in the client.
 *
 * Compose code reads the current implementation from [LocalStrings];
 * non-composable code (the API layer, date formatting, coroutine
 * callbacks) reads it from [I18n.strings]. Both always hold the same
 * instance, so a language change is picked up everywhere.
 *
 * Implementations: [StringsEn], [StringsZh].
 */
interface Strings {
    val tabHome: String
    val tabFiles: String
    val tabAlbum: String
    val tabSettings: String
    val actionBack: String
    val toggleTheme: String
    val actionCancel: String
    val actionConfirm: String
    val actionDone: String
    val actionClose: String
    val actionCreate: String
    val actionDelete: String
    val actionRename: String
    val actionMove: String
    val actionUpload: String
    val actionDownload: String
    val actionShare: String
    val actionRetry: String
    val actionSignIn: String
    val actionRegister: String
    val registerAndSignIn: String
    val actionSignOut: String
    val reopen: String
    val loading: String
    val loadFailed: String
    val operationFailed: String
    val operationFailedRetry: String
    val pleaseWait: String
    val measuring: String
    val readingServerStatus: String
    val valueNotSet: String
    val copied: String
    val selected: String
    val fieldUsername: String
    val fieldPassword: String
    val errorEnterUsername: String
    val errorEnterPassword: String
    val errorEnterCredentials: String
    val fieldServerUrl: String
    val fieldServerUrlHint: String
    val serverUrlDeviceHint: String
    val serverUrlWebHint: String
    val accountTitle: String
    val settingsAccount: String
    val accountInfo: String
    val notSignedIn: String
    val passwordPolicyNote: String
    val homeRecent: String
    val seeAll: String
    val noRecentFiles: String
    val noPhotos: String
    val serverStatus: String
    val metricMemory: String
    val metricDiskFree: String
    val statusReachable: String
    val statusUnreachable: String
    val newFolder: String
    val fieldFolderName: String
    val fieldNewName: String
    val renameFile: String
    val renameFolder: String
    val deleteFile: String
    val deleteFolder: String
    val downloadFile: String
    val moveHere: String
    val rootFolder: String
    val folderEmpty: String
    val moreActions: String
    val viewGrid: String
    val viewList: String
    val creating: String
    val createFailed: String
    val renameFailed: String
    val moveFailed: String
    val deleteFailed: String
    val downloadFailed: String
    val uploadFailedRetry: String
    val uploadingGeneric: String
    fun uploading(fileName: String): String
    fun verifyingFile(fileName: String): String
    fun finalizingFile(fileName: String): String
    fun uploadFailedNamed(name: String): String
    fun confirmDelete(name: String): String
    fun moveToTitle(fileName: String): String
    val pickFromGallery: String
    fun monthLabel(year: Int, month: Int): String
    val imageLoadFailed: String
    val previewUnsupported: String
    val previewDownloadHint: String
    val textPreviewTruncated: String
    val mediaVideo: String
    val mediaAudio: String
    val mediaTallImage: String
    val pageTurn: String
    fun pageNumber(page: Int): String
    val nativePlayerHint: String
    fun openedInNewTab(label: String): String
    val fetchLinkFailed: String
    val playbackUrlUnavailable: String
    val downloadingPdf: String
    val pdfLoadFailed: String
    val pdfCannotOpen: String
    val shareManagement: String
    val shareManagerSubtitle: String
    val noShareLinks: String
    val createShareLink: String
    val actionRevokeLink: String
    val copyLink: String
    val copyFailedManual: String
    val existingLink: String
    val shareLinkCreated: String
    val shareLinkShownOnce: String
    val fieldExpiry: String
    val expiryNever: String
    val expiryOneDay: String
    val expirySevenDays: String
    val expiryThirtyDays: String
    val neverExpires: String
    fun expiresAt(dateTime: String): String
    fun shareStats(views: String, downloads: String): String
    fun shareStatsSuffix(views: String, downloads: String): String
    val sharedFiles: String
    val shareFile: String
    val shareFolder: String
    val shareLinkInvalid: String
    val askSharerForNewLink: String
    val goToLogin: String
    fun updatedAt(dateTime: String): String
    val settingsAppearance: String
    val settingsTheme: String
    val settingsAccentColor: String
    val themeModeHint: String
    val themeLight: String
    val themeDark: String
    val themeSystem: String
    val dynamicColor: String
    val monetHint: String
    val dynamicColorOverridesAccent: String
    val pickAccentColor: String
    val settingsWallpaper: String
    val wallpaperBackground: String
    val wallpaperHint: String
    val pickWallpaper: String
    val clearWallpaper: String
    val thumbnailAutoCompressHint: String
    val glassBottomBar: String
    val glassBottomBarOffHint: String
    val blurEffect: String
    val surfaceOpacity: String
    val panelAlphaHint: String
    val settingsAbout: String
    val openSourceNotices: String
    val openSourceComponentsTitle: String
    val openSourceIntro: String
    val licenseNotice: String
    val copyrightHolder: String
    val openGitHubProfile: String
    val checkForUpdates: String
    val checkFailedRetry: String
    fun currentVersion(version: String): String
    fun upToDate(version: String): String
    fun updateAvailableVersion(tag: String): String
    val updateAvailable: String
    val openReleasePage: String
    val actionReload: String
    val actionDownloadUpdate: String
    fun reloadToUpdate(version: String): String
    fun downloadUpdateHint(version: String): String
    val updateDownloading: String
    fun webUpdateViaServer(version: String): String
    val networkTimeout: String
    val networkCannotConnect: String
    val settingsLanguage: String
    val languageModeHint: String
    val languageSystem: String
    val languageChinese: String
    val languageEnglish: String
    val settingsServer: String
    val pickUploadLocation: String
    val actionInfo: String
    val infoName: String
    val infoSize: String
    val infoModified: String
    val allDevices: String
    val allMedia: String
    val selectAll: String
    val userManagement: String
    val userManagementHint: String
    val you: String
    fun fileCount(n: Long): String
    val deleteUser: String
    fun deleteUserWarning(name: String): String
    val deleteUserSelfWarning: String
    val viewWaterfall: String
    val viewUniform: String
    val viewDated: String
    val allPhotos: String
    val backToCollections: String
    val transfers: String
    val tabUploading: String
    val tabDownloading: String
    val tabDone: String
    val transferDone: String
    val transferFailed: String
    val noTransfers: String
    val clearFinished: String
    val albumAutoSync: String
    val albumAutoSyncHint: String
    val syncWifiOnly: String
    val syncWifiOnlyHint: String
    val syncNow: String
    val syncStarted: String
    val openRegistration: String
    val openRegistrationOnHint: String
    val openRegistrationOffHint: String
    val switchingRegistration: String
}
