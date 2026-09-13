package com.linan.barezen_drive.files

import com.linan.barezen_drive.db.FoldersTable
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Folder-subtree traversal shared by the folder-delete path and the album
 * pagination path.
 *
 * Both call sites need "every folder id under this root". Doing that with one
 * query per level (or per ancestor) turns a deep tree into a burst of round
 * trips; instead the whole folder set of the account is read once and the walk
 * happens in memory, so the cost is a single SELECT regardless of depth.
 *
 * All functions must be called inside an Exposed transaction.
 */
internal object FileTree {

    /**
     * Folder ids strictly below [rootId] (the root itself is excluded), in
     * breadth-first order. Returns an empty list for a leaf folder and for a
     * [rootId] the account does not own.
     */
    fun descendants(userId: UUID, rootId: UUID): List<UUID> {
        val childrenByParent = childrenIndex(userId)
        val ids = mutableListOf<UUID>()
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            for (child in childrenByParent[queue.removeFirst()].orEmpty()) {
                ids.add(child)
                queue.add(child)
            }
        }
        return ids
    }

    /**
     * [rootId] followed by its descendants, i.e. the full subtree including the
     * root. This is the shape the delete path needs: every row touching the
     * subtree must go, the root folder included.
     */
    fun subtreeWithRoot(userId: UUID, rootId: UUID): List<UUID> =
        listOf(rootId) + descendants(userId, rootId)

    /**
     * parent folder id -> child folder ids, for every folder owned by [userId].
     * Folders at the root level (parent is null) are not keys, which is exactly
     * what the walks need: they start from an explicit root and move downward.
     */
    private fun childrenIndex(userId: UUID): Map<UUID, List<UUID>> {
        val acc = HashMap<UUID, MutableList<UUID>>()
        FoldersTable.selectAll()
            .where { FoldersTable.user eq userId }
            .forEach { row ->
                row[FoldersTable.parent]?.let { parent ->
                    acc.getOrPut(parent) { mutableListOf() }.add(row[FoldersTable.id])
                }
            }
        return acc
    }
}
