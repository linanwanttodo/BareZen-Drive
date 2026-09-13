package com.linan.barezen_drive.api

import com.linan.barezen_drive.core.dto.ErrorCodes
import java.sql.SQLException

/**
 * SQLStates that mean "this row collides with an existing unique key":
 * 23505 is PostgreSQL and also what H2 reports in PostgreSQL mode; 23001 and
 * 1022 cover H2/other drivers. A generic integrity-constraint failure that is
 * NOT a duplicate key (foreign key 23503, not-null 23502) must not be mapped.
 */
private val UNIQUE_SQL_STATES = setOf("23505", "23001", "1022")

/** True when this throwable, or anything in its cause chain, is a duplicate-key violation. */
fun Throwable.isUniqueViolation(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is java.sql.SQLIntegrityConstraintViolationException) return true
        val state = (current as? SQLException)?.sqlState
        if (state != null && state in UNIQUE_SQL_STATES) return true
        // Last resort: drivers that leave sqlState unset still say so verbatim.
        val message = current.message.orEmpty()
        if (message.contains("unique index or primary key violation", ignoreCase = true) ||
            message.contains("duplicate key value", ignoreCase = true) ||
            message.contains("violates unique constraint", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Every "same name in the same folder" guard is a SELECT followed by an INSERT:
 * two writers can both pass the check and one of them then hits the unique
 * index, which without this mapping leaves StatusPages answering 500. Translate
 * that race into the same 409 NAME_CONFLICT the pre-check produces.
 */
fun <T> mapNameConflict(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    // Exposed wraps the driver exception in ExposedSQLException (a runtime
    // exception), so the duplicate-key test has to walk the cause chain.
    if (e.isUniqueViolation()) {
        throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件夹或文件")
    }
    throw e
}
