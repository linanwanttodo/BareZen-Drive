package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.webdav.DavEntry
import com.linan.barezen_drive.webdav.DavListing
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PROPFIND is the only method a WebDAV mount cannot work without: without a
 * listing, Finder and Explorer show an empty drive and rclone errors out before
 * it ever tries a transfer. So these tests are about the wire format, not about
 * business logic - the two encodings a name goes through, the multistatus shape,
 * and which requests we refuse.
 *
 * The href/displayname pair is pinned separately on purpose: they are the two
 * classic places a DAV server is wrong (percent-encode the display name and the
 * user sees "a%20b.txt"; leave the href unencoded and "#" truncates it), and an
 * assertion that accepts either encoding catches neither bug.
 */
class DavPropfindTest {
    private val storageDir = Files.createTempDirectory("bz-davpropfind").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""
    private var token = ""

    private fun basic(user: String, pass: String) =
        "Basic " + "$user:$pass".toByteArray().encodeBase64()

    private suspend fun ApplicationTestBuilder.setup() {
        val c = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
        )
        application { module(c, LocalStorageProvider(Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user1","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
        val minted = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"label":"test mount","readOnly":false}""")
        }
        token = (json.parseToJsonElement(minted.bodyAsText()) as JsonObject)["plaintext"]!!
            .jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.mkFolder(name: String, parent: String? = null): String {
        val body = if (parent == null) """{"name":${json.encodeToString(name)}}"""
        else """{"parentId":"$parent","name":${json.encodeToString(name)}}"""
        val r = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(r.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.upload(
        name: String,
        body: String = "alpha",
        folder: String? = null,
    ): String {
        val bytes = body.encodeToByteArray()
        val init = """{"name":${json.encodeToString(name)},"size":${bytes.size}""" +
            (folder?.let { ""","folderId":"$it"""" } ?: "") + "}"
        val ir = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(init)
        }
        assertEquals(HttpStatusCode.OK, ir.status, ir.bodyAsText())
        val uploadId = (json.parseToJsonElement(ir.bodyAsText()) as JsonObject)["uploadId"]!!
            .jsonPrimitive.content
        client.put("/api/uploads/$uploadId/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(bytes)
        }
        val done = client.post("/api/uploads/$uploadId/complete") {
            header(HttpHeaders.Authorization, auth)
        }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return (json.parseToJsonElement(done.bodyAsText()) as JsonObject)["file"]!!
            .let { (it as JsonObject)["id"]!!.jsonPrimitive.content }
    }

    private suspend fun ApplicationTestBuilder.propfind(
        path: String,
        depth: String?,
        token: String = this@DavPropfindTest.token,
        body: String? = null,
    ): HttpResponse = client.request(path) {
        method = HttpMethod("PROPFIND")
        header(HttpHeaders.Authorization, basic("user1", token))
        if (depth != null) header(HttpHeaders.Depth, depth)
        if (body != null) {
            contentType(ContentType.parse("application/xml"))
            setBody(body)
        }
    }

    // --- wire-format helpers, deliberately independent of the server's own -----

    /**
     * RFC 3986 percent-encoding, via a different implementation than the server
     * uses. Only exact for the name shapes these tests use - URLDecoder's
     * encoder leaves `!'()*~` alone while the server escapes them - so a name in
     * a test never contains one of those five characters.
     */
    private fun pct(name: String): String =
        java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")

    private fun hrefsOf(body: String): List<String> =
        Regex("<D:href>(.*?)</D:href>").findAll(body).map { it.groupValues[1] }.toList()

    /** (href, response element body) for each `<D:response>` in the document. */
    private fun responsesOf(body: String): List<Pair<String, String>> =
        Regex("<D:response>(.*?)</D:response>", RegexOption.DOT_MATCHES_ALL).findAll(body)
            .map { m ->
                m.groupValues[1].let { b ->
                    Regex("<D:href>(.*?)</D:href>").find(b)!!.groupValues[1] to b
                }
            }.toList()

    private fun hrefFor(vararg segments: String): String =
        "/dav" + segments.joinToString("") { "/" + pct(it) }

    // --- listing shape ---------------------------------------------------------

    @Test
    fun depthOneListsTheCollectionItselfAndItsChildren() = testApplication {
        setup()
        val folder = mkFolder("工作")
        upload("a.txt", folder = folder)
        upload("b.txt", folder = folder)

        val r = propfind(hrefFor("工作"), "1")
        assertEquals(HttpStatusCode.MultiStatus, r.status, r.bodyAsText())
        val body = r.bodyAsText()
        val hrefs = hrefsOf(body)
        // The collection itself, then both children - as full paths, never as
        // bare names: a client resolves the href against the mount, so "a.txt"
        // would address the root and the child would appear to be its sibling.
        assertTrue(hrefs.contains(hrefFor("工作") + "/"), "the collection itself must appear: $body")
        assertTrue(hrefs.contains(hrefFor("工作", "a.txt")), "child href must be the full path: $body")
        assertTrue(hrefs.contains(hrefFor("工作", "b.txt")), "child href must be the full path: $body")
        assertEquals(3, hrefs.size, "Depth 1 is the collection plus its direct children only: $body")
        assertTrue(
            body.contains("<D:displayname>工作</D:displayname>"),
            "the collection must carry a displayname: $body",
        )
        assertTrue(body.contains("<D:collection/>"), "the collection must be marked as one: $body")
    }

    @Test
    fun depthZeroReturnsTheResourceAlone() = testApplication {
        setup()
        val folder = mkFolder("solo")
        upload("child.txt", folder = folder)

        val r = propfind(hrefFor("solo"), "0")
        assertEquals(HttpStatusCode.MultiStatus, r.status, r.bodyAsText())
        assertEquals(
            listOf(hrefFor("solo") + "/"), hrefsOf(r.bodyAsText()),
            "Depth 0 must not walk into children: ${r.bodyAsText()}",
        )
    }

    @Test
    fun hrefIsPercentEncodedButDisplayNameIsOnlyXmlEscaped() = testApplication {
        setup()
        upload("a b&c.txt", "x")

        val body = propfind(hrefFor(), "1").bodyAsText()
        // The href is a URI: the space becomes %20 and the & becomes %26, or the
        // client either shows a literal space or truncates the path at the &.
        assertTrue(
            body.contains("/dav/a%20b%26c.txt"),
            "the href must be percent-encoded: $body",
        )
        // The display name is human-readable text: the & has to be XML-escaped,
        // and percent-encoding it would show the user "a b&amp;c.txt" in their
        // own file browser.
        assertTrue(
            body.contains("<D:displayname>a b&amp;c.txt</D:displayname>"),
            "the displayname must carry the real name, XML-escaped only: $body",
        )
        assertFalse(
            body.contains("a%20b") && body.contains("<D:displayname>a%20"),
            "the displayname must never be percent-encoded: $body",
        )
    }

    @Test
    fun nonAsciiNamesSurviveTheRoundTrip() = testApplication {
        setup()
        upload("照片 2026.jpg", "x")

        val body = propfind(hrefFor(), "1").bodyAsText()
        assertTrue(
            body.contains("<D:displayname>照片 2026.jpg</D:displayname>"),
            "a non-ASCII name must be shown as it is stored: $body",
        )
        assertTrue(
            hrefsOf(body).contains("/dav/" + pct("照片 2026.jpg")),
            "the href must be UTF-8 percent-encoded: $body",
        )
    }

    @Test
    fun namesThatOnlyLookLikeRelativePathSegmentsStayLegal() = testApplication {
        setup()
        // "." and ".." are refused as whole names, but a name that merely starts
        // with dots is ordinary - refusing it here would make a backup tool that
        // restores ".hidden" fail against a mount it is entitled to write.
        upload("..文档", "x")
        upload(".hidden", "y")

        val body = propfind(hrefFor(), "1").bodyAsText()
        assertTrue(body.contains("<D:displayname>..文档</D:displayname>"), body)
        assertTrue(body.contains("<D:displayname>.hidden</D:displayname>"), body)
        assertEquals(
            2, hrefsOf(body).count { it != "/dav/" },
            "both dot-prefixed names must be listed: $body",
        )
    }

    @Test
    fun aNameContainingASignTheUrlAlsoUsesSurvivesTheRoundTrip() = testApplication {
        setup()
        // The href has to encode '%' as %25, and a decoder that insists every
        // '%' starts a valid escape then refuses to resolve the very href it
        // just wrote - which is how a directory of reports called "50%.txt"
        // disappears from a mount.
        upload("50%.txt", "x")

        val body = propfind(hrefFor(), "1").bodyAsText()
        assertTrue(body.contains("<D:displayname>50%.txt</D:displayname>"), body)
        assertTrue(body.contains("/dav/50%25.txt"), body)
        // And the name it wrote back must resolve to the same file.
        val again = propfind("/dav/50%25.txt", "0")
        assertEquals(HttpStatusCode.MultiStatus, again.status, again.bodyAsText())
        assertEquals(listOf("/dav/50%25.txt"), hrefsOf(again.bodyAsText()), again.bodyAsText())
    }

    @Test
    fun trashedFilesDoNotAppearInTheListing() = testApplication {
        setup()
        val id = upload("gone.txt")
        val del = client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status)

        val body = propfind(hrefFor(), "1").bodyAsText()
        assertFalse(
            hrefsOf(body).any { it.endsWith("/gone.txt") },
            "a trashed file must not be listed: $body",
        )
    }

    // --- refusals --------------------------------------------------------------

    @Test
    fun depthInfinityIsRefusedWithTheFiniteDepthPrecondition() = testApplication {
        setup()
        val r = propfind(hrefFor(), "infinity")
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        assertTrue(
            r.bodyAsText().contains("propfind-finite-depth"),
            "the client has to be told which precondition it broke: ${r.bodyAsText()}",
        )
    }

    @Test
    fun aMissingDepthHeaderIsTreatedAsOneNotAsInfinity() = testApplication {
        setup()
        upload("a.txt", "alpha")
        // RFC 4918 §9.1 says treat a missing Depth as infinity, but our infinity
        // answer is 403 - so following it literally would refuse every client
        // that only wanted a listing and omitted the header. Documented
        // deviation; the child must actually be there, not just the status.
        val r = propfind(hrefFor(), null)
        assertEquals(HttpStatusCode.MultiStatus, r.status, r.bodyAsText())
        assertTrue(
            hrefsOf(r.bodyAsText()).contains("/dav/a.txt"),
            "a missing Depth means Depth 1: ${r.bodyAsText()}",
        )
    }

    @Test
    fun depthOneOnAFileIsRefusedBecauseItCannotHaveChildren() = testApplication {
        setup()
        upload("a.txt", "alpha")

        val r = propfind(hrefFor("a.txt"), "1")
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(
            r.bodyAsText().contains("propfind-depth-on-non-collection"),
            "the client has to be told why: ${r.bodyAsText()}",
        )
        // Depth 0 on the same resource is the well-formed request.
        val ok = propfind(hrefFor("a.txt"), "0")
        assertEquals(HttpStatusCode.MultiStatus, ok.status, ok.bodyAsText())
        assertEquals(listOf(hrefFor("a.txt")), hrefsOf(ok.bodyAsText()), ok.bodyAsText())
    }

    @Test
    fun aPathThatDoesNotExistIsNotFound() = testApplication {
        setup()
        // Existence is the caller's own business only, so a path that belongs to
        // nobody answers 404 - never 403, which would confirm it exists.
        val r = propfind(hrefFor("not-a-folder"), "0")
        assertEquals(HttpStatusCode.NotFound, r.status, r.bodyAsText())
    }

    @Test
    fun anotherAccountsFolderIsNotFound() = testApplication {
        setup()
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user2","password":"password123"}""")
        }
        val user2Auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user2","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
        val theirs = client.post("/api/folders") {
            header(HttpHeaders.Authorization, user2Auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"private"}""")
        }
        assertEquals(HttpStatusCode.Created, theirs.status)

        val r = propfind(hrefFor("private"), "1")
        assertEquals(HttpStatusCode.NotFound, r.status, r.bodyAsText())
    }

    @Test
    fun optionsAdvertisesDavOneAndWithholdsLocking() = testApplication {
        setup()
        val r = client.request(hrefFor()) {
            method = HttpMethod.Options
            header(HttpHeaders.Authorization, basic("user1", token))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val allow = r.headers[HttpHeaders.Allow].orEmpty()
        assertTrue(allow.contains("PROPFIND"), allow)
        assertTrue(allow.contains("PUT"), allow)
        // Advertising a method we answer with 501 changes client behaviour for
        // the worse: Explorer declines to open a file it cannot lock, and Office
        // refuses to save one.
        assertFalse(allow.contains("LOCK"), "locking is not implemented, so it must not be advertised: $allow")
        val dav = r.headers.getAll("DAV").orEmpty().joinToString(",")
        assertEquals("1", dav, "must claim DAV: 1 and nothing else")
        assertEquals("DAV", r.headers["MS-Author-Via"])
    }

    // --- request body ----------------------------------------------------------

    @Test
    fun anUnknownPropertyIsReportedAs404InsideTheMultistatus() = testApplication {
        setup()
        val folder = mkFolder("props")
        upload("a.txt", "alpha", folder = folder)
        val r = client.request(hrefFor("props")) {
            method = HttpMethod("PROPFIND")
            header(HttpHeaders.Authorization, basic("user1", token))
            header(HttpHeaders.Depth, "0")
            contentType(ContentType.parse("application/xml"))
            setBody(
                """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>""" +
                    """<D:displayname/><D:x-made-up/></D:prop></D:propfind>""",
            )
        }
        assertEquals(HttpStatusCode.MultiStatus, r.status, r.bodyAsText())
        val block = responsesOf(r.bodyAsText()).single().second
        assertTrue(
            block.contains("<D:displayname>props</D:displayname>"),
            "a property we do support must come back: $block",
        )
        assertTrue(
            block.contains("<D:x-made-up/>") && block.contains("404 Not Found"),
            "an unknown property must be a 404 inside the multistatus, or the " +
                "client cannot tell 'no value' from 'not looked at': $block",
        )
    }

    @Test
    fun aPropertyThatDoesNotApplyIsOmittedRatherThanReportedAsMissing() = testApplication {
        setup()
        val folder = mkFolder("noetag")
        // A collection has no content, so getetag and getcontentlength are not
        // properties it has - they are not ones it failed to answer. Turning that
        // into a 404 propstat makes clients that key off "404 => no such
        // property" cache a negative result for a resource that is perfectly
        // alive.
        val r = propfind(
            hrefFor("noetag"), "0",
            body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>""" +
                """<D:getetag/><D:getcontentlength/><D:resourcetype/></D:prop></D:propfind>""",
        )
        assertEquals(HttpStatusCode.MultiStatus, r.status, r.bodyAsText())
        val block = responsesOf(r.bodyAsText()).single().second
        assertTrue(block.contains("<D:resourcetype>"), "the requested resourcetype must come back: $block")
        assertFalse(block.contains("getetag"), "a collection has no etag to report: $block")
        assertFalse(block.contains("404 Not Found"), "not-applicable is not missing: $block")
    }

    @Test
    fun collectionsCarryNoEtagWhileFilesDo() = testApplication {
        setup()
        val folder = mkFolder("withfile")
        upload("a.txt", "alpha", folder = folder)

        val body = propfind(hrefFor("withfile"), "1").bodyAsText()
        val entries = responsesOf(body).associateBy { it.first }
        val self = entries.getValue(hrefFor("withfile") + "/")
        val file = entries.getValue(hrefFor("withfile", "a.txt"))
        assertFalse(
            self.second.contains("getetag"),
            "a collection has no content to tag, so it must not claim an etag: $self",
        )
        assertTrue(
            Regex("<D:getetag>.+</D:getetag>").containsMatchIn(file.second),
            "a file must carry an etag: $file",
        )
        // And the etag must not leak the storage layout behind the blob.
        assertFalse(
            Regex("<D:getetag>[^\"]*blobs/").containsMatchIn(body),
            "the etag must be the content digest, not the storage key: $body",
        )
    }

    @Test
    fun anExternalEntityInTheRequestBodyIsNeverExpanded() = testApplication {
        setup()
        val r = client.request(hrefFor()) {
            method = HttpMethod("PROPFIND")
            header(HttpHeaders.Authorization, basic("user1", token))
            header(HttpHeaders.Depth, "0")
            contentType(ContentType.parse("application/xml"))
            setBody(
                """<?xml version="1.0"?><!DOCTYPE p [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
                    """<D:propfind xmlns:D="DAV:"><D:prop><D:displayname>&x;</D:displayname>""" +
                    """</D:prop></D:propfind>""",
            )
        }
        val body = r.bodyAsText()
        // 400, not 207: the parser has to refuse the DOCTYPE outright. "Nothing
        // leaked" would be a much weaker claim - the reply echoes property names,
        // never document text, so an expanded entity would be invisible in it
        // while the file was still read off disk.
        assertEquals(HttpStatusCode.BadRequest, r.status, "a DOCTYPE must be refused: $body")
        assertTrue(body.contains("propfind-invalid-body"), body)
        assertFalse(body.contains("root:"), "XXE must not read the file: ${body.take(200)}")
        assertFalse(body.contains("/bin/bash"), "XXE must not read the file: ${body.take(200)}")
    }

    @Test
    fun aMalformedRequestBodyIsAClientError() = testApplication {
        setup()
        val r = client.request(hrefFor()) {
            method = HttpMethod("PROPFIND")
            header(HttpHeaders.Authorization, basic("user1", token))
            header(HttpHeaders.Depth, "0")
            contentType(ContentType.parse("application/xml"))
            setBody("""<D:propfind><D:prop>""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    @Test
    fun anEmptyPropElementMeansAllProp() = testApplication {
        setup()
        val folder = mkFolder("allprop")
        upload("a.txt", "alpha", folder = folder)

        // `<D:prop/>` and no body at all are the same request per RFC 4918, and
        // both must come back with the full default set.
        for (body in listOf(null, """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop/></D:propfind>""")) {
            val r = propfind(hrefFor("allprop"), "1", body = body)
            assertEquals(HttpStatusCode.MultiStatus, r.status, r.bodyAsText())
            val block = responsesOf(r.bodyAsText()).first { it.first.endsWith("/a.txt") }.second
            assertTrue(block.contains("<D:resourcetype>"), "$body must return resourcetype: $block")
            assertTrue(block.contains("<D:displayname>a.txt</D:displayname>"), "$body: $block")
            assertTrue(block.contains("<D:getcontentlength>5</D:getcontentlength>"), "$body: $block")
            assertTrue(block.contains("<D:getlastmodified>"), "$body: $block")
            assertTrue(block.contains("<D:creationdate>"), "$body: $block")
            assertFalse(block.contains("404 Not Found"), "allprop must not invent missing properties: $block")
        }
    }

    @Test
    fun propNameAsksForNamesOnly() = testApplication {
        setup()
        upload("a.txt", "alpha")
        val r = propfind(
            hrefFor(), "0",
            body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:propname/></D:propfind>""",
        )
        assertEquals(HttpStatusCode.MultiStatus, r.status, r.bodyAsText())
        val block = responsesOf(r.bodyAsText()).single().second
        assertTrue(block.contains("<D:displayname/>"), "propname returns the name with no value: $block")
        assertFalse(block.contains("<D:displayname>a.txt"), "propname must not return values: $block")
    }

    // --- the lister ------------------------------------------------------------

    @Test
    fun aDirectoryLargerThanOnePageIsListedCompletelyAndExactlyOnce() = testApplication {
        setup()
        // Seeded straight into the table: 500+ real HTTP uploads would test the
        // upload path, not the lister.
        val extra = DavListing.PAGE + 3
        val uid = userId("user1")
        transaction(DatabaseFactory.db) {
            repeat(extra) { i ->
                FilesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[user] = uid
                    it[folder] = null
                    it[name] = "bulk-%04d.txt".format(i)
                    it[size] = 1L
                    it[mimeType] = "text/plain"
                    it[storageKey] = "blobs/00/00/${"a".repeat(64)}-$i"
                    it[sha256] = "%064x".format(i)
                    it[deletedAt] = 0L
                    it[createdAt] = 1_700_000_000_000L
                    it[updatedAt] = 1_700_000_000_000L
                }
            }
        }

        val body = propfind(hrefFor(), "1").bodyAsText()
        val childHrefs = hrefsOf(body).filter { it != "/dav/" }
        assertEquals(extra, childHrefs.size, "every row must appear, across page boundaries")
        assertEquals(extra, childHrefs.toSet().size, "a keyset boundary must not repeat a row")
        assertTrue(
            (0 until extra).all { childHrefs.contains("/dav/bulk-%04d.txt".format(it)) },
            "the tail of the directory is where a truncated walk shows up",
        )
    }

    @Test
    fun keysetPagingWalksFoldersAndFilesInOneOrderAcrossFoldCollisions() = testApplication {
        setup()
        val outer = mkFolder("outer")
        val uid = userId("user1")
        val inner = UUID.fromString(outer)
        // A folder inside the collection under test, so the walk has to page
        // two tables at once.
        mkFolder("zzz-folder", parent = outer)
        // Names whose case folding collides, spread over both tables: a walker
        // that pages only the file table, or that pages the merged list with a
        // `dropWhile` on a half-folded comparison, drops or repeats rows exactly
        // here. "beta.txt" and "BETA.TXT" fold together, so the cursor lands
        // between them on any odd page size.
        val files = listOf("Alpha.txt", "beta.txt", "BETA.TXT", "gamma.txt", "zeta.txt")
        transaction(DatabaseFactory.db) {
            files.forEach { n ->
                FilesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[user] = uid
                    it[FilesTable.folder] = inner
                    it[name] = n
                    it[size] = 1L
                    it[storageKey] = "blobs/00/00/${n.hashCode()}"
                    it[sha256] = "%064x".format(n.hashCode().toLong() and 0xffffffffL)
                    it[deletedAt] = 0L
                    it[createdAt] = 1L
                    it[updatedAt] = 1L
                }
            }
        }

        /** The whole folder, walked one [limit]-row page at a time. */
        fun walk(limit: Int): List<DavEntry> {
            val all = mutableListOf<DavEntry>()
            var afterName: String? = null
            var afterId: UUID? = null
            var pages = 0
            while (pages < 50) {
                val page = DavListing.page(uid, inner, afterName, afterId, limit = limit)
                if (page.isEmpty()) break
                all += page
                afterName = page.last().name
                afterId = page.last().id
                pages++
            }
            return all
        }

        for (limit in listOf(1, 2, 3, 7)) {
            val names = walk(limit).map { it.name }
            assertEquals(6, names.size, "page size $limit dropped a row: $names")
            assertEquals(6, names.toSet().size, "page size $limit repeated a row: $names")
            // Everything except the folded pair is fully determined: one folded
            // name per position, ascending.
            assertEquals("Alpha.txt", names[0], "page size $limit: $names")
            assertEquals(setOf("beta.txt", "BETA.TXT"), setOf(names[1], names[2]), "page size $limit: $names")
            assertEquals("gamma.txt", names[3], "page size $limit: $names")
            assertEquals("zeta.txt", names[4], "page size $limit: $names")
            assertEquals("zzz-folder", names[5], "page size $limit: $names")
        }
        assertTrue(walk(1).size > 1, "a page of 1 must take one page per row")
    }

    /**
     * The DAV surface must not leak outside /dav.
     *
     * `webdavResourceRoutes` originally called its handler builder bare inside
     * `authenticate("auth-webdav")`, which registered the catch-all and the Basic
     * challenge on the application's *root* node rather than under /dav. Every
     * path below /dav then resolved to nothing, and the root kept a handler meant
     * for a collection URL. Nothing else in the suite covers this: the other 22
     * tests all address /dav explicitly, so the misrouting was invisible until a
     * mount was actually opened.
     */
    @Test
    fun theDavSurfaceDoesNotBleedIntoTheRestOfTheApp() = testApplication {
        setup()
        // The SPA root. Must not answer with a DAV challenge or a DAV 404.
        val root = client.get("/")
        assertNotEquals(HttpStatusCode.Unauthorized, root.status, "/ must not require WebDAV credentials")
        assertNotEquals(HttpStatusCode.NotFound, root.status, "/ must not be swallowed by the DAV catch-all")

        // An unauthenticated /dav still challenges, or a client has nothing to
        // answer and cannot mount at all.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.request("/dav/") { method = HttpMethod("PROPFIND") }.status,
            "an unauthenticated PROPFIND must be challenged",
        )
        // And the real API keeps working with its own credential.
        assertEquals(HttpStatusCode.OK, client.get("/api/me") { header(HttpHeaders.Authorization, auth) }.status)

        // The sharper half of the same bug: with the catch-all and its tailcard on
        // the root, *any* path answered PROPFIND with a WebDAV challenge - so a
        // client aimed at a URL that is not a drive was handed credentials to
        // authenticate against. Outside /dav there must be no DAV challenge.
        for (path in listOf("/not-a-drive", "/api/whatever", "/index.html")) {
            val r = client.request(path) { method = HttpMethod("PROPFIND") }
            assertNotEquals(
                HttpStatusCode.Unauthorized, r.status,
                "$path must not answer with a WebDAV challenge",
            )
        }
    }

    private fun userId(username: String): UUID = transaction(DatabaseFactory.db) {
        UsersTable.selectAll().where { UsersTable.username eq username }.single()[UsersTable.id]
    }
}