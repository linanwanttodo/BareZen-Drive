package com.linan.barezen_drive

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.isUniqueViolation
import com.linan.barezen_drive.api.mapNameConflict
import com.linan.barezen_drive.core.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every "same name in the same folder" guard is a SELECT then an INSERT, so two
 * concurrent writers can both pass the pre-check and one of them hits the unique
 * index instead. Those races must answer 409 like the pre-check does, and only
 * duplicate-key failures may be translated: mapping a foreign key or not-null
 * violation to NAME_CONFLICT would hide a real bug behind a plausible error.
 */
class UniqueViolationTest {

    @Test
    fun postgresDuplicateKeyIsMappedToConflict() {
        val e = assertFailsWith<ApiException> {
            mapNameConflict {
                throw RuntimeException(
                    SQLException("duplicate key value violates unique constraint \"files_name_key\"", "23505"),
                )
            }
        }
        assertEquals(HttpStatusCode.Conflict, e.status)
        assertEquals(ErrorCodes.NAME_CONFLICT, e.code)
    }

    @Test
    fun h2DuplicateKeyIsMappedToConflict() {
        val e = assertFailsWith<ApiException> {
            mapNameConflict { throw SQLException("Unique index or primary key violation: PUBLIC.IDX", "23001") }
        }
        assertEquals(HttpStatusCode.Conflict, e.status)
    }

    @Test
    fun duplicateKeyWithoutSqlStateIsStillDetected() {
        // Some drivers leave sqlState null but say so in the message.
        val bare = RuntimeException(SQLException("duplicate key value violates unique constraint"))
        assertTrue(bare.isUniqueViolation())
        assertEquals(
            HttpStatusCode.Conflict,
            assertFailsWith<ApiException> { mapNameConflict { throw bare } }.status,
        )
    }

    @Test
    fun constraintViolationTypeIsDetected() {
        val typed = SQLIntegrityConstraintViolationException("violates unique constraint")
        assertTrue(typed.isUniqueViolation())
    }

    @Test
    fun otherIntegrityFailuresAreNotMapped() {
        for ((state, label) in listOf("23503" to "foreign key", "23502" to "not-null")) {
            val cause = SQLException("$label violated", state)
            assertFalse(cause.isUniqueViolation(), "$label must not look like a duplicate key")
            val thrown = assertFailsWith<SQLException> {
                mapNameConflict { throw cause }
            }
            assertSame(cause, thrown, "$label failure must propagate untouched")
        }
    }

    @Test
    fun unrelatedExceptionsPropagateAndSuccessPassesThrough() {
        val boom = IllegalStateException("db is down")
        assertSame(boom, assertFailsWith<IllegalStateException> { mapNameConflict { throw boom } })
        assertEquals(42, mapNameConflict { 42 })
    }
}
