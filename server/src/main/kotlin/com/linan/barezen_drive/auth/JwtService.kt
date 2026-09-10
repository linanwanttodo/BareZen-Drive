package com.linan.barezen_drive.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

object JwtService {
    private const val ISSUER = "barezen"
    private const val TTL_MS = 15L * 60 * 1000
    private lateinit var algorithm: Algorithm

    fun init(secret: String) {
        if (::algorithm.isInitialized) return
        algorithm = Algorithm.HMAC256(secret)
    }

    fun issue(userId: String): String =
        JWT.create().withIssuer(ISSUER).withClaim("sub", userId)
            .withExpiresAt(Date(System.currentTimeMillis() + TTL_MS))
            .sign(algorithm)

    val verifier: JWTVerifier get() = JWT.require(algorithm).withIssuer(ISSUER).build()
}
