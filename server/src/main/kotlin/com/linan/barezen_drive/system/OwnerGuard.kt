package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.UsersTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * The instance owner is the first account ever created: earliest createdAt,
 * id as the tie-break. That matches the install wizard's seeded bootstrap
 * admin (BOOTSTRAP_ADMIN_USER) and whoever signed up first on a fresh
 * instance. Derived on the fly - no schema change - and it stays correct
 * when extra accounts were let in through an open registration window:
 * they are guests, never administrators.
 */
internal fun ownerId(): UUID? = transaction(DatabaseFactory.db) {
    UsersTable.selectAll()
        .orderBy(UsersTable.createdAt to SortOrder.ASC, UsersTable.id to SortOrder.ASC)
        .limit(1)
        .firstOrNull()?.get(UsersTable.id)
}

/** Guards the owner-only endpoints: user management and the registration toggle. */
internal fun requireOwner(userId: UUID) {
    if (userId != ownerId()) throw ApiException.forbidden("仅实例所有者可执行此操作")
}
