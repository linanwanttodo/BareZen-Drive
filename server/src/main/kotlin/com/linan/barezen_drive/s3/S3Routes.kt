package com.linan.barezen_drive.s3

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.plugins.respondStaticWeb
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal val S3_RESERVED_BUCKETS = setOf("api", "dav", "health", "readyz")
private val S3_WRITE_METHODS = setOf("PUT", "POST", "DELETE")

/**
 * 真正的 S3 签名痕迹。与 [looksLikeS3] 分开是因为保留桶名的拦截发生在路由之前：
 * 那里若把"写方法"也算进来，未加签的 POST /api/auth/login 会被当成 S3 请求拒掉。
 */
internal fun ApplicationCall.hasS3Marker(): Boolean {
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization != null &&
        (authorization.startsWith("AWS4-HMAC-SHA256") || authorization.startsWith("AWS "))
    ) return true
    if (request.headers["x-amz-content-sha256"] != null) return true
    return request.queryString().split('&').any { it.substringBefore('=').startsWith("x-amz-", ignoreCase = true) }
}

/**
 * 根路径与 SPA 共用命名空间，判定必须保守：宁可交回静态站点，也不要抢走 App 的
 * URL。命中任意一条即视为 S3（spec §4.2）。
 */
internal fun ApplicationCall.looksLikeS3(): Boolean =
    hasS3Marker() || request.httpMethod.value.uppercase() in S3_WRITE_METHODS

/**
 * 每个 S3 handler 的统一外壳：判定 → 限流 → 鉴权 → 业务 → 错误渲染。
 * 任何漏网异常都变成 500 InternalError，绝不落到 StatusPages 的 JSON 信封。
 */
internal suspend fun ApplicationCall.s3Handle(block: suspend ApplicationCall.(WebdavTokenRecord) -> Unit) {
    if (!looksLikeS3()) {
        respondStaticWeb()
        return
    }
    val requestId = UUID.randomUUID().toString()
    response.header("x-amz-request-id", requestId)
    try {
        s3RequireQuota()
        val token = s3Authenticate()
        block(token)
    } catch (e: S3Exception) {
        respondS3Error(e, requestId)
    } catch (e: ApiException) {
        respondS3Error(e.toS3Exception(), requestId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        application.log.error("s3: unhandled on {}", request.path(), e)
        respondS3Error(S3Exception("InternalError", HttpStatusCode.InternalServerError, "服务器内部错误"), requestId)
    }
}

/** 保留桶名的拦截在路由之前跑，那时还没有 bucket 路径参数，只能从原始路径取首段。 */
internal fun ApplicationCall.s3BucketName(): String =
    parameters["bucket"] ?: request.path().trim('/').substringBefore('/')

/** 原始路径去掉首段（桶），剩下的键仍未解码——解码规则由 S3Names 掌握。 */
internal fun ApplicationCall.s3RawKey(): String {
    val trimmed = request.path().trimStart('/')
    val slash = trimmed.indexOf('/')
    return if (slash < 0) "" else trimmed.substring(slash + 1)
}

/** 保留桶名访问一律 404 NoSuchBucket，绝不回 SPA（否则客户端把 HTML 当成功解析）。 */
internal fun ApplicationCall.s3GuardReservedBucket() {
    if (s3BucketName().lowercase() in S3_RESERVED_BUCKETS) {
        throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    }
}

fun Route.s3Routes() {
    get("/") { call.s3Handle { s3NotYetImplemented() } }
    route("/{bucket}") {
        s3BucketMethods { s3Handle { s3GuardReservedBucket(); s3NotYetImplemented() } }
        route("/") {
            s3BucketMethods { s3Handle { s3GuardReservedBucket(); s3NotYetImplemented() } }
        }
        route("{key...}") {
            s3BucketMethods { s3Handle { s3GuardReservedBucket(); s3NotYetImplemented() } }
        }
    }
}

/** 五个方法共用一个 handler；业务分发在 handler 内部按 method/query 做。 */
private fun Route.s3BucketMethods(handler: suspend ApplicationCall.() -> Unit) {
    get { call.handler() }
    head { call.handler() }
    put { call.handler() }
    delete { call.handler() }
    post { call.handler() }
}

internal suspend fun ApplicationCall.s3NotYetImplemented() {
    throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "该操作尚未实现")
}
