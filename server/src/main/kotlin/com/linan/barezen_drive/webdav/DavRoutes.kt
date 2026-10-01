package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.api.ApiException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.Writer
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The WebDAV surface, mounted at /dav.
 *
 * Outside /api on purpose: a mount speaks HTTP Basic, not the app's Bearer JWT,
 * and the two must not be interchangeable. Registered after the other real
 * routes so nothing shadows it, and before staticWeb() so an unmatched /dav path
 * is answered here rather than by the SPA fallback (see
 * StaticWeb.RESERVED_PREFIXES).
 *
 * Authenticated as a whole rather than per method, so an unauthenticated request
 * to any method gets a 401 with a `WWW-Authenticate` challenge instead of a bare
 * 404 that leaves the client with nothing to authenticate against.
 */
fun Route.webdavResourceRoutes() {
    authenticate("auth-webdav") {
        // Both spellings are registered, and the trailing-slash one is the point:
        // the canonical URL of a WebDAV collection ends in "/", and that is what
        // Finder, Explorer, davfs2 and rclone all send. Ktor's constant path
        // selector treats "/dav" and "/dav/" as different routes, so registering
        // only one of them means a client gets a 404 for the very URL it was
        // just handed and reports the mount as broken.
        //
        // "/dav" is also the only branch that covers "/dav/a/b": its constant
        // selector consumes just the first segment and leaves the rest as the
        // tail a `handle` matches, while "/dav/" matches only the bare
        // collection URL. Registering davSurface() on the root instead - which
        // is what this used to do - leaves every path below /dav unrouted, and
        // the trailing-slash branch's catch-all alive only for "/dav/" itself.
        route("/dav") { davSurface() }
        route("/dav/") { davSurface() }
    }
}

private fun Route.davSurface() {
    // OPTIONS is the hard floor for a Windows mount to succeed at all, so it is
    // the one method registered up front. The rest arrive with the later tasks
    // and take precedence over the catch-all below.
    options {
        call.davAllowRequest()
        call.respond(HttpStatusCode.OK)
        call.response.header(HttpHeaders.Allow, DAV_ALLOW)
        call.response.header("DAV", "1")
        // Office reads this; harmless elsewhere.
        call.response.header("MS-Author-Via", "DAV")
    }

    // The catch-all in davMethods() is not a cosmetic one. Ktor only runs the
    // `authenticate` challenge for a route that actually matched, so without a
    // handler covering the remaining methods an unauthenticated GET /dav/ would
    // come back 404 and the client would never be offered a challenge to answer.
    davMethods()

    // Everything below the collection URL. A `handle` on the "/dav" node matches
    // only when the whole path has been consumed - it is a handler on that node,
    // not a tail match - so without this tailcard "PROPFIND /dav/a/b" resolves
    // to nothing at all and falls through to whatever is registered last. Every
    // method is answered down here, not just the ones with real work to do.
    route("{...}") { davMethods() }
}

/** The methods a resource path answers, and the catch-all for the rest. */
private fun Route.davMethods() {
    // PROPFIND is the method a mount cannot work without: without a listing,
    // Finder and Explorer show an empty drive and rclone fails before it ever
    // tries a transfer.
    method(HttpMethod("PROPFIND")) { handle { call.respondPropfind() } }
    davGetAndHead()
    handle { call.respond(HttpStatusCode.NotFound) }
}

/**
 * Advertised capabilities.
 *
 * LOCK/UNLOCK are deliberately absent, and so is the `2` in `DAV:`. Neither is
 * implemented - they answer 501 - and advertising them changes client behaviour
 * for the worse: a client that believes locking is available declines to open a
 * file it cannot lock, and Office refuses to save one. Claiming a capability we
 * lack is worse than the 501 itself.
 *
 * `X-MSDAVEXT` is likewise withheld. It advertises the Windows-only name-mangling
 * extensions, and a client that trusts it starts sending `Translate: f` expecting
 * a rewritten display name.
 */
private const val DAV_ALLOW =
    "OPTIONS, HEAD, GET, PROPFIND, PUT, MKCOL, DELETE, MOVE, COPY"

/** A PROPFIND body is a property list; anything larger is a client bug or an attack. */
private const val MAX_PROPFIND_BODY = 64 * 1024

private const val MULTISTATUS_HEAD =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n"
private const val MULTISTATUS_TAIL = "\n</D:multistatus>\n"

/**
 * One PROPFIND, as a 207 Multi-Status.
 *
 * Streamed: the listing is written entry by entry as the pages come off the
 * cursor, so a 100k-file directory never exists as one string and never sits in
 * memory twice. The status and headers are therefore settled before the first
 * byte is written - every refusal below happens first, because a thrown
 * exception after the response has started cannot turn into a 404 any more.
 */
private suspend fun ApplicationCall.respondPropfind() {
    davAllowRequest()
    val userId = requireDavToken().userId
    val depth = propfindDepth()

    val rawPath = request.path().removePrefix("/dav")
    // A path that does not decode, or that hides a separator inside a segment,
    // cannot name anything: no name may contain one.
    val segments = davSegments(rawPath) ?: throw ApiException.notFound("资源不存在")
    val (requested, namesOnly) = readPropfindBody()

    when (val target = resolveDavPath(userId, segments)) {
        DavTarget.Missing -> throw ApiException.notFound("资源不存在")
        is DavTarget.Blob -> {
            // RFC 4918 §9.1 has a 403 here. A 400 is the honest answer: the
            // request contradicts the resource's own type rather than asking for
            // something the caller may not have, and a WebDAV client that walks
            // a mount has no way to recover from either.
            if (depth != 0) {
                throw ApiException.badRequest(
                    "PROPFIND 的 Depth: 1 只适用于集合", "propfind-depth-on-non-collection",
                )
            }
            val entry = DavListing.blob(userId, target.id) ?: throw ApiException.notFound("资源不存在")
            val base = hrefBase(segments)
            writeMultistatus { DavProperties.writeResponse(it, base, entry, requested, namesOnly) }
        }
        is DavTarget.Collection -> {
            val entry = target.id
                ?.let { DavListing.collection(userId, it) ?: throw ApiException.notFound("资源不存在") }
                ?: DavListing.root(userId)
            // RFC 4918 §5.2: the href of a collection ends in "/". Emitting the
            // caller's spelling instead would hand back a URL that GET and
            // PROPFIND then disagree about.
            val base = hrefBase(segments)
            val selfHref = "$base/"
            writeMultistatus { out ->
                DavProperties.writeResponse(out, selfHref, entry, requested, namesOnly)
                if (depth != 1) return@writeMultistatus
                var afterName: String? = null
                var afterId: UUID? = null
                while (true) {
                    // The JDBC wait belongs off the event loop: this runs inside
                    // a response writer, which is otherwise on the Netty thread.
                    val page = withContext(Dispatchers.IO) {
                        DavListing.page(userId, target.id, afterName, afterId)
                    }
                    for (child in page) {
                        val href = "$base/" + DavProperties.hrefSegment(child.name) +
                            if (child.collection) "/" else ""
                        DavProperties.writeResponse(out, href, child, requested, namesOnly)
                    }
                    if (page.size < DavListing.PAGE) return@writeMultistatus
                    afterName = page.last().name
                    afterId = page.last().id
                }
            }
        }
    }
}

/** The 207 envelope, written around whatever [block] puts inside it. */
private suspend fun ApplicationCall.writeMultistatus(block: suspend (Writer) -> Unit) {
    respondTextWriter(
        ContentType.parse("application/xml; charset=utf-8"),
        HttpStatusCode.MultiStatus,
    ) {
        // Buffered: a multistatus entry is a dozen small writes, and a 500-row
        // page would otherwise be six thousand channel writes.
        val out = java.io.BufferedWriter(this, 32 * 1024)
        out.write(MULTISTATUS_HEAD)
        block(out)
        out.write(MULTISTATUS_TAIL)
        out.flush()
    }
}

/** "/dav" plus the canonical percent-encoded form of every path segment. */
private fun hrefBase(segments: List<String>): String =
    segments.joinToString(prefix = "/dav", separator = "") { "/" + DavProperties.hrefSegment(it) }

/**
 * The Depth header, normalised.
 *
 * `infinity` is refused rather than served. A mount always walks the whole
 * tree, and answering it means materialising every descendant of every folder -
 * on a 100k-file drive that is one request holding one connection from the
 * five-connection pool for minutes. The client is told which precondition it
 * broke so it can fall back to Depth: 1 per collection.
 *
 * A *missing* Depth is 1, not infinity, which is a deliberate deviation from
 * RFC 4918 §9.1: our infinity answer is 403, so following the RFC literally
 * would refuse every client that asked only for a listing and left the header
 * out, and the commonest way to ask for a listing is exactly that.
 */
private fun ApplicationCall.propfindDepth(): Int = when (val raw = request.headers[HttpHeaders.Depth]?.trim()?.lowercase()) {
    null, "1" -> 1
    "0" -> 0
    "infinity" -> throw ApiException.forbidden(
        "PROPFIND 只支持有限的 Depth", "propfind-finite-depth",
    )
    else -> throw ApiException.badRequest("Depth 头非法", "propfind-invalid-depth")
}

/** What the body asked for: the named properties, and whether it wanted names only. */
private data class PropfindBody(val requested: Set<DavProp>, val namesOnly: Boolean)

/**
 * Parse the propfind body, or treat its absence as allprop.
 *
 * An empty set means "allprop" downstream, which is also what RFC 4918 assigns to
 * an empty `<D:prop/>`.
 *
 * Parsed with the JDK parser and every avenue to the outside world closed: a
 * DOCTYPE is refused outright, so the `<!ENTITY x SYSTEM "file:///etc/passwd">`
 * trick has nowhere to expand to. Not a hand-rolled parser - the XXE hardening
 * of a real one is exactly the part nobody re-audits.
 */
private suspend fun ApplicationCall.readPropfindBody(): PropfindBody {
    val raw = request.receiveChannel().readRemaining(MAX_PROPFIND_BODY.toLong() + 1).readByteArray()
    if (raw.size > MAX_PROPFIND_BODY) throw ApiException.tooLarge("PROPFIND 请求体过大")
    val text = raw.toString(Charsets.UTF_8).trim()
    if (text.isEmpty()) return PropfindBody(emptySet(), false)

    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        isXIncludeAware = false
        // Deliberately not wrapped in runCatching: a parser that cannot be
        // hardened must fail the request, not quietly parse the body anyway. The
        // four features below are all supported by the JDK's own Xerces, which is
        // the only implementation on the classpath.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        // Belt and braces for the other JAXP attributes, which older parsers may
        // not know.
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }
    val document = try {
        factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    } catch (e: Exception) {
        // SAXParseException included: a truncated or DOCTYPE-carrying body is a
        // client error, and answering 500 for it would be a lie.
        throw ApiException.badRequest("PROPFIND 请求体无效", "propfind-invalid-body")
    }
    val root = document.documentElement ?: throw ApiException.badRequest("PROPFIND 请求体无效", "propfind-invalid-body")
    var namesOnly = false
    val requested = LinkedHashSet<DavProp>()
    for (child in root.childElements()) {
        when {
            child.localName == "propname" && child.namespaceURI == DavProp.DAV -> namesOnly = true
            child.localName == "prop" && child.namespaceURI == DavProp.DAV ->
                for (prop in child.childElements()) {
                    requested += DavProp(prop.namespaceURI ?: "", prop.localName ?: prop.nodeName)
                }
        }
    }
    return PropfindBody(requested, namesOnly)
}

private fun Node.childElements(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element) out += n
        n = n.nextSibling
    }
    return out
}