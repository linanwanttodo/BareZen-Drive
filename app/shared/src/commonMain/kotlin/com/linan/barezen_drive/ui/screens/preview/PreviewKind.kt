package com.linan.barezen_drive.ui.screens.preview

import com.linan.barezen_drive.core.dto.FileDto

/** What kind of preview (if any) a file supports, decided from mime + name. */
enum class PreviewKind {
    IMAGE, VIDEO, AUDIO, TEXT, PDF, OTHER;

    companion object {
        private val textExtensions = setOf(
            "txt", "md", "markdown", "log", "csv", "json", "xml", "yaml", "yml", "toml", "ini", "conf",
            "kt", "kts", "java", "groovy", "gradle", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
            "cs", "swift", "m", "mm", "sh", "bash", "zsh", "bat", "ps1", "sql", "html", "css", "scss",
            "js", "ts", "jsx", "tsx", "vue", "php", "properties", "gitignore", "dockerfile",
        )
        private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif")
        private val videoExtensions = setOf("mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v")
        private val audioExtensions = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus")

        fun of(file: FileDto): PreviewKind {
            val mime = file.mimeType
            if (mime != null && mime != "application/octet-stream") {
                return when {
                    mime.startsWith("image/") -> IMAGE
                    mime.startsWith("video/") -> VIDEO
                    mime.startsWith("audio/") -> AUDIO
                    mime == "application/pdf" -> PDF
                    mime.startsWith("text/") -> TEXT
                    mime in setOf("application/json", "application/xml", "application/javascript",
                        "application/yaml", "application/toml", "application/x-sh") -> TEXT
                    else -> byExtension(file.name) ?: OTHER
                }
            }
            return byExtension(file.name) ?: OTHER
        }

        private fun byExtension(name: String): PreviewKind? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return when {
                ext == "pdf" -> PDF
                ext in textExtensions -> TEXT
                ext in imageExtensions -> IMAGE
                ext in videoExtensions -> VIDEO
                ext in audioExtensions -> AUDIO
                else -> null
            }
        }
    }
}
