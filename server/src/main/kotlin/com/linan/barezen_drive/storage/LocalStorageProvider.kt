package com.linan.barezen_drive.storage

import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class LocalStorageProvider(private val root: Path) : StorageProvider {
    private val blobsDir = root.resolve("blobs")
    override val tmpDir: Path get() = root.resolve("tmp")

    init {
        Files.createDirectories(blobsDir); Files.createDirectories(tmpDir)
    }

    override fun blobKey(sha256: String): String =
        "blobs/${sha256.substring(0, 2)}/${sha256.substring(2, 4)}/$sha256"

    override suspend fun put(key: String, channel: ByteReadChannel): Unit = withContext(Dispatchers.IO) {
        val target = root.resolve(key)
        if (Files.exists(target)) { channel.discard(); return@withContext }
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "put-", ".tmp")
        try {
            FileOutputStream(tmp.toFile()).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            Files.deleteIfExists(tmp); throw t
        } finally {
            channel.cancel()
        }
    }

    override suspend fun get(key: String): ByteReadChannel = withContext(Dispatchers.IO) {
        java.io.FileInputStream(root.resolve(key).toFile()).toByteReadChannel()
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(root.resolve(key)); Unit
    }

    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(root.resolve(key))
    }

    override fun resolvePath(key: String): Path {
        val resolved = root.resolve(key).normalize()
        require(resolved.startsWith(root.normalize())) { "storage key escapes root: $key" }
        return resolved
    }
}
