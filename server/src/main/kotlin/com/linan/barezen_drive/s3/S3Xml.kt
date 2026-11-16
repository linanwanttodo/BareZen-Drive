package com.linan.barezen_drive.s3

import com.linan.barezen_drive.api.readBounded
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

const val S3_XML_LIMIT = 1024 * 1024

/**
 * 读并解析一个 S3 XML body。硬化配方与 WebDAV 的 PROPFIND 完全一致：DOCTYPE 直接
 * 拒，外部实体不展开——用真实解析器而不是手写，因为 XXE 硬化正是没人会复审的部分。
 * 解析失败一律是 400，不是 500：截断的 body 是客户端的错。
 */
suspend fun ApplicationCall.readS3Xml(limit: Int = S3_XML_LIMIT): Element {
    val raw = readBounded(request.receiveChannel(), limit.toLong(), request.contentLength() ?: 0)
        ?: throw S3Exception("InvalidRequest", HttpStatusCode.BadRequest, "请求体超过 $limit 字节")
    return parseS3XmlBytes(raw)
}

/** 已经读过 body 的调用方（DeleteObjects 要先核对 Content-MD5）走这里，硬化配方只有一份。 */
fun parseS3XmlBytes(raw: ByteArray): Element {
    val text = raw.toString(Charsets.UTF_8).trim()
    if (text.isEmpty()) throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "请求体为空")
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        isXIncludeAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }
    val root = try {
        factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
    } catch (e: Exception) {
        throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "请求体不是合法 XML")
    }
    return root ?: throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "请求体不是合法 XML")
}

fun Node.childElements(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element) out += n
        n = n.nextSibling
    }
    return out
}

/** 直接子元素的文本；S3 的请求体都不带命名空间前缀，按 localName 匹配。 */
fun Element.childText(name: String): String? =
    childElements().firstOrNull { it.localName == name || it.nodeName == name }?.textContent

private val S3_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

/** S3 的 LastModified / CreationDate 形状，毫秒精度、UTC。 */
fun s3Timestamp(epochMillis: Long): String = S3_TIME.format(Instant.ofEpochMilli(epochMillis))