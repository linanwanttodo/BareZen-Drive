package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/** What a WebDAV path points at. */
sealed interface DavTarget {
    /** A collection; [id] is null for the account root, which has no folder row. */
    data class Collection(val id: UUID?) : DavTarget

    data class Blob(val id: UUID) : DavTarget

    data object Missing : DavTarget
}

/**
 * The path segments of a `/dav` URL, percent-decoded.
 *
 * A `%2F` decodes into a slash, and no name may contain one, so a segment that
 * decodes that way is not a name: it is an attempt to smuggle a path separator
 * past the walk. It answers null, and so does one whose bytes are not valid
 * UTF-8 - guessing there would resolve to a different name than the client
 * asked for.
 *
 * A malformed escape (`%`, `%z`) is a different matter and is kept literal, the
 * same call StaticWeb makes: plenty of clients emit an unescaped `%` in a
 * filename, and refusing to list "50%.txt" helps nobody.
 */
fun davSegments(rawPath: String): List<String>? {
    val parts = rawPath.split('/').filter { it.isNotEmpty() }
    val out = ArrayList<String>(parts.size)
    for (part in parts) {
        val decoded = percentDecode(part) ?: return null
        if (decoded.contains('/') || decoded.isEmpty()) return null
        out += decoded
    }
    return out
}

private fun percentDecode(s: String): String? {
    if ('%' !in s) return s
    val bytes = ByteArray(s.length)
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length &&
            Character.digit(s[i + 1], 16) >= 0 && Character.digit(s[i + 2], 16) >= 0
        ) {
            bytes[n++] = ((Character.digit(s[i + 1], 16) shl 4) or Character.digit(s[i + 2], 16)).toByte()
            i += 3
        } else {
            if (c.code > 0x7F) {
                // Raw non-ASCII in a URL is illegal but clients send it anyway;
                // treat the character as its own UTF-8 bytes rather than 400.
                val encoded = c.toString().toByteArray(StandardCharsets.UTF_8)
                encoded.copyInto(bytes, n)
                n += encoded.size
            } else {
                bytes[n++] = c.code.toByte()
            }
            i++
        }
    }
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, n))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * Resolve a path by walking one folder row per segment.
 *
 * Deliberately not a prefix match over the whole table: a per-segment walk is
 * `depth` indexed lookups, and it is the only way a name like "a/b" can never
 * be confused with a folder "a" containing a folder "b". Every step is scoped
 * to the owner, so another account's path resolves to [DavTarget.Missing] and
 * answers 404 rather than confirming that it exists.
 *
 * [segments] must already be decoded - see [davSegments].
 */
fun resolveDavPath(userId: UUID, segments: List<String>): DavTarget {
    if (segments.isEmpty()) return DavTarget.Collection(null)
    return transaction(DatabaseFactory.db) {
        var parent: UUID? = null
        for (i in 0 until segments.size - 1) {
            val next = FoldersTable.select(FoldersTable.id).where {
                (FoldersTable.user eq userId) and (FoldersTable.name eq segments[i]) and
                    if (parent == null) FoldersTable.parent.isNull() else (FoldersTable.parent eq parent)
            }.firstOrNull() ?: return@transaction DavTarget.Missing
            parent = next[FoldersTable.id]
        }
        val last = segments.last()
        FoldersTable.select(FoldersTable.id).where {
            (FoldersTable.user eq userId) and (FoldersTable.name eq last) and
                if (parent == null) FoldersTable.parent.isNull() else (FoldersTable.parent eq parent)
        }.firstOrNull()?.let { return@transaction DavTarget.Collection(it[FoldersTable.id]) }
        FilesTable.select(FilesTable.id).where {
            (FilesTable.user eq userId) and (FilesTable.name eq last) and (FilesTable.deletedAt eq 0L) and
                if (parent == null) FilesTable.folder.isNull() else (FilesTable.folder eq parent)
        }.firstOrNull()?.let { return@transaction DavTarget.Blob(it[FilesTable.id]) }
        DavTarget.Missing
    }
}