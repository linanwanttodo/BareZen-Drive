package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.data.api.AppClient
import com.linan.barezen_drive.data.evidence.EvidenceCloudVault
import com.linan.barezen_drive.data.evidence.SESSION_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readRemaining
import java.io.File
import java.nio.channels.Channels

object EvidenceCloudStore {
    private const val ROOT_NAME = "取证"
    private val files get() = AppClient.files

    private const val ROOT_FOLDER_ID = "root"

    suspend fun ensureRoot(): FolderDto = folder(null, ROOT_NAME)
    suspend fun ensureVault(vaultId: String): FolderDto = folder(ensureRoot().id, vaultId)

    private suspend fun folder(parentId: String?, name: String): FolderDto {
        val found = files.contents(parentId ?: ROOT_FOLDER_ID, includeEvidence = true).getOrThrow().folders.firstOrNull { it.name == name }
        return found ?: files.createFolder(parentId, name).getOrThrow()
    }

    suspend fun uploadManifest(vaultId: String, manifest: ByteArray): FileDto {
        val parent = ensureVault(vaultId)
        return upload(parent.id, "vault.json", manifest, "application/json")
    }

    suspend fun uploadSegment(row: SegmentRow): FileDto {
        val parent = folder(ensureVault(row.vaultId).id, row.sessionId)
        return upload(parent.id, row.name, File(row.cipherPath), "application/octet-stream", row.cipherSha256)
    }

    private suspend fun upload(
        parentId: String,
        name: String,
        bytes: ByteArray,
        mime: String,
        sha256: String? = null,
    ): FileDto {
        val temp = File.createTempFile("evidence-upload", ".tmp", EvidenceFiles.playbackDir().apply { mkdirs() })
        return try {
            temp.writeBytes(bytes)
            upload(parentId, name, temp, mime, sha256)
        } finally {
            temp.delete()
        }
    }

    private suspend fun upload(
        parentId: String,
        name: String,
        source: File,
        mime: String,
        sha256: String? = null,
    ): FileDto = withContext(Dispatchers.IO) {
        val length = source.length()
        val init = files.init(
            UploadInitRequest(
                folderId = parentId,
                name = name,
                size = length,
                mimeType = mime,
                sha256 = sha256,
                chunkSize = 4L * 1024 * 1024,
                overwrite = true,
            ),
        ).getOrThrow()
        if (init.instantUpload) return@withContext init.file ?: error("missing uploaded file")
        source.inputStream().buffered().use { input ->
            var index = 0
            val chunk = ByteArray(4 * 1024 * 1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                val bytes = if (count == chunk.size) chunk else chunk.copyOf(count)
                files.putChunk(init.uploadId, index, bytes, null).getOrThrow()
                index += 1
            }
        }
        files.complete(init.uploadId).getOrThrow()
    }

    suspend fun cloudVaults(): List<EvidenceCloudVault> {
        val root = files.contents(ROOT_FOLDER_ID, includeEvidence = true).getOrThrow().folders.firstOrNull { it.name == ROOT_NAME } ?: return emptyList()
        return files.contents(root.id, includeEvidence = true).getOrThrow().folders.filter { EvidenceNames.isVaultId(it.name) }.mapNotNull { vault ->
            val files = files.contents(vault.id, includeEvidence = true).getOrThrow().files
            val manifest = files.firstOrNull { file -> file.name == "vault.json" } ?: return@mapNotNull null
            EvidenceCloudVault(vault.name, manifest.id, parseIsoMillis(manifest.updatedAt))
        }
    }

    suspend fun downloadManifest(vault: EvidenceCloudVault): ByteArray {
        require(vault.manifestFileId.isNotEmpty())
        val channel = files.download(vault.manifestFileId)
        return withContext(Dispatchers.IO) { channel.readRemaining().readByteArray() }
    }

    suspend fun cloudSegments(vaultId: String): List<Pair<SessionFolder, FileDto>> {
        val vault = ensureVault(vaultId)
        return files.contents(vault.id, includeEvidence = true).getOrThrow().folders.filter { SESSION_ID.matches(it.name) }.flatMap { session ->
            files.contents(session.id, includeEvidence = true).getOrThrow().files.filter { it.name.endsWith(".enc") }.map { SessionFolder(session.id, session.name) to it }
        }
    }

    suspend fun downloadSegment(fileId: String, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        files.download(fileId).let { channel ->
            target.outputStream().use { output -> channel.copyTo(Channels.newChannel(output)) }
        }
    }

    suspend fun deleteVault(vaultId: String) {
        val root = files.contents(ROOT_FOLDER_ID, includeEvidence = true).getOrThrow().folders.firstOrNull { it.name == ROOT_NAME } ?: return
        val vault = files.contents(root.id, includeEvidence = true).getOrThrow().folders.firstOrNull { it.name == vaultId } ?: return
        files.deleteFolder(vault.id).getOrThrow()
        files.deleteForever(vault.id).getOrThrow()
    }

    data class SessionFolder(val id: String, val name: String)

    private fun parseIsoMillis(value: String): Long? = runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.getOrNull()
}
