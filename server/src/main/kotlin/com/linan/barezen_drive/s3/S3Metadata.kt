package com.linan.barezen_drive.s3

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_KEYS = 16
private const val MAX_KEY_CHARS = 64
private const val MAX_VALUE_BYTES = 512
private const val MAX_JSON_BYTES = 4096
private val JSON = Json { encodeDefaults = true }

/**
 * 收集 x-amz-meta-*。键已小写（HTTP 头名大小写不敏感）。没有键时返回 null，
 * 覆盖上传据此清空旧元数据——S3 语义，不继承。
 */
internal fun ApplicationCall.s3UserMeta(): String? {
    val entries = LinkedHashMap<String, String>()
    request.headers.forEach { name, values ->
        if (!name.startsWith("x-amz-meta-", ignoreCase = true)) return@forEach
        val key = name.substring("x-amz-meta-".length).lowercase()
        val value = values.joinToString(",")
        if (key.isEmpty() || key.length > MAX_KEY_CHARS) {
            throw S3Exception("MetadataTooLarge", HttpStatusCode.BadRequest, "元数据键超长")
        }
        // 值上限是字节数：files.user_meta 是 varchar(4096)，按字符算会放行三倍的中文。
        if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
            throw S3Exception("MetadataTooLarge", HttpStatusCode.BadRequest, "元数据值超长")
        }
        entries[key] = value
    }
    if (entries.isEmpty()) return null
    if (entries.size > MAX_KEYS) {
        throw S3Exception("MetadataTooLarge", HttpStatusCode.BadRequest, "元数据键过多")
    }
    val json = JSON.encodeToString(JsonObject.serializer(), JsonObject(entries.mapValues { JsonPrimitive(it.value) }))
    if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
        throw S3Exception("MetadataTooLarge", HttpStatusCode.BadRequest, "元数据总量超限")
    }
    return json
}

/** 落库的 JSON 回显成头。解析失败当作没有元数据——行里的值只可能由本服务写入。 */
internal fun s3MetaHeaders(userMeta: String?): Map<String, String> {
    if (userMeta.isNullOrBlank()) return emptyMap()
    return runCatching {
        JSON.parseToJsonElement(userMeta).let { it as JsonObject }
            .mapValues { (_, v) -> v.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
}