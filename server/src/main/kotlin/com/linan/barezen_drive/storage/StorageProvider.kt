package com.linan.barezen_drive.storage

import io.ktor.utils.io.*
import java.nio.file.Path

interface StorageProvider {
    fun blobKey(sha256: String): String
    suspend fun put(key: String, channel: ByteReadChannel)
    suspend fun get(key: String): ByteReadChannel
    suspend fun delete(key: String)
    suspend fun exists(key: String): Boolean

    /** Local filesystem path of a key when storage is on disk; null otherwise. */
    fun resolvePath(key: String): Path? = null

    // Session-local staging directory for chunk parts and merge files.
    val tmpDir: Path
}

/**
 * Client-generated cover images are keyed by the *source* content hash, so every
 * copy of a file shares one cover: one JPEG per source sha256, whoever produced
 * it.
 *
 * That makes the key a mutable slot rather than an immutable object - the cover
 * in it is the best one this server has, and "best" changes (a client redoing a
 * bad frame, a server that learned to decode a format it could not before).
 */
fun thumbKey(sha256: String): String = "thumbs/$sha256.jpg"

/**
 * Key namespaces whose stored bytes are a *slot* - "the best one so far" -
 * rather than a value the key names. A write to one of these replaces what is
 * there; a write to anything else (a content-addressed blob) keeps the
 * skip-if-present behaviour.
 *
 * This lives with the key layout instead of on [StorageProvider.put] as a flag
 * for two reasons: the invariant "a blob can never be rewritten" then holds no
 * matter what a caller passes, and the key prefix is the only thing that knows
 * which keys are content-addressed. An avatar is the same shape as a cover - one
 * per owner, replaced when they change it.
 */
private val MUTABLE_KEY_PREFIXES = listOf("thumbs/", "avatars/")

/** True when a write to [key] replaces the stored bytes instead of skipping. */
fun isMutableKey(key: String): Boolean = MUTABLE_KEY_PREFIXES.any { key.startsWith(it) }
