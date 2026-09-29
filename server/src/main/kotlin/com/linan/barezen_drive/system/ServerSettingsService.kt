package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.isUniqueViolation
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.SettingsTable
import com.linan.barezen_drive.db.UsersTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Owner-managed server settings persisted in the settings key/value table.
 *
 * A missing row means the factory default, and the default is CLOSED: a fresh
 * instance must get its owner from the install wizard (BOOTSTRAP_ADMIN_*) or
 * from the host itself, never from whoever reaches the published port first.
 * The owner opens registration from the settings screen if the drive is meant
 * to have guests. [registrationDefault] is wired from AppConfig
 * (REGISTRATION_OPEN) at startup for operators who want the old behaviour.
 *
 * The table also holds the fixed owner id (see [claimOwner]): "who owns the
 * instance" is a fact about the past, and re-deriving it from the users table on
 * every request meant both a query per call and an answer that a later account
 * could appear to change.
 */
object ServerSettingsService {

    const val KEY_REGISTRATION_OPEN = "registration_open"
    const val KEY_OWNER_ID = "owner_id"

    /** Factory default for a missing [KEY_REGISTRATION_OPEN] row. */
    @Volatile
    var registrationDefault: Boolean = false

    fun registrationOpen(): Boolean = readBool(KEY_REGISTRATION_OPEN, registrationDefault)

    fun setRegistrationOpen(open: Boolean) {
        writeBool(KEY_REGISTRATION_OPEN, open)
    }

    /** The account that owns the instance, or null while none is fixed yet. */
    fun ownerId(): UUID? = readString(KEY_OWNER_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * Whether any account exists at all. An instance with no accounts is still
     * being installed, which is the only state in which the register endpoint
     * lets a request through without the owner having opened registration.
     */
    fun hasAnyAccount(): Boolean = transaction(DatabaseFactory.db) {
        UsersTable.selectAll().limit(1).any()
    }

    /**
     * Fixes [candidate] as the owner if nobody holds the role yet, and answers
     * who the owner is either way.
     *
     * A plain INSERT, so two registrations racing on a fresh instance cannot
     * both decide they own it: the unique key lets exactly one land and the
     * loser reads the winner back. That is what makes the answer stable - a
     * compare-then-write would let a later account overwrite it, and "earliest
     * createdAt, id ascending" would leave a same-millisecond tie to whichever
     * random UUID sorted first.
     */
    fun claimOwner(candidate: UUID): UUID = transaction(DatabaseFactory.db) {
        ownerId()?.let { return@transaction it }
        try {
            SettingsTable.insert {
                it[key] = KEY_OWNER_ID
                it[value] = candidate.toString()
            }
            candidate
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
            // Lost the race: the account that got there first owns the instance.
            ownerId() ?: candidate
        }
    }

    private fun readBool(key: String, default: Boolean): Boolean {
        val row = transaction(DatabaseFactory.db) {
            SettingsTable.selectAll().where { SettingsTable.key eq key }.singleOrNull()
        } ?: return default
        return row[SettingsTable.value] == "true"
    }

    private fun readString(key: String): String? = transaction(DatabaseFactory.db) {
        SettingsTable.selectAll().where { SettingsTable.key eq key }.singleOrNull()?.get(SettingsTable.value)
    }

    private fun writeBool(key: String, value: Boolean) {
        transaction(DatabaseFactory.db) {
            val updated = SettingsTable.update({ SettingsTable.key eq key }) {
                it[SettingsTable.value] = value.toString()
            }
            if (updated == 0) {
                try {
                    SettingsTable.insert {
                        it[SettingsTable.key] = key
                        it[SettingsTable.value] = value.toString()
                    }
                } catch (e: Exception) {
                    // Lost the insert race to a concurrent first-writer: the row
                    // now exists, so fall back to updating it rather than 500.
                    if (!e.isUniqueViolation()) throw e
                    SettingsTable.update({ SettingsTable.key eq key }) {
                        it[SettingsTable.value] = value.toString()
                    }
                }
            }
        }
    }
}
