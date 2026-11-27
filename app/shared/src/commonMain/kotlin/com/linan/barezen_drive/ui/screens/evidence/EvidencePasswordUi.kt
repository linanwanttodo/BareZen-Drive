package com.linan.barezen_drive.ui.screens.evidence

internal fun canSubmitEvidencePassword(
    password: String,
    creating: Boolean,
    restoringCloud: Boolean,
    confirmation: String,
): Boolean = password.isNotEmpty() && (restoringCloud || !creating || password == confirmation)
