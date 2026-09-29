package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager as ExposedTransactionManager
import org.jetbrains.exposed.sql.Transaction as ExposedTransaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * The expired-session sweep opened one transaction per session for its two
 * DELETEs, so a backlog of a few hundred abandoned uploads cost a few hundred
 * sequential round trips on the same 5-connection pool the live requests need -
 * every six hours.
 *
 * What is pinned here is that the cost of the sweep no longer grows with the
 * number of expired sessions (transactions, not rows), and that batching the
 * deletes did not change what goes away: expired sessions, their chunk rows and
 * their staging directories all disappear, and a live session is untouched.
 */
class UploadSessionCleanupBatchTest {
    private val storageDir = Files.createTempDirectory("bz-cleanup").toString()
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        // Stated explicitly: registration is closed by default, and the
        // bootstrap slot only opens for a loopback peer, so a test that
        // registers must say it wants the door open.
        registrationOpen = true,
    )
    private lateinit var storage: LocalStorageProvider

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        storage = LocalStorageProvider(Path.of(c.storageDir))
        application { module(c, storage) }
        client.get("/health") // the module block is lazy; the schema comes from it
    }

    /** [expired] sessions in the past, plus one that must survive the sweep. */
    private fun seedSessions(expired: Int): Pair<UUID, List<UUID>> = transaction(DatabaseFactory.db) {
        val uid = UUID.randomUUID()
        UsersTable.insert {
            it[id] = uid
            it[username] = "owner-${uid.toString().take(8)}"
            it[passwordHash] = "x"
            it[createdAt] = System.currentTimeMillis()
        }
        val now = System.currentTimeMillis()
        val past = (0 until expired).map {
            val sid = UUID.randomUUID()
            UploadSessionsTable.insert {
                it[id] = sid
                it[user] = uid
                it[folder] = null
                it[name] = "expired-$it.bin"
                it[size] = 16L
                it[chunkSize] = 1024L
                it[expiresAt] = now - 60_000
            }
            UploadChunksTable.insert {
                it[session] = sid
                it[chunkIndex] = 0
                it[size] = 16L
            }
            sid
        }
        val liveId = UUID.randomUUID()
        UploadSessionsTable.insert {
            it[id] = liveId
            it[user] = uid
            it[folder] = null
            it[name] = "live.bin"
            it[size] = 16L
            it[chunkSize] = 1024L
            it[expiresAt] = now + 3_600_000
        }
        UploadChunksTable.insert {
            it[session] = liveId
            it[chunkIndex] = 0
            it[size] = 16L
        }
        uid to past
    }

    /**
     * Counts the transactions [block] opens by wrapping the database's
     * transaction manager for the duration of the call. One transaction is one
     * round trip group; the per-session loop this replaces opened one per
     * session. Restored in a finally, and asserted to be non-zero so a wrapper
     * that never got installed fails loudly instead of passing vacuously.
     */
    private fun countTransactions(db: Database, block: () -> Unit): Int {
        val real = ExposedTransactionManager.managerFor(db)!!
        val opened = AtomicInteger()
        val counting = object : ExposedTransactionManager by real {
            override fun newTransaction(isolation: Int, readOnly: Boolean, outerTransaction: ExposedTransaction?): ExposedTransaction {
                opened.incrementAndGet()
                return real.newTransaction(isolation, readOnly, outerTransaction)
            }
        }
        ExposedTransactionManager.registerManager(db, counting)
        try {
            block()
        } finally {
            ExposedTransactionManager.registerManager(db, real)
        }
        return opened.get()
    }

    @Test
    fun theSweepCostsOneTransactionRegardlessOfTheSessionCount() = testApplication {
        setup()
        val (_, expired) = seedSessions(120)

        val opened = countTransactions(DatabaseFactory.db) {
            runBlocking { UploadService.cleanupExpired(storage) }
        }

        assertTrue(opened >= 1, "no transaction was observed at all - the counter is not wired up")
        assertTrue(
            opened <= 4,
            "120 expired sessions must not cost more than a handful of transactions, took $opened",
        )
        assertEquals(
            0L,
            transaction(DatabaseFactory.db) { UploadSessionsTable.selectAll().where { UploadSessionsTable.id inList expired }.count() },
            "every expired session must be gone",
        )
        assertEquals(1, transaction(DatabaseFactory.db) { UploadSessionsTable.selectAll().count() }, "only the live session may remain")
    }

    @Test
    fun theSweepRemovesSessionsChunksAndStagingDirsButKeepsLiveOnes() = testApplication {
        setup()
        val (_, expired) = seedSessions(3)
        // Staging directories: what putChunk would have left behind.
        expired.forEach { sid ->
            val dir = storage.tmpDir.resolve(sid.toString())
            Files.createDirectories(dir)
            Files.write(dir.resolve("0.part"), byteArrayOf(9))
        }

        runBlocking { UploadService.cleanupExpired(storage) }

        val names = transaction(DatabaseFactory.db) { UploadSessionsTable.selectAll().map { it[UploadSessionsTable.name] } }
        assertFalse(names.any { it.startsWith("expired-") }, "expired sessions must be gone, left: $names")
        assertEquals(1L, transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().count() }, "only the live session's chunk row may remain")
        expired.forEach { sid ->
            assertFalse(Files.exists(storage.tmpDir.resolve(sid.toString())), "staging dir of $sid must be gone")
        }
        val live = transaction(DatabaseFactory.db) { UploadSessionsTable.selectAll().single() }
        assertEquals("live.bin", live[UploadSessionsTable.name], "a session that has not expired must survive the sweep")
    }
}
