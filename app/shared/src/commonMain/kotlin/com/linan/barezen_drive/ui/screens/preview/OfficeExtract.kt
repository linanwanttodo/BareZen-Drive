package com.linan.barezen_drive.ui.screens.preview

/**
 * Minimal OOXML text extraction so it runs on every platform.
 * A docx/xlsx/pptx is a zip whose parts are XML; the readable text lives in
 * known members:
 * - docx: word/document.xml <w:t> runs
 * - xlsx: xl/sharedStrings.xml <t> entries (cell values referencing strings)
 * - pptx: ppt/slides/slideN.xml <a:t> runs (per slide, in slide order)
 * No layout fidelity - paragraphs, table cell text and slide text in order,
 * which is what "see what is inside" needs.
 */

/** Un-escapes the five XML predefined entities; numeric refs are left as-is. */
private fun xmlUnescape(s: String): String = buildString(s.length) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '&') {
            val semi = s.indexOf(';', i)
            if (semi in i + 2..i + 10) {
                when (val ent = s.substring(i + 1, semi)) {
                    "lt" -> append('<')
                    "gt" -> append('>')
                    "amp" -> append('&')
                    "quot" -> append('"')
                    "apos" -> append('\'')
                    else -> {
                        append('&'); append(ent); append(';')
                    }
                }
                i = semi + 1
                continue
            }
        }
        append(c)
        i++
    }
}

/** Concatenates the text of every <tag ...>...</tag> occurrence, in order. */
private fun collectTagText(xml: String, tag: String): List<String> {
    val out = mutableListOf<String>()
    var from = 0
    while (true) {
        val open = xml.indexOf("<$tag", from)
        if (open < 0) break
        val c = xml.getOrNull(open + tag.length + 1)
        // <w:t> / <w:t ...> must not match <w:tab> etc.: the next char after
        // the tag name has to be '>' or whitespace.
        if (c != '>' && c != ' ' && c != '\n' && c != '\t' && c != '/') {
            from = open + tag.length + 1
            continue
        }
        val gt = xml.indexOf('>', open)
        if (gt < 0) break
        if (xml[gt - 1] == '/') { // self-closing, empty text
            from = gt + 1
            continue
        }
        val close = xml.indexOf("</$tag>", gt)
        if (close < 0) break
        out.add(xmlUnescape(xml.substring(gt + 1, close)))
        from = close + tag.length + 2
    }
    return out
}

/**
 * True when this zip's local file header sequence contains the member name.
 * Names are stored uncompressed in central/local headers, so a plain search
 * finds them; we only need presence, not offsets, because inflation below
 * scans for PK\x03\x04 signatures itself.
 */
private fun zipHasEntry(bytes: ByteArray, name: String): Boolean {
    val pat = name.encodeToByteArray()
    outer@ for (i in 0..bytes.size - pat.size) {
        for (j in pat.indices) if (bytes[i + j] != pat[j]) continue@outer
        return true
    }
    return false
}

/**
 * Inflates one zip member by scanning for its local file header (PK\x03\x04),
 * matching the member name, then inflating the following deflate stream.
 * Raw-stored members (compression method 0) are copied verbatim. Good enough
 * for office parts, which are always deflated by every producing tool.
 */
private fun inflateEntry(bytes: ByteArray, name: String): ByteArray? {
    val nameBytes = name.encodeToByteArray()
    var i = 0
    while (i <= bytes.size - 30) {
        if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
            bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte()
        ) {
            val method = readU16(bytes, i + 8)
            val compSize = readU32(bytes, i + 18)
            val nameLen = readU16(bytes, i + 26)
            val extraLen = readU16(bytes, i + 28)
            val dataStart = i + 30 + nameLen + extraLen
            if (nameLen == nameBytes.size &&
                dataStart.toLong() + compSize <= bytes.size &&
                (0 until nameLen).all { bytes[i + 30 + it] == nameBytes[it] }
            ) {
                val data = bytes.copyOfRange(dataStart, dataStart + compSize)
                return if (method == 0) data else OfficeZip.inflateDeflate(data)
            }
            // Header not ours: jump past this record's name/extra so nested
            // signatures inside compressed data are not misread.
            if (dataStart > i) i = dataStart.coerceAtMost(bytes.size) else i += 4
        } else {
            i++
        }
    }
    return null
}

private fun readU16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun readU32(b: ByteArray, off: Int): Int =
    readU16(b, off) or (readU16(b, off + 2) shl 16)

/** Slide XMLs in presentation order: slide1.xml, slide2.xml, ... */
private fun extractPptxSlides(bytes: ByteArray): String? {
    val slides = mutableListOf<String>()
    var n = 1
    while (true) {
        val xml = inflateEntry(bytes, "ppt/slides/slide$n.xml")?.decodeToString() ?: break
        slides.addAll(collectTagText(xml, "a:t"))
        n++
    }
    return slides.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/** Returns readable text, or null when the blob is not a known OOXML doc. */
internal fun extractOfficeText(name: String, bytes: ByteArray): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "docx" -> if (zipHasEntry(bytes, "word/document.xml")) {
            inflateEntry(bytes, "word/document.xml")
                ?.decodeToString()
                ?.let { collectTagText(it, "w:t").joinToString("") }
                ?.takeIf { it.isNotBlank() }
        } else null
        "xlsx" -> if (zipHasEntry(bytes, "xl/sharedStrings.xml")) {
            inflateEntry(bytes, "xl/sharedStrings.xml")
                ?.decodeToString()
                ?.let { collectTagText(it, "t") }
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
        } else null
        "pptx" -> if (zipHasEntry(bytes, "ppt/presentation.xml")) extractPptxSlides(bytes) else null
        else -> null
    }
}
