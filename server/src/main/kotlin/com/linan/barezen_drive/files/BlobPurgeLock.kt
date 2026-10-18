package com.linan.barezen_drive.files

import com.linan.barezen_drive.db.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BlobPurgeLock")

/**
 * A per-blob mutual exclusion between "this key has no references" and "a new
 * reference is being committed for it".
 *
 * The grace period made the delete decision re-checkable, which fixed the case
 * where the dedup upload committed *before* the sweep ran. What is left is the
 * opposite interleaving, inside a single sweep: the recount says orphan, and an
 * upload that is at that very moment between its `exists()` probe and its
 * commit gets its bytes unlinked underneath it. Both sides take this lock, so
 * they serialize instead of interleaving.
 *
 * PostgreSQL only - `pg_advisory_xact_lock` keyed by a 64-bit hash of the
 * storage key. It is transaction-scoped, so it releases on commit or rollback
 * with no cleanup to forget. On H2 (the test database) the lock is a no-op and
 * the code runs without it; that is the one behaviour these tests cannot cover.
 */
internal object BlobPurgeLock {
    /**
     * FNV-1a over the key, folded to 64 bits. A hash collision only means two
     * unrelated blobs serialize against each other for the length of one
     * delete - it cannot make an unsafe interleaving safe-looking, because the
     * recount runs again inside the lock either way.
     */
    private fun keyToLockId(key: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis
        for (b in key.encodeToByteArray()) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }

    /**
     * Matched on the dialect class name rather than JDBC metadata because the
     * lock has to be taken *inside* the caller's transaction, where the pooled
     * connection is not reachable as a handle. Both spellings are accepted:
     * Exposed has shipped this class as `PostgresSQLDialect` and
     * `PostgreSQLDialect`, and a rename that turned the check into a silent
     * false negative would drop the mutual exclusion without a single log line.
     * H2 falls through to the unguarded path, which is correct but only
     * narrows the race - the recount still runs.
     */
    private fun isPostgres(db: Database): Boolean = runCatching {
        db.dialect.javaClass.name.contains("postgres", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Runs [block] while holding the per-key advisory lock. The block must do
     * its database work inside the caller's transaction - the lock is
     * transaction-scoped on purpose, so it is held for exactly as long as the
     * surrounding transaction.
     */
    fun <T> withKey(key: String, block: () -> T): T {
        val db = DatabaseFactory.db
        if (!isPostgres(db)) return block()
        return transaction(db) {
            runCatching {
                // Void result: the call only has to take the lock, nothing to read.
                exec("SELECT pg_advisory_xact_lock(${keyToLockId(key)})") { _ -> Unit }
                Unit
            }.onFailure {
                // A dialect that reports itself as PostgreSQL but lacks the
                // function, or a permission-restricted role: the recount inside
                // the block is still correct, only the mutual exclusion is
                // missing. Losing the lock narrows the race, it does not make
                // the result wrong, so log and continue.
                log.warn("advisory lock unavailable for {}; falling back to the recount alone", key, it)
            }
            block()
        }
    }

    /**
     * Runs [block] while holding the same per-key lock, on a borrowed connection and
     * with no transaction open.
     *
     * The purge needs the lock for longer than a transaction can sensibly be held:
     * the work inside it is a network unlink, and the S3 client is configured with a
     * ten-minute request timeout. Parking a transaction across that parks one of the
     * five pooled connections idle-in-transaction, lock and all, and the sweep does
     * it once per key.
     *
     * `pg_advisory_lock` shares the object space with the `pg_advisory_xact_lock` the
     * upload side takes, so the two still serialize - only the transaction is gone,
     * and the connection goes back the moment the unlink is done. The unlock runs
     * under [NonCancellable]: a coroutine cancelled mid-sweep must still release the
     * lock, because a pooled connection that returns holding it makes the next
     * borrower wait on a lock nobody remembers taking.
     */
    suspend fun <T> withKeyHeld(key: String, block: suspend () -> T): T {
        val db = DatabaseFactory.db
        if (!isPostgres(db)) return block()
        val id = keyToLockId(key)
        val unavailable = { t: Throwable ->
            log.warn("advisory lock unavailable for {}; falling back to the recount alone", key, t)
        }
        val conn = runCatching { DatabaseFactory.borrowConnection() }.onFailure(unavailable).getOrNull()
            ?: return block()
        return conn.use { c ->
            val locked = runCatching {
                c.autoCommit = true
                c.createStatement().use { it.execute("SELECT pg_advisory_lock($id)") }
            }.onFailure(unavailable).isSuccess
            // Same verdict as losing the transaction-scoped lock: the recount still
            // decides the delete, only the mutual exclusion is missing.
            if (!locked) block() else try {
                block()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { c.createStatement().use { it.execute("SELECT pg_advisory_unlock($id)") } }
                        .onFailure { log.warn("could not release the advisory lock for {}", key, it) }
                }
            }
        }
    }

}
