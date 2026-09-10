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
    fun resolvePath(key: String): java.nio.file.Path? = null

    // Session-local staging directory for chunk parts and merge files.
    val tmpDir: Path
}

/** Client-generated cover images are content-addressed like blobs: one JPEG per source sha256. */
fun thumbKey(sha256: String): String = "thumbs/$sha256.jpg"
