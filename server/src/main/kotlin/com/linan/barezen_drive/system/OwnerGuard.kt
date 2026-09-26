package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.UsersTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The instance owner is the first account ever created: earliest createdAt,
 * id as the tie-break. That matches the install wizard's seeded bootstrap
 * admin (BOOTSTRAP_ADMIN_USER) and whoever signed up first on a fresh
 * instance. Derived on the fly - no schema change - and it stays correct
 * when extra accounts were let in through an open registration window:
 * they are guests, never administrators.
 */
internal fun ownerId(): UUID? = transaction(DatabaseFactory.db) { ownerAccountId() }

/**
 * The one definition of "owner": the earliest account, ties broken by id so the
 * answer cannot flip between two accounts created in the same millisecond.
 * Callers already run inside (or open) a transaction - this only reads.
 */
internal fun ownerAccountId(): UUID? = UsersTable.selectAll()
    .orderBy(UsersTable.createdAt to SortOrder.ASC, UsersTable.id to SortOrder.ASC)
    .limit(1)
    .firstOrNull()?.get(UsersTable.id)

/**
 * Guards the owner-only endpoints: user management and the registration toggle.
 *
 * `suspend` because ownerId() opens a blocking JDBC transaction; every caller
 * runs on the request pipeline, so without the IO hop a slow query here would
 * occupy the event-loop thread that also has to serve everybody else.
 */
internal suspend fun requireOwner(userId: UUID) {
    val owner = withContext(Dispatchers.IO) { ownerId() }
    if (userId != owner) throw ApiException.forbidden("仅实例所有者可执行此操作")
}
