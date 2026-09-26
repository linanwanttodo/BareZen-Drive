package com.linan.barezen_drive.files

import io.ktor.http.*

/** How a stored file is allowed back out: rendered by the browser, or downloaded. */
internal data class SafeContent(
    val type: ContentType,
    /** null = serve inline (no Content-Disposition). */
    val disposition: String?,
)

/** Rendered by the browser's own viewer, never executed. */
private val INLINE_EXACT = setOf("application/pdf")

private val INLINE_PREFIXES = listOf("image/", "video/", "audio/")

/**
 * Media-looking types that are script containers. `image/svg+xml` is the classic
 * one - it is XML, so `<script>` inside runs in the serving origin.
 */
private val NEVER_INLINE = setOf("image/svg+xml", "image/svg")

/**
 * Extensions a browser will execute no matter what the declared type says, so a
 * file called `evil.html` never gets an inline response even if the client
 * claimed it was a PNG. The declared type comes from the client, the name does
 * too - but the name is the thing a human double-clicks, so it is the more
 * honest signal when the two disagree.
 */
private val SCRIPTABLE_EXTENSIONS = setOf(
    "html", "htm", "xhtml", "xht", "shtml", "hta", "svg", "svgz", "xml", "xsl", "xslt",
    "js", "mjs", "cjs", "swf", "jar", "php", "asp", "aspx", "jsp", "vbs", "vbscript",
)

/**
 * Decides the response type for a stored file.
 *
 * Uploads are served back from the same origin as the app, so the stored
 * Content-Type is attacker-controlled: whoever can upload `evil.html` would
 * otherwise get script execution on the netdisk origin, where the app keeps the
 * access and refresh tokens in localStorage. Everything a browser can execute
 * is therefore served as `application/octet-stream` + `Content-Disposition:
 * attachment`, which hands the file to the download manager instead of a
 * rendering context. Media and PDF stay inline because the app depends on it -
 * the image viewer, video playback and the PDF preview (which opens the signed
 * URL in a new tab and lets the browser render it) all need an inline response.
 *
 * This is the second of two layers; `X-Content-Type-Options: nosniff` (global,
 * see Application.module) covers the case where a declared type slips through
 * as something inert-looking but executable.
 */
internal fun safeFileContent(declaredMime: String?, fileName: String): SafeContent {
    val declared = declaredMime?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    // Compare on the bare type/subtype so a `; charset=utf-8` or a parameter
    // cannot be used to dodge the checks.
    val bare = declared?.let { "${it.contentType}/${it.contentSubtype}".lowercase() }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val inline = bare != null &&
        ext !in SCRIPTABLE_EXTENSIONS &&
        bare !in NEVER_INLINE &&
        (bare in INLINE_EXACT || INLINE_PREFIXES.any { bare.startsWith(it) })
    return if (inline) {
        SafeContent(declared!!, null)
    } else {
        SafeContent(ContentType.Application.OctetStream, attachmentDisposition(fileName))
    }
}

/**
 * `attachment` plus the original name in both encodings: the ASCII form is what
 * old clients read, `filename*` (RFC 5987) carries the real name including
 * Chinese characters. Control characters are dropped rather than escaped - they
 * have no business in a filename and CRLF here would be header injection.
 */
private fun attachmentDisposition(name: String): String {
    val ascii = name
        .map { if (it.isLetterOrDigit() && it.code < 128 || it in ". _-") it else '_' }
        .joinToString("")
        .ifEmpty { "download" }
        .take(120)
    val encoded = name.toByteArray().joinToString("") { b ->
        val c = b.toInt() and 0xff
        if (c in 0x20..0x7E && c != 0x25) c.toChar().toString()
        else "%" + c.toString(16).uppercase().padStart(2, '0')
    }
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}
