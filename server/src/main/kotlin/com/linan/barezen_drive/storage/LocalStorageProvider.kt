package com.linan.barezen_drive.storage

import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.min

class LocalStorageProvider(private val root: Path) : StorageProvider {
    private val blobsDir = root.resolve("blobs")
    override val tmpDir: Path get() = root.resolve("tmp")

    init {
        Files.createDirectories(blobsDir); Files.createDirectories(tmpDir)
    }

    override fun blobKey(sha256: String): String =
        "blobs/${sha256.substring(0, 2)}/${sha256.substring(2, 4)}/$sha256"

    override suspend fun put(key: String, channel: ByteReadChannel): Unit = withContext(Dispatchers.IO) {
        // Every public path goes through resolvePath so a hostile key can never
        // escape the storage root, not even through put/delete/exists.
        val target = resolvePath(key)
        // A blob key is the content hash: an existing key already holds exactly
        // these bytes, so a re-PUT is a retry and skipping it is the point (it
        // saves writing the whole file again). A cover or avatar key is a
        // mutable slot instead (isMutableKey), and its stored bytes are meant to
        // be replaced - otherwise the first cover ever uploaded could never be
        // corrected.
        if (!isMutableKey(key) && Files.exists(target)) { channel.discard(); return@withContext }
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
            // REPLACE_EXISTING closes the concurrent-put race: two writers of
            // the same content key both pass the exists() check above, and the
            // loser's ATOMIC_MOVE alone would throw FileAlreadyExistsException
            // (a 500) even though the winning bytes are identical - keys are
            // content-addressed, so replacing same with same is a no-op. It is
            // also what makes an overwriting cover write land in one step, so a
            // reader never sees a half-written cover.
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            Files.deleteIfExists(tmp); throw t
        } finally {
            channel.cancel()
        }
    }

    override suspend fun get(key: String): ByteReadChannel = withContext(Dispatchers.IO) {
        java.io.FileInputStream(resolvePath(key).toFile()).toByteReadChannel()
    }

    override suspend fun getRange(key: String, from: Long, untilExclusive: Long): ByteReadChannel =
        withContext(Dispatchers.IO) {
            val length = untilExclusive - from
            require(length >= 0) { "negative range length: $from..$untilExclusive" }
            LimitedInputStream(Channels.newInputStream(openRange(resolvePath(key), from)), length).toByteReadChannel()
        }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(resolvePath(key))
        return@withContext
    }

    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(resolvePath(key))
    }

    override fun resolvePath(key: String): Path {
        val resolved = root.resolve(key).normalize()
        require(resolved.startsWith(root.normalize())) { "storage key escapes root: $key" }
        return resolved
    }
}

/**
 * Opens [path] already positioned at [from], so serving a byte range costs the
 * bytes after the offset and not everything before it: a read-and-discard pulls
 * the whole prefix through the page cache on every media seek, which on a 1 GB
 * box is the difference between a seek and a stall.
 */
internal fun openRange(path: Path, from: Long): SeekableByteChannel {
    require(from >= 0) { "negative range start: $from" }
    val channel = try {
        Files.newByteChannel(path)
    } catch (_: NoSuchFileException) {
        // Same signal a missing blob gives on the whole-file path and on S3, so
        // callers keep mapping it to 404 rather than 500.
        throw FileNotFoundException(path.toString())
    }
    return try {
        channel.position(from)
        channel
    } catch (t: Throwable) {
        channel.close()
        throw t
    }
}

/**
 * The first [limit] bytes of [source], and nothing after them. The seek supplies
 * the range start; this supplies the range end, so a caller that reads the
 * channel to EOF gets the slice it asked for instead of the rest of the object.
 */
private class LimitedInputStream(
    private val source: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val b = source.read()
        if (b != -1) remaining--
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val n = source.read(buffer, offset, min(length.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = min(source.available().toLong(), remaining).toInt()

    override fun close(): Unit = source.close()
}
