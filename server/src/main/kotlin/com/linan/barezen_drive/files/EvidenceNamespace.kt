package com.linan.barezen_drive.files

import com.linan.barezen_drive.db.FoldersTable
import org.jetbrains.exposed.sql.*
import java.util.UUID

internal const val EVIDENCE_ROOT_NAME = "取证"

internal fun hasEvidenceRoot(userId: UUID): Boolean =
    !FoldersTable.selectAll()
        .where { (FoldersTable.user eq userId) and (FoldersTable.parent.isNull()) and (FoldersTable.name eq EVIDENCE_ROOT_NAME) }
        .empty()

internal class OutsideEvidenceNamespace(
    private val userId: UUID,
    private val folder: Expression<*>,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("(")
        folder.toQueryBuilder(queryBuilder)
        queryBuilder.append(" IS NULL OR ")
        folder.toQueryBuilder(queryBuilder)
        queryBuilder.append(" NOT IN (WITH RECURSIVE evidence(id) AS (SELECT id FROM folders WHERE user_id = ")
        queryBuilder.registerArgument(FoldersTable.user.columnType, userId)
        queryBuilder.append(" AND parent_id IS NULL AND name = ")
        queryBuilder.registerArgument(FoldersTable.name.columnType, EVIDENCE_ROOT_NAME)
        queryBuilder.append(" UNION SELECT f.id FROM folders f JOIN evidence e ON f.parent_id = e.id) SELECT id FROM evidence))")
    }
}
