package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UsersTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * One row of a WebDAV collection listing.
 *
 * Deliberately not a DTO: a 100k-file directory must never materialise 100k of
 * anything, so this carries only what a multistatus entry needs, and the
 * listing walks it in keyset pages. [id] is the page cursor, not something ever
 * written out.
 */
data class DavEntry(
    val id: UUID,
    val name: String,
    val collection: Boolean,
    val size: Long,
    val updatedAt: Long,
    val createdAt: Long,
    /** Null for collections: they have no content, so there is nothing to tag. */
    val etag: String?,
)

/**
 * Keyset paging over one collection, for PROPFIND.
 *
 * `FileService.contents` cannot serve this. It has no cursor for folders at
 * all, and with a null limit it maps the whole ordered file set to DTOs - and a
 * mount always needs the complete listing, because the client caches it. That
 * made a 100k-file directory materialise 100k DTOs in one answer, the same
 * unboundedness `listTrash` used to have.
 *
 * The order is `(lowercase(name), id)` - the same order the album and contents
 * pages use, so a client sees one listing order across the whole app. Folders
 * and files come out of two different tables, so each is keyed separately and
 * the two page-sized runs are merged in memory: one page of the merged list can
 * never straddle the sort, because the merge happens after each side's SQL
 * keyset has already bounded it.
 */
object DavListing {
    /** Rows per page. A mount asks for the whole listing, so this only bounds
     *  what is held in memory at once, not what is returned. */
    const val PAGE = 500

    /**
     * The next [limit] entries after ([afterName], [afterId]) in the listing
     * order; the first page when the cursor is null. A half-supplied cursor is
     * treated as no cursor at all: a malformed cursor that skipped rows would
     * hide files from a listing the client is about to cache.
     *
     * One transaction, so each page is one consistent read.
     */
    fun page(
        userId: UUID,
        parent: UUID?,
        afterName: String?,
        afterId: UUID?,
        limit: Int = PAGE,
    ): List<DavEntry> {
        require(limit > 0) { "a page must hold at least one row" }
        val cursor = if (afterName != null && afterId != null) Cursor(afterName.lowercase(), afterId) else null
        return transaction(DatabaseFactory.db) {
            val folders = scan(cursor, limit) { f, i, t -> folderBatch(userId, parent, f, i, t) }
            val files = scan(cursor, limit) { f, i, t -> fileBatch(userId, parent, f, i, t) }
            (folders + files)
                // The cursor test again on the merged list, where it decides the
                // page boundary: the merge is the only place that knows which of
                // the two tables' rows comes first overall.
                .filter { after(it, cursor) }
                .sortedWith(ORDER)
                .take(limit)
        }
    }

    /** The cursor is (folded name, id). */
    private data class Cursor(val folded: String, val id: UUID)

    private val ORDER = Comparator<DavEntry> { a, b ->
        val byName = a.name.lowercase().compareTo(b.name.lowercase())
        if (byName != 0) byName else compareIds(a.id, b.id)
    }

    /**
     * The explicit in-memory half of the cursor test: strictly after the
     * (folded name, id) the caller last saw.
     *
     * An earlier draft expressed this as a `dropWhile` over the merged list,
     * which silently swallowed the row *after* the cursor whenever the cursor
     * sat on a case-folded name (`beta.txt` / `BETA.TXT`): the folded compare
     * said "equal", the loop stopped, and the second row was never listed at
     * all. Comparing both keys is the only reading that terminates.
     */
    private fun after(entry: DavEntry, cursor: Cursor?): Boolean {
        if (cursor == null) return true
        val folded = entry.name.lowercase()
        return folded > cursor.folded ||
            (folded == cursor.folded && compareIds(entry.id, cursor.id) > 0)
    }

    /**
     * Unsigned, most significant half first: the order both H2 and PostgreSQL
     * give a `uuid` column.
     *
     * `UUID.compareTo` is signed, so it disagrees with the database for any id
     * whose top bit is set - about half of them. Where the two disagree, the
     * keyset says a row is before the cursor while the in-memory test says it is
     * after, the row is filtered out of the page it belongs to, and the walk
     * ends with that file missing from the listing.
     */
    private fun compareIds(a: UUID, b: UUID): Int {
        val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
        return if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
    }

    /**
     * Read one table until [limit] rows are past the cursor, or it runs out.
     *
     * The loop is not defensive padding. The SQL keyset is a strict `(folded
     * name, id)` comparison while the in-memory one folds the name with Kotlin's
     * `lowercase`, and the two disagree about rows whose names fold together -
     * so a `LIMIT n` can be spent entirely on rows the in-memory test then
     * rejects, and the page comes back short (or empty, ending the walk with
     * rows still unread). Each round therefore advances this table's own bound
     * to the last row it read, and stops as soon as [limit] rows survive.
     */
    private fun scan(
        cursor: Cursor?,
        limit: Int,
        batch: (foldBound: String?, idBound: UUID?, take: Int) -> List<DavEntry>,
    ): List<DavEntry> {
        val kept = ArrayList<DavEntry>(limit)
        var foldBound = cursor?.folded
        var idBound = cursor?.id
        while (kept.size < limit) {
            val rows = batch(foldBound, idBound, limit - kept.size)
            if (rows.isEmpty()) break
            for (row in rows) {
                val folded = row.name.lowercase()
                if (foldBound == null ||
                    folded > foldBound ||
                    (folded == foldBound && compareIds(row.id, idBound!!) > 0)
                ) {
                    kept += row
                }
            }
            val last = rows.last()
            foldBound = last.name.lowercase()
            idBound = last.id
        }
        return kept
    }

    /** `IS NULL` for the account root, `= ?` for a real folder. */
    private fun scoped(column: Column<UUID?>, parent: UUID?): Op<Boolean> =
        Op.build { if (parent == null) column.isNull() else column.eq(parent) }

    /**
     * The account root as a listing entry.
     *
     * It has no folder row, so its dates come from the account: epoch zero would
     * reach clients as 1 January 1970, which reads as "this drive has never been
     * written to" on a drive that is full.
     */
    fun root(userId: UUID): DavEntry = transaction(DatabaseFactory.db) {
        val created = UsersTable.select(UsersTable.createdAt)
            .where { UsersTable.id eq userId }.first()[UsersTable.createdAt]
        // The all-zero id stands in for "no row"; the root is never a page cursor.
        DavEntry(UUID(0, 0), "", true, 0L, created, created, null)
    }

    /** One live file as a listing entry; null when it is not this user's, or trashed. */
    fun blob(userId: UUID, fileId: UUID): DavEntry? = transaction(DatabaseFactory.db) {
        FilesTable.select(
            FilesTable.id, FilesTable.name, FilesTable.size,
            FilesTable.createdAt, FilesTable.updatedAt, FilesTable.sha256,
        ).where {
            (FilesTable.id eq fileId) and (FilesTable.user eq userId) and (FilesTable.deletedAt eq 0L)
        }.firstOrNull()?.toFileEntry()
    }

    /** One folder as a listing entry; null when it is not this user's. */
    fun collection(userId: UUID, folderId: UUID): DavEntry? = transaction(DatabaseFactory.db) {
        FoldersTable.select(FoldersTable.id, FoldersTable.name, FoldersTable.createdAt, FoldersTable.updatedAt)
            .where { (FoldersTable.id eq folderId) and (FoldersTable.user eq userId) }
            .firstOrNull()?.toFolderEntry()
    }

    private fun ResultRow.toFolderEntry() = DavEntry(
        id = this[FoldersTable.id],
        name = this[FoldersTable.name],
        collection = true,
        size = 0L,
        updatedAt = this[FoldersTable.updatedAt],
        createdAt = this[FoldersTable.createdAt],
        etag = null,
    )

    private fun ResultRow.toFileEntry() = DavEntry(
        id = this[FilesTable.id],
        name = this[FilesTable.name],
        collection = false,
        size = this[FilesTable.size],
        updatedAt = this[FilesTable.updatedAt],
        createdAt = this[FilesTable.createdAt],
        // The content digest, which is the last path segment of the blob key
        // too: content-addressed storage makes it a strong ETag for free, and
        // taking the key itself would leak the storage layout
        // (blobs/ab/cd/<sha>) to every client.
        etag = this[FilesTable.sha256],
    )

    private fun folderBatch(
        userId: UUID,
        parent: UUID?,
        foldBound: String?,
        idBound: UUID?,
        take: Int,
    ): List<DavEntry> {
        var q = FoldersTable.select(FoldersTable.id, FoldersTable.name, FoldersTable.createdAt, FoldersTable.updatedAt)
            .where { (FoldersTable.user eq userId) and scoped(FoldersTable.parent, parent) }
        if (foldBound != null && idBound != null) {
            val folded = FoldersTable.name.lowerCase()
            q = q.andWhere {
                (folded greater foldBound) or ((folded eq foldBound) and (FoldersTable.id greater idBound))
            }
        }
        return q.orderBy(FoldersTable.name.lowerCase() to SortOrder.ASC, FoldersTable.id to SortOrder.ASC)
            .limit(take)
            .map { it.toFolderEntry() }
    }

    private fun fileBatch(
        userId: UUID,
        parent: UUID?,
        foldBound: String?,
        idBound: UUID?,
        take: Int,
    ): List<DavEntry> {
        var q = FilesTable.select(
            FilesTable.id, FilesTable.name, FilesTable.size,
            FilesTable.createdAt, FilesTable.updatedAt, FilesTable.sha256,
        ).where {
            (FilesTable.user eq userId) and scoped(FilesTable.folder, parent) and (FilesTable.deletedAt eq 0L)
        }
        if (foldBound != null && idBound != null) {
            val folded = FilesTable.name.lowerCase()
            q = q.andWhere {
                (folded greater foldBound) or ((folded eq foldBound) and (FilesTable.id greater idBound))
            }
        }
        return q.orderBy(FilesTable.name.lowerCase() to SortOrder.ASC, FilesTable.id to SortOrder.ASC)
            .limit(take)
            .map { it.toFileEntry() }
    }
}