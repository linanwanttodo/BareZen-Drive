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
 * The instance owner: the account that administers user management, the
 * registration switch and the host statistics.
 *
 * The role is FIXED, not re-derived. It is decided once - when the first
 * account is created, or on the first owner-scoped read of an instance that
 * predates the setting - and is then read from the settings table. Two reasons:
 *
 *  - An answer about the past does not change. "Earliest createdAt, id as the
 *    tie-break" was recomputed on every request, so the owner could change if
 *    the first account was ever deleted, and a same-millisecond pair of
 *    registrations was decided by which random UUID happened to sort first.
 *  - It is asked on the request path. That query sorted the whole users table by
 *    an unindexed column for every management request; the answer is now held
 *    in process and pinned in the settings table, so the hot path is a field
 *    read.
 */
@Volatile
private var cachedOwnerId: UUID? = null

private val ownerCacheLock = Any()

/**
 * The owner, opening its own transaction - so it belongs on an IO thread, which
 * is where every caller puts it. After the first call this is a field read.
 *
 * A null answer (an instance with no account at all, i.e. one that is still
 * being installed) is deliberately not cached: it is not an answer about the
 * past, and the next call is the one that will find the first account.
 */
internal fun ownerId(): UUID? {
    cachedOwnerId?.let { return it }
    return synchronized(ownerCacheLock) {
        cachedOwnerId ?: transaction(DatabaseFactory.db) { resolveOwnerInside() }
            .also { cachedOwnerId = it }
    }
}

/** Forgets the cached answer; a restart does the same implicitly. */
internal fun resetOwnerCache() {
    cachedOwnerId = null
}

/**
 * The one definition of "owner", for callers that are already inside a
 * transaction (it reuses it through the thread-local). Reads only: a plain read
 * must not become a write because a DTO wanted to know who the owner was.
 */
internal fun ownerAccountId(): UUID? = transaction(DatabaseFactory.db) {
    ServerSettingsService.ownerId() ?: earliestAccountId()
}

/**
 * Pin-if-absent, then answer. Writing the setting on first use is how an
 * instance that predates it adopts its own first account instead of losing it -
 * an upgrade must never leave the existing owner locked out of their instance.
 */
private fun resolveOwnerInside(): UUID? {
    ServerSettingsService.ownerId()?.let { return it }
    val earliest = earliestAccountId() ?: return null
    return ServerSettingsService.claimOwner(earliest)
}

/** The earliest account, ties broken by id so the answer cannot flip. */
private fun earliestAccountId(): UUID? = UsersTable.selectAll()
    .orderBy(UsersTable.createdAt to SortOrder.ASC, UsersTable.id to SortOrder.ASC)
    .limit(1)
    .firstOrNull()?.get(UsersTable.id)

/**
 * Guards the owner-only endpoints: user management, the registration toggle and
 * the host statistics.
 *
 * `suspend` because the first resolution opens a blocking JDBC transaction;
 * every caller runs on the request pipeline, so without the IO hop a slow query
 * here would occupy the event-loop thread that also has to serve everybody
 * else.
 */
internal suspend fun requireOwner(userId: UUID) {
    val owner = withContext(Dispatchers.IO) { ownerId() }
    if (userId != owner) throw ApiException.forbidden("仅实例所有者可执行此操作")
}
