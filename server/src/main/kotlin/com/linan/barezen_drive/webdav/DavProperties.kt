package com.linan.barezen_drive.webdav

import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One property a client asked for by name, namespace included. */
data class DavProp(val namespace: String, val local: String) {
    companion object {
        const val DAV = "DAV:"
    }
}

/**
 * The two different encodings a name goes through, in one place so they cannot
 * drift:
 *
 *  - [hrefSegment] percent-encodes for the URI. The client resolves the path
 *    from it, so "#" would truncate the href and "?" would start a query string.
 *  - [xmlEscaped] escapes the XML metacharacters and nothing else. displayname
 *    is human-readable text, and percent-encoding it would show the user
 *    "a%20b.txt" in their own file browser.
 */
object DavProperties {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    /**
     * RFC 3986 pchar, over the UTF-8 bytes.
     *
     * Deliberately conservative: it escapes everything outside the unreserved
     * set rather than a hand-picked "reserved but legal here" subset. An
     * over-escaped href still resolves to the same name - the server decodes it
     * back - whereas an under-escaped one addresses a different resource.
     */
    fun hrefSegment(name: String): String = buildString(name.length * 2) {
        for (b in name.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt().toChar()
            if (b >= 0 && ch in UNRESERVED) append(ch) else append('%').append("%02X".format(b))
        }
    }

    /**
     * Escapes text content AND drops the characters XML 1.0 cannot represent.
     *
     * FileService.nameOk refuses C0 controls and DEL at the door; this filter is
     * the second line of defence for rows written before that rule existed. One
     * such name in a listing makes the whole Multi-Status document unparseable,
     * which hides the entire directory rather than the one broken file.
     */
    fun xmlEscaped(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c == '\'' -> append("&apos;")
            c.code < 0x20 || c.code == 0x7F -> Unit
            else -> append(c)
        }
    }

    /** RFC 1123, always GMT - `getlastmodified` has to parse as an HTTP date. */
    private val RFC1123: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    fun httpDate(epochMillis: Long): String = RFC1123.format(Instant.ofEpochMilli(epochMillis))

    private fun iso(epochMillis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

    /**
     * Every property we can answer, in a fixed order so listings diff cleanly.
     *
     * A property that is not in here is reported as not found rather than
     * quietly skipped: `getcontenttype` is the one a client asks for and we do
     * not answer, and a silently ignored request is the "not looked at" case
     * RFC 4918 §9.1 exists to rule out.
     */
    private val SUPPORTED = listOf(
        "resourcetype", "displayname", "getcontentlength",
        "getlastmodified", "creationdate", "getetag",
    )

    /**
     * Properties the resource does not have, as opposed to ones we cannot
     * answer. RFC 4918 §15.5 says a collection *may* carry an etag and §15.4
     * does not define `getcontentlength` for one at all, so a collection
     * answers neither. Reporting them as "not found" would be a lie about the
     * resource: clients key "404 propstat" as "this property does not exist",
     * and would cache a negative result for a collection that is very much
     * alive.
     */
    private fun inapplicable(entry: DavEntry): Set<String> =
        if (entry.collection) setOf("getetag", "getcontentlength") else emptySet()

    /**
     * One `<D:response>` element, written straight to [out] so a large listing
     * never has to exist as one string.
     *
     * An empty [requested] is `allprop`, which RFC 4918 also assigns to an
     * empty `<D:prop/>`; [namesOnly] is the `propname` form, which returns the
     * supported names with no values.
     */
    fun writeResponse(
        out: Writer,
        href: String,
        entry: DavEntry,
        requested: Set<DavProp> = emptySet(),
        namesOnly: Boolean = false,
    ) {
        out.write("<D:response><D:href>")
        out.write(xmlEscaped(href))
        out.write("</D:href>")

        val all = requested.isEmpty()
        fun wanted(local: String) = all || requested.any { it.namespace == DavProp.DAV && it.local == local }

        out.write("<D:propstat><D:prop>")
        writeValue(out, "resourcetype", "<D:collection/>".takeIf { entry.collection }.orEmpty(), namesOnly)
        if (wanted("displayname")) {
            writeValue(out, "displayname", if (namesOnly) "" else xmlEscaped(entry.name), namesOnly)
        }
        if (wanted("getcontentlength") && "getcontentlength" !in inapplicable(entry)) {
            writeValue(out, "getcontentlength", if (namesOnly) "" else entry.size.toString(), namesOnly)
        }
        if (wanted("getlastmodified")) {
            writeValue(out, "getlastmodified", if (namesOnly) "" else httpDate(entry.updatedAt), namesOnly)
        }
        if (wanted("creationdate")) {
            writeValue(out, "creationdate", if (namesOnly) "" else iso(entry.createdAt), namesOnly)
        }
        if (wanted("getetag") && entry.etag != null) {
            writeValue(out, "getetag", if (namesOnly) "" else "\"" + xmlEscaped(entry.etag) + "\"", namesOnly)
        }
        out.write("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")

        // RFC 4918 §9.1: a property that does not exist must be *reported*, or
        // the client cannot tell "no value" from "not looked at" and either
        // caches nothing or treats the whole response as useless. Only a
        // property that was named and that we do not implement lands here -
        // never one we simply omitted because it does not apply to this
        // resource.
        if (!all && !namesOnly) {
            for (prop in requested) {
                if (prop.namespace == DavProp.DAV && prop.local in SUPPORTED) continue
                if (prop.namespace == DavProp.DAV && prop.local in inapplicable(entry)) continue
                out.write("<D:propstat><D:prop>")
                writePropTag(out, prop)
                out.write("</D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>")
            }
        }
        out.write("</D:response>")
    }

    private fun writeValue(out: Writer, name: String, value: String, namesOnly: Boolean) {
        if (namesOnly) {
            out.write("<D:$name/>")
        } else {
            out.write("<D:$name>$value</D:$name>")
        }
    }

    /**
     * The empty element that names a property we are reporting as not found.
     *
     * A non-DAV namespace is declared inline: reporting `{urn:x}foo` as
     * `<D:foo/>` would tell the client a different property is missing.
     */
    private fun writePropTag(out: Writer, prop: DavProp) {
        if (prop.namespace == DavProp.DAV) {
            out.write("<D:${prop.local}/>")
        } else {
            out.write("<X:${prop.local} xmlns:X=\"${xmlEscaped(prop.namespace)}\"/>")
        }
    }
}