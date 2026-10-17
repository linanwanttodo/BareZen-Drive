package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * filename, and refusing to list "50%.txt" helps nobody. A well-formed `%25`
 * means the same character and names a file called "%".
 *
 * The decoded name is then checked for a lone surrogate. [percentDecode] refuses
 * one because it runs only on a segment that contains a percent sign - without
 * this second check the same character arriving in a plain `/xxx.jpg` path would
 * slip through and be written to a row whose name no client can ask for again.
 */
fun davSegments(rawPath: String): List<String>? {
    val parts = rawPath.split('/').filter { it.isNotEmpty() }
    val out = ArrayList<String>(parts.size)
    for (part in parts) {
        val decoded = percentDecode(part) ?: return null
        if (decoded.contains('/') || decoded.isEmpty()) return null
        if (hasUnpairedSurrogate(decoded)) return null
        out += decoded
    }
    return out
}

/**
 * True when a surrogate in the string is not half of a well-formed pair.
 *
 * A name carrying one cannot be stored: the JDBC driver turns it into a `?` on
 * the way to PostgreSQL, so the row would get a name no client can ask for
 * again. Refusing it here is the same verdict [percentDecode] already gives an
 * escaped one - that check alone does not cover it, because decoding only runs
 * on a segment that contains a percent sign.
 */
private fun hasUnpairedSurrogate(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.isHighSurrogate() -> {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            }
            c.isLowSurrogate() -> return true
            else -> i++
        }
    }
    return false
}

private fun percentDecode(s: String): String? {
    if ('%' !in s) return s
    // A raw non-ASCII character occupies up to three UTF-8 bytes, so a buffer
    // sized by character count is too small for the very paths this branch exists
    // to accept. Three per character also covers a surrogate pair: two characters
    // encoding to four bytes.
    val bytes = ByteArray(s.length * 3)
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
                // treat the character as its own UTF-8 bytes rather than 400. A
                // surrogate pair is one code point and must be encoded as a pair:
                // each half on its own would be replaced by '?', turning the name
                // into a different one. An unpaired half names nothing at all, so
                // it is refused here rather than guessed at.
                val paired = c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
                if (c.isSurrogate() && !paired) return null
                val encoded = s.substring(i, i + if (paired) 2 else 1).toByteArray(StandardCharsets.UTF_8)
                encoded.copyInto(bytes, n)
                n += encoded.size
                i += if (paired) 2 else 1
            } else {
                bytes[n++] = c.code.toByte()
                i++
            }
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
 *
 * The dispatch belongs to the helper rather than to its many call sites because
 * this walk is `depth + 1` blocking JDBC round trips, and a DAV route runs on the
 * request's event-loop thread: parking that thread stalls every other connection
 * the process holds, which is what the rest of this surface is written around.
 */
internal suspend fun resolveDavPath(userId: UUID, segments: List<String>): DavTarget =
    withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext DavTarget.Collection(null)
        transaction(DatabaseFactory.db) {
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