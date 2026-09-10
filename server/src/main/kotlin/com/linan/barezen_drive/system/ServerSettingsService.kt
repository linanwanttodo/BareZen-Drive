package com.linan.barezen_drive.system

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.SettingsTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Owner-managed server settings persisted in the settings key/value table.
 *
 * A missing row means the default, so a fresh server accepts registration
 * (a private drive needs its first account) and the owner closes it afterwards
 * from the client's settings screen.
 */
object ServerSettingsService {

    const val KEY_REGISTRATION_OPEN = "registration_open"

    fun registrationOpen(): Boolean = readBool(KEY_REGISTRATION_OPEN, default = true)

    fun setRegistrationOpen(open: Boolean) {
        writeBool(KEY_REGISTRATION_OPEN, open)
    }

    private fun readBool(key: String, default: Boolean): Boolean {
        val row = transaction(DatabaseFactory.db) {
            SettingsTable.selectAll().where { SettingsTable.key eq key }.singleOrNull()
        } ?: return default
        return row[SettingsTable.value] == "true"
    }

    private fun writeBool(key: String, value: Boolean) {
        transaction(DatabaseFactory.db) {
            val updated = SettingsTable.update({ SettingsTable.key eq key }) {
                it[SettingsTable.value] = value.toString()
            }
            if (updated == 0) {
                SettingsTable.insert {
                    it[SettingsTable.key] = key
                    it[SettingsTable.value] = value.toString()
                }
            }
        }
    }
}
