package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable

/**
 * Photo-album picker: system photo picker on Android (no permission needed,
 * sees device albums incl. cloud-backed ones), image file input on web.
 * Returns picked images as [PickedFile]s ready for the upload pipeline.
 */
@Composable
expect fun rememberImagePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit
