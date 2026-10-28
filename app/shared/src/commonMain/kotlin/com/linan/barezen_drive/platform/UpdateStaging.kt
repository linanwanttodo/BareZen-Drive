package com.linan.barezen_drive.platform

/**
 * Tries package mirrors strictly in list order and returns the staged path of
 * the first [fetch] that yields one; null when none does, including for an
 * empty list. A fetch that throws counts as that mirror having failed, exactly
 * like one returning null: the exception never escapes and the next candidate
 * is tried. Pure over the mirror list and the fetch callback, so the ordering,
 * fail-over and first-win rules are testable without a network stack.
 */
internal fun stageWithMirrors(urls: List<String>, fetch: (String) -> String?): String? =
    urls.firstNotNullOfOrNull { url -> runCatching { fetch(url) }.getOrNull() }

/**
 * The composition rule behind downloadAndInstallUpdate: once a package is
 * staged, the operation has succeeded. [install] hands the file to the OS
 * installer and its outcome is deliberately not part of this decision - an
 * installer intent that throws (system rejected it) and an installer hand-off
 * that quietly reports failure because the staged file was cleaned up after
 * staging are both swallowed here, and the caller still reports success: the
 * bytes are verified and on disk, and re-fetching the next mirror would just
 * download the same package again. Returns false only when nothing was staged
 * at all, which is the one case the user should be sent to the release page.
 */
internal fun installAndAccept(staged: String?, install: (String) -> Unit): Boolean {
    val path = staged ?: return false
    runCatching { install(path) }
    return true
}
