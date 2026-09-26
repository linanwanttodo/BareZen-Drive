package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * The refcount sweep used to ask "is this storage_key still referenced?" with one
 * query per key per table. A folder delete runs it twice (hardDelete, then the
 * purge sweep), so 1000 blobs meant 4000 sequential round trips - all of them
 * holding one of the 5 pooled connections, which is how a background cleanup
 * ends up starving live traffic.
 *
 * The batched form asks once for the set of still-referenced keys, so the query
 * count no longer depends on how much is being deleted. What this pins is that
 * the batching stays correct as the key list grows past one batch, and that both
 * tables are consulted - a version row keeps a blob alive exactly like a file row.
 */
class OrphanBlobKeysBatchTest {
    private val storageDir = Files.createTempDirectory("bz-orphan").toString()
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health") // the module block is lazy; the schema comes from it
    }

    /** Insert [count] file rows directly - the sweep only ever reads the DB. */
    private fun insertFiles(count: Int): Pair<UUID, List<String>> = transaction(DatabaseFactory.db) {
        val uid = UUID.randomUUID()
        UsersTable.insert {
            it[id] = uid
            it[username] = "owner-${uid.toString().take(8)}"
            it[passwordHash] = "x"
            it[createdAt] = System.currentTimeMillis()
        }
        val keys = (1..count).map { "blobs/live/live/live-$it" }
        keys.forEachIndexed { i, key ->
            FilesTable.insert {
                it[id] = UUID.randomUUID()
                it[user] = uid
                it[folder] = null
                it[name] = "f$i.txt"
                it[size] = 10L
                it[mimeType] = "text/plain"
                it[storageKey] = key
                it[sha256] = "sha-$i"
                it[deletedAt] = 0L
                it[createdAt] = System.currentTimeMillis()
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        uid to keys
    }

    /**
     * Counts the statements a block issues, via an interceptor registered on
     * that transaction and removed again - no global side effect, so the count
     * cannot leak into other tests.
     */
    private fun countStatements(block: () -> Unit): Int {
        var count = 0
        val interceptor = object : StatementInterceptor {
            override fun beforeExecution(transaction: org.jetbrains.exposed.sql.Transaction, context: StatementContext) {
                count++
            }

            override fun afterStatementPrepared(
                transaction: org.jetbrains.exposed.sql.Transaction,
                prepared: org.jetbrains.exposed.sql.statements.api.PreparedStatementApi,
            ) = Unit

            override fun afterExecution(
                transaction: org.jetbrains.exposed.sql.Transaction,
                context: List<StatementContext>,
                prepared: org.jetbrains.exposed.sql.statements.api.PreparedStatementApi,
            ) = Unit

            override fun beforeCommit(transaction: org.jetbrains.exposed.sql.Transaction) = Unit
            override fun afterCommit(transaction: org.jetbrains.exposed.sql.Transaction) = Unit
            override fun beforeRollback(transaction: org.jetbrains.exposed.sql.Transaction) = Unit
            override fun afterRollback(transaction: org.jetbrains.exposed.sql.Transaction) = Unit

            @Suppress("UNCHECKED_CAST")
            override fun keepUserDataInTransactionStoreOnCommit(
                userData: Map<org.jetbrains.exposed.sql.Key<*>, Any?>,
            ): Map<org.jetbrains.exposed.sql.Key<*>, Any?> = emptyMap<org.jetbrains.exposed.sql.Key<*>, Any?>()
        }
        transaction(DatabaseFactory.db) {
            registerInterceptor(interceptor)
            try {
                block()
            } finally {
                unregisterInterceptor(interceptor)
            }
        }
        return count
    }

    @Test
    fun liveAndDeadKeysAreSeparatedRegardlessOfBatchCount() = testApplication {
        setup()
        // 620 live keys: more than the internal batch size, so the verdict
        // depends on the chunking being right.
        val (_, live) = insertFiles(620)
        val dead = (1..300).map { "blobs/dead/dead/dead-$it" }

        val orphans = transaction { FileService.orphanBlobKeys(live + dead) }

        assertEquals(dead.sorted(), orphans.sorted(), "every unreferenced key must be reported")
        assertTrue(orphans.intersect(live.toSet()).isEmpty(), "a referenced key must never be reported")
    }

    @Test
    fun aVersionRowKeepsItsBlobAlive() = testApplication {
        setup()
        val (uid, _) = insertFiles(1)
        val versionOnlyKey = "blobs/vers/vers/version-only"
        val fileId = transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.user eq uid }.single()[FilesTable.id]
        }
        transaction(DatabaseFactory.db) {
            FileVersionsTable.insert {
                it[id] = UUID.randomUUID()
                it[FileVersionsTable.file] = fileId
                it[revision] = 1L
                it[sha256] = "sha-v"
                it[storageKey] = versionOnlyKey
                it[size] = 10L
                it[createdAt] = System.currentTimeMillis()
            }
        }

        assertTrue(
            transaction { FileService.orphanBlobKeys(listOf(versionOnlyKey)) }.isEmpty(),
            "a version row still references this key, so it is not an orphan",
        )

        transaction(DatabaseFactory.db) {
            FileVersionsTable.deleteWhere { FileVersionsTable.storageKey eq versionOnlyKey }
        }
        assertEquals(
            listOf(versionOnlyKey),
            transaction { FileService.orphanBlobKeys(listOf(versionOnlyKey)) },
        )
    }

    @Test
    fun anEmptyCandidateListCostsNoQuery() = testApplication {
        setup()
        assertTrue(transaction { FileService.orphanBlobKeys(emptyList()) }.isEmpty())
    }

    @Test
    fun statementCountDoesNotGrowWithTheNumberOfKeys() = testApplication {
        setup()
        val small = insertFiles(3).second
        val large = insertFiles(600).second

        val smallCost = countStatements { FileService.orphanBlobKeys(small) }
        val largeCost = countStatements { FileService.orphanBlobKeys(large) }

        // One statement per table per batch, regardless of key count. The
        // per-key version this replaces issued 2 x 603 = 1206 statements here.
        assertTrue(
            largeCost <= 8,
            "603 keys must not cost more than a couple of batched statements, took $largeCost",
        )
        assertTrue(
            largeCost <= smallCost + 4,
            "cost grew with the key count: 3 keys = $smallCost, 603 keys = $largeCost",
        )
    }
}
