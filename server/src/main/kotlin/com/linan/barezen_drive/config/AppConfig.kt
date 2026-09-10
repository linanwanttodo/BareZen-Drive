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
) {
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
