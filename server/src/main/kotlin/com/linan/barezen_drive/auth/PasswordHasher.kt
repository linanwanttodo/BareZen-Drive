package com.linan.barezen_drive.auth

import at.favre.lib.crypto.bcrypt.BCrypt

object PasswordHasher {
    private const val COST = 10
    fun hash(password: String): String = BCrypt.withDefaults().hashToString(COST, password.toCharArray())
    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}
