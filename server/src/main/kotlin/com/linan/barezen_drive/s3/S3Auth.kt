package com.linan.barezen_drive.s3

import com.linan.barezen_drive.api.Throttle
import com.linan.barezen_drive.api.clientIp
import com.linan.barezen_drive.webdav.S3KeyLookup
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import com.linan.barezen_drive.webdav.WebdavTokenService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.queryString
import io.ktor.util.AttributeKey
import kotlin.math.abs

val s3TokenKey = AttributeKey<WebdavTokenRecord>("BareZenS3Token")

/** 与 login / dav-auth 各自独立的失败预算：挂载点每个请求都带凭据，错误密钥的
 *  重试循环不该把登录额度烧光（RFC 7617 附录的同一理由）。 */
const val S3_AUTH_MAX_PER_WINDOW = 20
const val S3_AUTH_WINDOW_MS = 300_000L
const val S3_MAX_PER_WINDOW = 600
const val S3_WINDOW_MS = 60_000L
private const val SKEW_MS = 15L * 60 * 1000
private const val MAX_EXPIRES = 604800L

private val S3_WRITE_METHODS = setOf("PUT", "POST", "DELETE")

fun ApplicationCall.s3RequireQuota() {
    if (!Throttle.allow("s3:" + clientIp(), S3_MAX_PER_WINDOW, S3_WINDOW_MS)) {
        throw S3Exception("SlowDown", HttpStatusCode.ServiceUnavailable, "请求过于频繁")
    }
}

/** 只读令牌上所有写操作一律 403。显式方法集合，新增方法不会悄悄变可写。 */
fun ApplicationCall.s3Writable(token: WebdavTokenRecord): WebdavTokenRecord {
    if (token.readOnly && request.httpMethod.value.uppercase() in S3_WRITE_METHODS) {
        throw S3Exception("AccessDenied", HttpStatusCode.Forbidden, "此令牌为只读")
    }
    return token
}

/**
 * 校验一个 S3 请求的签名，成功则把令牌记录放进 call attributes。
 * 失败一律抛 S3Exception，由路由包装渲染成 XML。
 */
suspend fun ApplicationCall.s3Authenticate(): WebdavTokenRecord {
    if (request.headers["x-amz-security-token"] != null ||
        request.queryString().split('&').any { it.substringBefore('=').equals("X-Amz-Security-Token", ignoreCase = true) }
    ) {
        throw S3Exception("InvalidRequest", HttpStatusCode.BadRequest, "本项目没有 STS")
    }
    val budgetKey = "s3-auth:" + clientIp()
    if (!Throttle.tryConsume(budgetKey, S3_AUTH_MAX_PER_WINDOW, S3_AUTH_WINDOW_MS)) {
        throw S3Exception("SlowDown", HttpStatusCode.ServiceUnavailable, "鉴权失败次数过多")
    }
    val token = authenticateInner()
    Throttle.refund(budgetKey, S3_AUTH_WINDOW_MS)
    attributes.put(s3TokenKey, token)
    return token
}

private suspend fun ApplicationCall.authenticateInner(): WebdavTokenRecord {
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization != null && authorization.startsWith("AWS ")) {
        throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持 SigV2")
    }
    val presign = if (authorization == null) S3SigV4.parsePresign(request.queryString()) else null
    if (authorization == null && presign == null) {
        // 预签名是唯一被认可的匿名路径；其余匿名一律 403。
        throw S3Exception("AccessDenied", HttpStatusCode.Forbidden, "拒绝访问")
    }
    val headerAuth = if (authorization != null) S3SigV4.parseAuthorization(authorization) else null
    if (authorization != null && headerAuth == null) {
        throw S3Exception("AccessDenied", HttpStatusCode.Forbidden, "Authorization 头格式不符")
    }

    val accessKey: String
    val dateStamp: String
    val region: String
    val amzDate: String
    val signedHeaders: List<String>
    val signature: String
    val now = System.currentTimeMillis()
    if (headerAuth != null) {
        accessKey = headerAuth.accessKey; dateStamp = headerAuth.dateStamp; region = headerAuth.region
        signedHeaders = headerAuth.signedHeaders; signature = headerAuth.signature
        amzDate = S3SigV4.amzDateOf(request)
            ?: throw S3Exception("AccessDenied", HttpStatusCode.Forbidden, "缺少 x-amz-date")
        val skew = S3SigV4.parseAmzDate(amzDate)
            ?: throw S3Exception("AccessDenied", HttpStatusCode.Forbidden, "x-amz-date 格式不符")
        if (abs(now - skew) > SKEW_MS) {
            throw S3Exception("RequestTimeTooSkewed", HttpStatusCode.Forbidden, "客户端时钟偏移超过 15 分钟")
        }
    } else {
        val p = presign!!
        accessKey = p.accessKey; dateStamp = p.dateStamp; region = p.region
        amzDate = p.amzDate; signedHeaders = p.signedHeaders; signature = p.signature
        if (p.expires < 1 || p.expires > MAX_EXPIRES) {
            throw S3Exception("AuthorizationQueryParametersError", HttpStatusCode.Forbidden, "X-Amz-Expires 超出范围")
        }
        val signedAt = S3SigV4.parseAmzDate(p.amzDate)!!
        if (now < signedAt - SKEW_MS) {
            throw S3Exception("RequestTimeTooSkewed", HttpStatusCode.Forbidden, "预签名时间在未来")
        }
        if (now > signedAt + p.expires * 1000) {
            throw S3Exception("RequestExpired", HttpStatusCode.Forbidden, "预签名 URL 已过期")
        }
    }

    val lookup = WebdavTokenService.lookupSigningKey(accessKey)
    val found = when (lookup) {
        is S3KeyLookup.Found -> lookup
        S3KeyLookup.UnknownKey -> throw S3Exception("InvalidAccessKeyId", HttpStatusCode.Forbidden, "Access Key 不存在")
        S3KeyLookup.NotS3Capable -> throw S3Exception(
            "AccessDenied", HttpStatusCode.Forbidden,
            "该令牌需要重新签发才能用于 S3",
        )
    }
    S3SigV4.verify(
        request, accessKey, found.secret, region, dateStamp, amzDate, signedHeaders, signature,
        presign = presign != null,
    )?.let { throw it }
    return found.record
}