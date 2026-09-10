package com.linan.barezen_drive.ui.media

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.linan.barezen_drive.i18n.I18n

private fun Int.pad2() = toString().padStart(2, '0')

/**
 * "2026-09-05T14:30:22.123Z" -> "2026-09-05 14:30" in the device timezone.
 * Falls back to a naive slice when the server value is not parseable.
 */
fun formatDateTime(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull()
        ?: return iso.take(16).replace('T', ' ')
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.year.toString().padStart(4, '0')}-${local.monthNumber.pad2()}-${local.dayOfMonth.pad2()} " +
        "${local.hour.pad2()}:${local.minute.pad2()}"
}

/** Album month bucket label, localized (for example "September 2026"). */
fun formatMonthLabel(year: Int, month: Int): String = I18n.strings.monthLabel(year, month)
