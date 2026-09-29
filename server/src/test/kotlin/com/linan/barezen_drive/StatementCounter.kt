package com.linan.barezen_drive

import com.linan.barezen_drive.db.DatabaseFactory
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Counts the SQL statements a block issues.
 *
 * A service that reads a row, writes it and reads it back looks fine in a test
 * that only checks results; the statement count is what makes the extra round
 * trips visible, and what keeps them from coming back. The interceptor is
 * registered on the enclosing transaction, so statements issued by a service
 * function that opens its own `transaction(db) { }` are counted too - Exposed
 * reuses the outer transaction unless `useNestedTransactions` is set.
 *
 * The interceptor is unregistered again in a finally block, so the count cannot
 * leak into other tests running in the same process.
 */
internal object StatementCounter {

    /** Total statements plus, in [sql], every executed statement's SQL text. */
    data class Count(val total: Int, val sql: List<String>)

    fun count(block: () -> Unit): Count {
        val sql = mutableListOf<String>()
        val interceptor = object : StatementInterceptor {
            override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                sql += context.sql(transaction).trim().replace(Regex("\\s+"), " ")
            }

            override fun afterStatementPrepared(transaction: Transaction, prepared: PreparedStatementApi) = Unit
            override fun afterExecution(transaction: Transaction, context: List<StatementContext>, prepared: PreparedStatementApi) = Unit
            override fun beforeCommit(transaction: Transaction) = Unit
            override fun afterCommit(transaction: Transaction) = Unit
            override fun beforeRollback(transaction: Transaction) = Unit
            override fun afterRollback(transaction: Transaction) = Unit

            @Suppress("UNCHECKED_CAST")
            override fun keepUserDataInTransactionStoreOnCommit(
                userData: Map<org.jetbrains.exposed.sql.Key<*>, Any?>,
            ): Map<org.jetbrains.exposed.sql.Key<*>, Any?> = emptyMap()
        }
        transaction(DatabaseFactory.db) {
            registerInterceptor(interceptor)
            try {
                block()
            } finally {
                unregisterInterceptor(interceptor)
            }
        }
        return Count(sql.size, sql.toList())
    }

}

/** Statements whose text starts with [verb] (`SELECT`, `DELETE`, `UPDATE`). */
internal fun StatementCounter.Count.of(verb: String): List<String> = sql.filter { it.uppercase().startsWith(verb.uppercase()) }

/** Statements of [verb] that also mention [needle] somewhere in the text. */
internal fun StatementCounter.Count.of(verb: String, needle: String): List<String> = of(verb).filter { it.contains(needle, ignoreCase = true) }
