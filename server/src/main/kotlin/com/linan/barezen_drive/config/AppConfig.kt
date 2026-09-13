package com.linan.barezen_drive.config

data class AppConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val storageDir: String,
    val maxFileSize: Long,
    /** Repository polled by GET /api/version for the newest release. */
    val updateRepoUrl: String = com.linan.barezen_drive.core.BuildInfo.REPOSITORY_URL,
    /** Release manifest (update.json) URL; preferred over the GitHub API when set. */
    val updateManifestUrl: String? = null,
    /** Optional GitHub token, raising the release-check rate limit. */
    val githubToken: String? = null,
    /** "local" (disk, default) or "s3" (any S3-compatible endpoint incl. MinIO). */
    val storageBackend: String = "local",
    /** S3 endpoint override; blank = AWS region default. Required for path-style. */
    val s3Endpoint: String? = null,
    val s3Region: String = "us-east-1",
    val s3Bucket: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    /** Address buckets in the URL path (MinIO and most self-hosted gateways). */
    val s3PathStyle: Boolean = false,
    /**
     * Set true only when the process is fronted by a reverse proxy that rewrites
     * `X-Forwarded-For` (nginx, Caddy, ...). While false the limiter keys on the
     * socket address and ignores the header entirely, so a caller cannot mint an
     * unlimited number of buckets by spoofing it.
     */
    val trustProxy: Boolean = false,
) {
    /**
     * Name the active provider is registered under in `StorageRegistry`. Only the
     * two known backends are recognised, so a typo in STORAGE_BACKEND cannot
     * register a misleading id while the local provider serves traffic.
     */
    val storageBackendId: String get() = if (storageBackend == "s3") "s3" else "local"

    companion object {
        private fun env(name: String, default: String? = null): String =
            System.getenv(name) ?: default ?: error("Missing environment variable $name")

        fun fromEnv(): AppConfig = AppConfig(
            port = env("SERVER_PORT", "8080").toInt(),
            jdbcUrl = env("JDBC_URL"),
            dbUser = env("DB_USER"),
            dbPassword = env("DB_PASSWORD"),
            jwtSecret = env("JWT_SECRET").also { require(it.length >= 32) { "JWT_SECRET must be at least 32 bytes" } },
            storageDir = env("STORAGE_DIR", "./data/storage"),
            maxFileSize = env("MAX_FILE_SIZE", (10L * 1024 * 1024 * 1024).toString()).toLong(),
            updateRepoUrl = env("UPDATE_REPO_URL", com.linan.barezen_drive.core.BuildInfo.REPOSITORY_URL),
            updateManifestUrl = System.getenv("UPDATE_MANIFEST_URL")?.takeIf { it.isNotBlank() }
                ?: defaultManifestUrl(env("UPDATE_REPO_URL", com.linan.barezen_drive.core.BuildInfo.REPOSITORY_URL)),
            githubToken = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() },
            storageBackend = env("STORAGE_BACKEND", "local").lowercase(),
            s3Endpoint = System.getenv("S3_ENDPOINT")?.takeIf { it.isNotBlank() },
            s3Region = env("S3_REGION", "us-east-1"),
            s3Bucket = env("S3_BUCKET", ""),
            s3AccessKey = env("S3_ACCESS_KEY", ""),
            s3SecretKey = env("S3_SECRET_KEY", ""),
            s3PathStyle = env("S3_PATH_STYLE", "false").toBoolean(),
            trustProxy = env("TRUST_PROXY", "false").toBoolean(),
        )

        /**
         * Default manifest location: update.json attached to the repository's
         * latest release ("releases/latest/download" always resolves to the
         * newest non-prerelease, with no API rate limit).
         */
        private fun defaultManifestUrl(repoUrl: String): String? {
            val normalized = repoUrl.trim().trimEnd('/').removeSuffix(".git")
            return if (normalized.startsWith("http")) "$normalized/releases/latest/download/update.json" else null
        }
    }
}
