package com.linan.barezen_drive.storage

import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * S3-compatible object storage (AWS S3, MinIO, R2, COS...) behind the same
 * [StorageProvider] contract as the local disk implementation.
 *
 * No SDK dependency: requests are signed with [SigV4] over the JDK's own
 * HTTP client, which keeps the fat jar small (the server already ships
 * java.net.http for the version check). PUT bodies are spooled through
 * [tmpDir] first because the signature needs the payload hash and the content
 * length before the request starts - memory stays flat regardless of blob
 * size, at the price of one temporary local copy per put.
 */
class S3StorageProvider(
    private val client: HttpClient,
    /** Endpoint WITHOUT the bucket, e.g. https://s3.us-east-1.amazonaws.com or http://minio:9000. */
    private val endpoint: URI,
    private val region: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    /** MinIO-style deployments address the bucket in the path; AWS defaults to a virtual host. */
    private val pathStyle: Boolean,
    override val tmpDir: Path,
) : StorageProvider {

    init {
        Files.createDirectories(tmpDir)
    }

    private val virtualHost: String? = if (pathStyle) null else "$bucket.${endpoint.host}"

    override fun blobKey(sha256: String): String =
        "blobs/${sha256.substring(0, 2)}/${sha256.substring(2, 4)}/$sha256"

    /**
     * Authority used both in the request URI and in the signed Host header -
     * one string, so what we sign is exactly what the client sends (default
     * ports are omitted on both sides).
     */
    private fun authorityHeaderValue(): String =
        virtualHost?.let { it + portSuffixForVirtualHost() }
            ?: (endpoint.host + if (endpoint.port > 0 && !isDefaultPort(endpoint.port)) ":${endpoint.port}" else "")

    private fun objectUri(key: String): URI {
        val path = if (pathStyle) "/$bucket/${key.trimStart('/')}" else "/${key.trimStart('/')}"
        val encodedPath = path.split('/').joinToString("/") { SigV4.uriEncode(it, encodeSlash = false) }
        return URI("${endpoint.scheme}://${authorityHeaderValue()}$encodedPath")
    }

    private fun portSuffixForVirtualHost(): String =
        if (endpoint.port > 0 && !isDefaultPort(endpoint.port)) ":${endpoint.port}" else ""

    private fun isDefaultPort(port: Int): Boolean =
        port == (if (endpoint.scheme == "https") 443 else 80)

    private fun request(method: String, key: String, payloadHash: String, body: HttpRequest.BodyPublisher?): HttpRequest {
        val now = java.time.LocalDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val dateStamp = now.format(DateTimeFormatter.BASIC_ISO_DATE)
        val uri = objectUri(key)
        val headers = linkedMapOf(
            "host" to authorityHeaderValue(),
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
        )
        val auth = SigV4.authorization(
            method = method,
            canonicalUri = uri.s3CanonicalUri(),
            canonicalQuery = "",
            headers = headers,
            payloadHash = payloadHash,
            accessKey = accessKey,
            secretKey = secretKey,
            region = region,
            service = "s3",
            amzDate = amzDate,
            dateStamp = dateStamp,
        )
        return HttpRequest.newBuilder(uri)
            .header("Authorization", auth)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .timeout(Duration.ofMinutes(10))
            .method(method, body ?: HttpRequest.BodyPublishers.noBody())
            .build()
    }

    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        val res = client.send(request("HEAD", key, SigV4.EMPTY_PAYLOAD_HASH, null), HttpResponse.BodyHandlers.discarding())
        when (res.statusCode()) {
            200 -> true
            404 -> false
            else -> throw IOException("S3 HEAD $key -> ${res.statusCode()}")
        }
    }

    override suspend fun get(key: String): ByteReadChannel = withContext(Dispatchers.IO) {
        val res = client.send(request("GET", key, SigV4.EMPTY_PAYLOAD_HASH, null), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) {
            res.body().close()
            if (res.statusCode() == 404) throw FileNotFoundException("S3 GET $key -> 404")
            throw IOException("S3 GET $key -> ${res.statusCode()}")
        }
        res.body().toByteReadChannel()
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        val res = client.send(request("DELETE", key, SigV4.EMPTY_PAYLOAD_HASH, null), HttpResponse.BodyHandlers.discarding())
        if (res.statusCode() !in intArrayOf(204, 404)) {
            // S3 answers 204 for existing and missing keys alike.
            throw IOException("S3 DELETE $key -> ${res.statusCode()}")
        }
    }

    /**
     * Spools the channel through tmpDir (computing SHA-256 on the way) and PUTs
     * the file. An existing key is left untouched - blobs are content-addressed
     * and immutable, matching LocalStorageProvider's skip-if-present contract.
     */
    override suspend fun put(key: String, channel: ByteReadChannel): Unit = withContext(Dispatchers.IO) {
        if (exists(key)) {
            channel.discard()
            return@withContext
        }
        val staging = Files.createTempFile(tmpDir, "s3put-", ".bin")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(staging.toFile()).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    if (n > 0) {
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val payloadHash = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            val res = client.send(
                request("PUT", key, payloadHash, HttpRequest.BodyPublishers.ofFile(staging)),
                HttpResponse.BodyHandlers.discarding(),
            )
            if (res.statusCode() !in intArrayOf(200, 201)) throw IOException("S3 PUT $key -> ${res.statusCode()}")
        } finally {
            runCatching { Files.deleteIfExists(staging) }
            channel.cancel()
        }
    }

    companion object {
        /**
         * Builds a provider from raw configuration. [storageDir] keeps being the
         * local staging directory (chunk parts, merges, put spooling), so the
         * upload protocol works identically on both backends.
         */
        fun create(
            storageDir: String,
            endpoint: String?,
            region: String,
            bucket: String,
            accessKey: String,
            secretKey: String,
            pathStyle: Boolean,
        ): S3StorageProvider {
            require(bucket.isNotBlank()) { "S3_BUCKET is required when STORAGE_BACKEND=s3" }
            require(accessKey.isNotBlank() && secretKey.isNotBlank()) { "S3_ACCESS_KEY/S3_SECRET_KEY are required when STORAGE_BACKEND=s3" }
            val ep = URI.create(
                when {
                    endpoint.isNullOrBlank() && !pathStyle -> "https://s3.$region.amazonaws.com"
                    endpoint.isNullOrBlank() -> error("S3_ENDPOINT is required for path-style access")
                    !endpoint.startsWith("http://") && !endpoint.startsWith("https://") -> "https://$endpoint"
                    else -> endpoint
                }.trimEnd('/')
            )
            require(ep.rawPath.isBlank() || ep.rawPath == "/") { "S3_ENDPOINT must not contain a path" }
            return S3StorageProvider(
                // HTTP/1.1 on purpose: the signature covers the Host header, which
                // HTTP/2 replaces with the :authority pseudo-header.
                client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    // Never follow a 30x: an object store speaks redirects only for
                    // region/bucket misconfiguration, and obeying one would let the
                    // endpoint (possibly plain http) point the client at an internal
                    // address. A 301 therefore surfaces as a failed request.
                    .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build(),
                endpoint = ep,
                region = region,
                bucket = bucket,
                accessKey = accessKey,
                secretKey = secretKey,
                pathStyle = pathStyle,
                tmpDir = Path.of(storageDir, "tmp"),
            )
        }
    }
}
