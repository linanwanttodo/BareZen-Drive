package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.s3.S3Exception
import com.linan.barezen_drive.s3.s3BucketId
import com.linan.barezen_drive.s3.s3BucketNameOk
import com.linan.barezen_drive.s3.s3EnsureParents
import com.linan.barezen_drive.s3.s3KeyOf
import com.linan.barezen_drive.s3.s3KeySegments
import com.linan.barezen_drive.s3.s3PruneEmptyAncestors
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3NamesTest {
    private val storageDir = Files.createTempDirectory("bz-s3names").toString()
    private var userId = UUID.randomUUID()

    private suspend fun ApplicationTestBuilder.setup() {
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30, registrationOpen = true,
        )
        application { module(cfg, LocalStorageProvider(Path.of(cfg.storageDir))) }
        client.get("/health") // module 块是惰性的，建表在它里面
        userId = transaction(DatabaseFactory.db) {
            val id = UUID.randomUUID()
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.username] = "u"
                it[UsersTable.passwordHash] = "x"
            }
            id
        }
    }

    private fun mkBucket(name: String, owner: UUID = userId, parent: UUID? = null): UUID =
        transaction(DatabaseFactory.db) {
            val id = UUID.randomUUID()
            FoldersTable.insert {
                it[FoldersTable.id] = id; it[FoldersTable.user] = owner
                it[FoldersTable.parent] = parent; it[FoldersTable.name] = name
            }
            id
        }

    private fun mkFile(folder: UUID, name: String, deletedAt: Long = 0L): UUID =
        transaction(DatabaseFactory.db) {
            val id = UUID.randomUUID()
            FilesTable.insert {
                it[FilesTable.id] = id; it[FilesTable.user] = userId
                it[FilesTable.folder] = folder; it[FilesTable.name] = name
                it[FilesTable.size] = 1; it[FilesTable.sha256] = "0".repeat(64)
                it[FilesTable.storageKey] = "blobs/00/00/" + "0".repeat(64)
                it[FilesTable.deletedAt] = deletedAt
            }
            id
        }

    private fun mkSession(folder: UUID, name: String): UUID = transaction(DatabaseFactory.db) {
        val id = UUID.randomUUID()
        UploadSessionsTable.insert {
            it[UploadSessionsTable.id] = id; it[UploadSessionsTable.user] = userId
            it[UploadSessionsTable.folder] = folder; it[UploadSessionsTable.name] = name
            it[UploadSessionsTable.size] = 1; it[UploadSessionsTable.chunkSize] = 1
            it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() + 60_000
        }
        id
    }

    private fun folderCount(): Long = transaction(DatabaseFactory.db) {
        FoldersTable.selectAll().where { FoldersTable.user eq userId }.count()
    }

    private fun folderExists(id: UUID): Boolean = transaction(DatabaseFactory.db) {
        FoldersTable.selectAll().where { FoldersTable.id eq id }.any()
    }

    @Test
    fun bucketNamesFollowTheS3Dialect() {
        assertTrue(s3BucketNameOk("abc"))
        assertTrue(s3BucketNameOk("my-bucket.1"))
        assertTrue(s3BucketNameOk("a".repeat(63)))
        assertFalse(s3BucketNameOk("ab"))
        assertFalse(s3BucketNameOk("a".repeat(64)))
        assertFalse(s3BucketNameOk("ABC"))
        assertFalse(s3BucketNameOk("我的桶"))
        assertFalse(s3BucketNameOk("a b"))
        assertFalse(s3BucketNameOk("-abc"))
        assertFalse(s3BucketNameOk("abc-"))
        assertFalse(s3BucketNameOk("a..b"))
        assertFalse(s3BucketNameOk("192.168.0.1"))
        assertFalse(s3BucketNameOk("a_b"))
    }

    @Test
    fun keySegmentsDecodeOnceAndRejectTheHazards() {
        assertEquals(listOf("a b", "c.txt"), s3KeySegments("a%20b/c.txt"))
        assertEquals(listOf("50%.txt"), s3KeySegments("50%.txt"))
        assertEquals(listOf("中文"), s3KeySegments("%E4%B8%AD%E6%96%87"))
        assertEquals("a b/c.txt", s3KeyOf(s3KeySegments("a%20b/c.txt")!!))
        assertNull(s3KeySegments(""))
        assertNull(s3KeySegments("a//b"))
        assertNull(s3KeySegments("a/"))
        assertNull(s3KeySegments("/a"))
        assertNull(s3KeySegments("a/../b"))
        assertNull(s3KeySegments("a/./b"))
        assertNull(s3KeySegments("a%2Fb/c"))
        assertNull(s3KeySegments("%FF"))
        assertNull(s3KeySegments("%ED%A0%80"))
        assertNull(s3KeySegments("a%00b"))
        assertNull(s3KeySegments("a%7Fb"))
        assertNull(s3KeySegments("a".repeat(256)))
        // 段长上限的反向对照：255 必须收，否则上一条测的是"什么都拒"
        assertEquals(listOf("a".repeat(255)), s3KeySegments("a".repeat(255)))
    }

    /**
     * 计划里用 "a".repeat(1025) 测键长上限，但那是被 255 的段长规则拒的，
     * 把 MAX_KEY_BYTES 整条删掉它照样绿。这里用多段键把两条规则分开。
     */
    @Test
    fun theKeyLengthLimitIsBytesOfTheWholeKey() {
        val at = listOf("a".repeat(250), "b".repeat(250), "c".repeat(250), "d".repeat(250), "e".repeat(20))
        assertEquals(1024, s3KeyOf(at).toByteArray(Charsets.UTF_8).size)
        assertEquals(at, s3KeySegments(s3KeyOf(at)))
        assertNull(s3KeySegments(s3KeyOf(at) + "x"))
        // 字符数没超但字节数超了：中文一段 255 字 = 765 字节，两段 1531 字节
        assertEquals(listOf("中".repeat(255)), s3KeySegments("中".repeat(255)))
        assertNull(s3KeySegments("中".repeat(255) + "/" + "中".repeat(255)))
    }

    @Test
    fun implicitParentsAreCreatedLevelByLevel() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        val leaf = s3EnsureParents(userId, bucket, listOf("a", "b", "c.txt"))
        val again = s3EnsureParents(userId, bucket, listOf("a", "b", "c.txt"))
        assertEquals(leaf, again)
        assertEquals(3L, folderCount()) // 桶 + a + b
    }

    @Test
    fun aSingleSegmentKeyNeedsNoDirectories() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        assertEquals(bucket, s3EnsureParents(userId, bucket, listOf("c.txt")))
        assertEquals(1L, folderCount())
    }

    @Test
    fun aFileInThePathIsAConflict() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        mkFile(bucket, "a")
        val e = assertFailsWith<S3Exception> { s3EnsureParents(userId, bucket, listOf("a", "b.txt")) }
        assertEquals("InvalidRequest", e.code)
        assertEquals(HttpStatusCode.Conflict, e.status)
    }

    /** 回收站里的同名文件不挡路：deleted_at 非 0 就不是活文件。 */
    @Test
    fun aTrashedFileDoesNotBlockThePath() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        mkFile(bucket, "a", deletedAt = System.currentTimeMillis())
        val leaf = s3EnsureParents(userId, bucket, listOf("a", "b.txt"))
        assertTrue(folderExists(leaf))
        assertEquals(2L, folderCount())
    }

    @Test
    fun pruningStopsAtTheBucketAndNeverRemovesIt() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        val leaf = s3EnsureParents(userId, bucket, listOf("a", "b", "c.txt"))
        s3PruneEmptyAncestors(userId, leaf, bucket)
        assertEquals(1L, folderCount())
        assertTrue(folderExists(bucket))
        // 再剪一次不能把桶也剪掉
        s3PruneEmptyAncestors(userId, bucket, bucket)
        assertTrue(folderExists(bucket))
    }

    @Test
    fun pruningKeepsAFolderThatStillHoldsATrashedFile() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        val leaf = s3EnsureParents(userId, bucket, listOf("a", "b", "c.txt"))
        mkFile(leaf, "gone.bin", deletedAt = System.currentTimeMillis())
        s3PruneEmptyAncestors(userId, leaf, bucket)
        assertTrue(folderExists(leaf))
        assertEquals(3L, folderCount())
    }

    @Test
    fun pruningKeepsAFolderReferencedByAnUploadSession() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        val leaf = s3EnsureParents(userId, bucket, listOf("a", "b", "c.txt"))
        mkSession(leaf, "pending.bin")
        s3PruneEmptyAncestors(userId, leaf, bucket)
        assertTrue(folderExists(leaf))
        assertEquals(3L, folderCount())
    }

    @Test
    fun pruningStopsBelowANonEmptySibling() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        val leaf = s3EnsureParents(userId, bucket, listOf("a", "b", "c.txt"))
        val sibling = s3EnsureParents(userId, bucket, listOf("a", "keep", "d.txt"))
        s3PruneEmptyAncestors(userId, leaf, bucket)
        assertFalse(folderExists(leaf))
        assertTrue(folderExists(sibling))
        assertEquals(3L, folderCount()) // 桶 + a + keep
    }

    @Test
    fun bucketLookupIsScopedToOwnerAndTopLevelAndLegalNames() = testApplication {
        setup()
        val bucket = mkBucket("testbucket")
        val other = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            UsersTable.insert {
                it[UsersTable.id] = other; it[UsersTable.username] = "other"
                it[UsersTable.passwordHash] = "x"
            }
        }
        mkBucket("otherbucket", owner = other)
        mkBucket("illegal_name")
        val nested = mkBucket("nestedbucket", parent = bucket)
        assertEquals(bucket, s3BucketId(userId, "testbucket"))
        assertNull(s3BucketId(userId, "otherbucket"))
        assertNull(s3BucketId(userId, "illegal_name"))
        assertNull(s3BucketId(userId, "nestedbucket"))
        assertNull(s3BucketId(userId, "absent"))
    }
}