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
            githubToken = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() },
        )
    }
}
