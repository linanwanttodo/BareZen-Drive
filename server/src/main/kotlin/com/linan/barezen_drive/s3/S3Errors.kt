package com.linan.barezen_drive.s3

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.webdav.DavProperties
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText

/** S3 的拒绝。code 是协议里的字符串（NoSuchKey…），不是本项目的 ErrorCodes。 */
class S3Exception(val code: String, val status: HttpStatusCode, override val message: String) : RuntimeException(message)

/**
 * 渲染 S3 错误。Message 严格 XML 转义——键与名字是用户输入，这里是注入面。
 * HEAD 不带 body：S3 的 HEAD 错误只有状态码，带 XML 反而让客户端解析失败。
 */
suspend fun ApplicationCall.respondS3Error(e: S3Exception, requestId: String) {
    // s3Handle 已经在顶部设过；直接调用本函数（如测试 probe）时才补设，避免重复头。
    if (response.headers["x-amz-request-id"] == null) response.header("x-amz-request-id", requestId)
    if (request.httpMethod == HttpMethod.Head) {
        respondText("", ContentType.Application.Xml, e.status)
        return
    }
    val body = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<Error><Code>").append(DavProperties.xmlEscaped(e.code)).append("</Code>")
        append("<Message>").append(DavProperties.xmlEscaped(e.message)).append("</Message>")
        append("<Resource>").append(DavProperties.xmlEscaped(request.path())).append("</Resource>")
        append("<RequestId>").append(DavProperties.xmlEscaped(requestId)).append("</RequestId></Error>")
    }
    respondText(body, ContentType.Application.Xml, e.status)
}

/**
 * 从 files/webdav 层漏出来的 ApiException 翻成 S3 形状。S3 自己的代码一律直接抛
 * S3Exception，所以这里只处理"下层不知道 S3 存在"的那部分。
 *
 * 409 → 503 SlowDown：下层的 409 只可能是同名唯一索引冲突，而那对 S3 客户端是
 * 可重试的；413 → 400 EntityTooLarge：spec §10 把超过服务端上限归在 400。
 */
fun ApiException.toS3Exception(): S3Exception = when (status) {
    HttpStatusCode.NotFound -> S3Exception("NoSuchKey", status, message)
    HttpStatusCode.Forbidden -> S3Exception("AccessDenied", status, message)
    HttpStatusCode.Conflict -> S3Exception("SlowDown", HttpStatusCode.ServiceUnavailable, message)
    HttpStatusCode.PayloadTooLarge -> S3Exception("EntityTooLarge", HttpStatusCode.BadRequest, message)
    HttpStatusCode.BadRequest -> S3Exception("InvalidArgument", status, message)
    HttpStatusCode.PreconditionFailed -> S3Exception("PreconditionFailed", status, message)
    else -> S3Exception("InternalError", HttpStatusCode.InternalServerError, message)
}