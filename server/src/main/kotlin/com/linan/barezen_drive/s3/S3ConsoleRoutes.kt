package com.linan.barezen_drive.s3

import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.S3BucketDto
import com.linan.barezen_drive.core.dto.S3BucketsResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Authenticated management view of the S3 surface: every top-level folder with
 * its bucket-name validity, recursive object count and total bytes. The S3
 * protocol itself only lists valid buckets; this endpoint also shows the
 * folders that are NOT buckets so the console can explain why.
 *
 * Stats are computed in one transaction: load the user's whole folder tree and
 * all live file rows, then aggregate in memory. The same single-SELECT walk
 * that FileTree uses for deletion, so a deep tree does not turn into a burst of
 * round trips.
 */
fun Route.s3ConsoleRoutes() {
    route("/api/s3") {
        get("/buckets") {
            val uid = call.userId
            val response = withContext(Dispatchers.IO) {
                transaction(DatabaseFactory.db) {
                    val folders = FoldersTable.selectAll()
                        .where { FoldersTable.user eq uid }
                        .map { row ->
                            FolderRow(
                                id = row[FoldersTable.id],
                                parent = row[FoldersTable.parent],
                                name = row[FoldersTable.name],
                                createdAt = row[FoldersTable.createdAt],
                            )
                        }
                    val childrenByParent = HashMap<UUID, MutableList<UUID>>()
                    for (f in folders) {
                        f.parent?.let { p -> childrenByParent.getOrPut(p) { mutableListOf() }.add(f.id) }
                    }
                    val files = FilesTable.select(FilesTable.folder, FilesTable.size)
                        .where { (FilesTable.user eq uid) and (FilesTable.deletedAt eq 0L) }
                        .map { it[FilesTable.folder] to it[FilesTable.size] }

                    val countByFolder = HashMap<UUID, Long>()
                    val bytesByFolder = HashMap<UUID, Long>()
                    var rootFiles = 0L
                    for ((folder, size) in files) {
                        if (folder == null) {
                            rootFiles++
                        } else {
                            countByFolder[folder] = (countByFolder[folder] ?: 0L) + 1
                            bytesByFolder[folder] = (bytesByFolder[folder] ?: 0L) + size
                        }
                    }

                    fun subtreeStats(rootId: UUID): Pair<Long, Long> {
                        var count = 0L
                        var bytes = 0L
                        val queue = ArrayDeque(listOf(rootId))
                        while (queue.isNotEmpty()) {
                            val id = queue.removeFirst()
                            count += countByFolder[id] ?: 0L
                            bytes += bytesByFolder[id] ?: 0L
                            childrenByParent[id]?.let { queue.addAll(it) }
                        }
                        return count to bytes
                    }

                    val topLevel = folders.filter { it.parent == null }
                        .sortedBy { it.name.lowercase() }
                    val buckets = topLevel.map { f ->
                        val (count, bytes) = subtreeStats(f.id)
                        S3BucketDto(
                            folderId = f.id.toString(),
                            name = f.name,
                            createdAt = f.createdAt,
                            objectCount = count,
                            totalBytes = bytes,
                            s3Valid = s3BucketNameOk(f.name),
                        )
                    }
                    S3BucketsResponse(buckets, rootFiles)
                }
            }
            call.respond(response)
        }
    }
}

private data class FolderRow(val id: UUID, val parent: UUID?, val name: String, val createdAt: Long)