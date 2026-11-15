package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S3ListingTest {
    private val storageDir = Files.createTempDirectory("bz-s3list").toString()
    private var accessKey = ""
    private var secret = ""
    private var userId = ""
    private var apiBearer = ""

    private suspend fun ApplicationTestBuilder.setup(maxFileSize: Long = 1L shl 30) {
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, maxFileSize, registrationOpen = true,
        )
        application { module(cfg, LocalStorageProvider(Path.of(cfg.storageDir))) }
        client.post("/api/auth/register") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user1","password":"password123"}""")
        }.bodyAsText()
        val bearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        apiBearer = bearer
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, bearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"s3","readOnly":false}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
        userId = transaction(DatabaseFactory.db) {
            com.linan.barezen_drive.db.UsersTable.selectAll()
                .where { com.linan.barezen_drive.db.UsersTable.username eq "user1" }
                .single()[com.linan.barezen_drive.db.UsersTable.id].toString()
        }
    }

    /** 返回 (Authorization, x-amz-date)。两者必须来自同一个 Instant，否则签名对不上。 */
    private fun authFor(
        method: String, path: String, query: String = "",
        payloadHash: String = "UNSIGNED-PAYLOAD",
    ): Pair<String, String> {
        val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val headers = linkedMapOf("host" to "localhost", "x-amz-date" to amzDate, "x-amz-content-sha256" to payloadHash)
        val authz = SigV4.authorization(
            method, SigV4.canonicalUriRaw(path), SigV4.canonicalQueryRaw(query),
            headers, payloadHash, accessKey, secret, "us-east-1", "s3", amzDate, amzDate.substring(0, 8),
        )
        return authz to amzDate
    }

    /** extraHeaders 不参与签名（SignedHeaders 只含 host/x-amz-date/x-amz-content-sha256）。 */
    private suspend fun ApplicationTestBuilder.s3(
        method: String, path: String, query: String = "",
        body: ByteArray = ByteArray(0),
        extraHeaders: Map<String, String> = emptyMap(),
        payloadHash: String = "UNSIGNED-PAYLOAD",
    ): HttpResponse {
        val (authz, amzDate) = authFor(method, path, query, payloadHash)
        return client.request(if (query.isEmpty()) path else "$path?$query") {
            this.method = HttpMethod.parse(method)
            header(HttpHeaders.Authorization, authz)
            // 测试宿主不会替我们填 Host，而 host 是 SignedHeaders 里的一员：
            // 不显式发出去，服务端规范化出来的就是 "host:"，签名必然不符。
            header(HttpHeaders.Host, "localhost")
            header("x-amz-date", amzDate)
            header("x-amz-content-sha256", payloadHash)
            extraHeaders.forEach { (k, v) -> header(k, v) }
            if (body.isNotEmpty() || method == "PUT" || method == "POST") setBody(body)
        }
    }

    /**
     * 走真实 API 上传到指定文件夹，返回 file id。
     *
     * 复用 setup 拿到的 bearer：login 限流是每 IP 每分钟 10 次（AuthRoutes），
     * 属性测试要播 59 个文件，每次重新登录会在第 11 个就拿到 429。
     */
    private suspend fun ApplicationTestBuilder.uploadViaApiTo(folderId: UUID?, name: String, body: String): String {
        val bearer = apiBearer
        val bytes = body.toByteArray()
        val folderJson = folderId?.let { """"folderId":"$it",""" } ?: ""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, bearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("{$folderJson\"name\":\"$name\",\"size\":${bytes.size}}")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val sid = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        client.put("/api/uploads/$sid/chunks/0") { header(HttpHeaders.Authorization, bearer); setBody(bytes) }
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, bearer) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(done.bodyAsText())!!.groupValues[1]
    }

    /** 用 API 建一棵树：dirs 是目录路径，files 是 "dir/name" 形式。 */
    private suspend fun ApplicationTestBuilder.seedTree(dirs: List<String>, files: List<String>) {
        s3("PUT", "/testbucket")
        val bucket = transaction(DatabaseFactory.db) {
            FoldersTable.selectAll().where { FoldersTable.name eq "testbucket" }.single()[FoldersTable.id]
        }
        val ids = HashMap<String, UUID>()
        ids[""] = bucket
        for (d in dirs.sortedBy { it.count { c -> c == '/' } }) {
            val parent = ids[d.substringBeforeLast('/', "")]!!
            val id = UUID.randomUUID()
            transaction(DatabaseFactory.db) {
                FoldersTable.insert {
                    it[FoldersTable.id] = id; it[FoldersTable.user] = UUID.fromString(userId)
                    it[FoldersTable.parent] = parent; it[FoldersTable.name] = d.substringAfterLast('/')
                }
            }
            ids[d] = id
        }
        for (f in files) {
            val parent = ids[f.substringBeforeLast('/', "")]!!
            uploadViaApiTo(parent, f.substringAfterLast('/'), "x")
        }
    }

    private suspend fun ApplicationTestBuilder.list(query: String): String =
        s3("GET", "/testbucket", if (query.isEmpty()) "list-type=2" else "list-type=2&$query").bodyAsText()

    private fun keysOf(xml: String): List<String> =
        Regex("<Key>(.*?)</Key>").findAll(xml).map { it.groupValues[1] }.toList()

    // 顶层还有一个 <Prefix> 是请求回显，只取 CommonPrefixes 里的那个才是折叠出来的目录。
    private fun prefixesOf(xml: String): List<String> =
        Regex("<CommonPrefixes><Prefix>(.*?)</Prefix></CommonPrefixes>").findAll(xml)
            .map { it.groupValues[1] }.toList()

    private fun nextToken(xml: String): String? =
        Regex("<NextContinuationToken>(.*?)</NextContinuationToken>").find(xml)?.groupValues?.get(1)

    @Test
    fun delimiterSlashFoldsDirectoriesIntoCommonPrefixes() = testApplication {
        setup()
        seedTree(listOf("a", "a/b"), listOf("root.txt", "a/one.txt", "a/b/two.txt"))
        val xml = list("delimiter=%2F")
        assertEquals(listOf("root.txt"), keysOf(xml))
        assertEquals(listOf("a/"), prefixesOf(xml))
        assertTrue(xml.contains("<KeyCount>2</KeyCount>"), xml)
    }

    @Test
    fun emptyDelimiterExpandsEverything() = testApplication {
        setup()
        seedTree(listOf("a", "a/b"), listOf("root.txt", "a/one.txt", "a/b/two.txt"))
        val xml = list("")
        assertEquals(listOf("a/b/two.txt", "a/one.txt", "root.txt"), keysOf(xml))
        assertTrue(prefixesOf(xml).isEmpty())
    }

    @Test
    fun prefixNarrowsToASubtree() = testApplication {
        setup()
        seedTree(listOf("a", "a/b"), listOf("root.txt", "a/one.txt", "a/b/two.txt"))
        assertEquals(listOf("a/b/two.txt", "a/one.txt"), keysOf(list("prefix=a%2F")))
        // 不以 / 结尾：最后一段是名字前缀
        val narrowed = list("prefix=a%2Fo&delimiter=%2F")
        assertEquals(listOf("a/one.txt"), keysOf(narrowed))
        // 名字前缀不匹配的目录也要被滤掉，否则 a/b/ 会混进 CommonPrefixes
        assertTrue(prefixesOf(narrowed).isEmpty(), narrowed)
    }

    @Test
    fun maxKeysZeroIsEmptyAndNotTruncated() = testApplication {
        setup()
        seedTree(emptyList(), listOf("a.txt", "b.txt"))
        val xml = list("max-keys=0")
        assertTrue(keysOf(xml).isEmpty())
        assertTrue(xml.contains("<IsTruncated>false</IsTruncated>"), xml)
    }

    @Test
    fun negativeMaxKeysIs400AndOverThousandIsClamped() = testApplication {
        setup()
        seedTree(emptyList(), listOf("a.txt"))
        assertEquals(HttpStatusCode.BadRequest, s3("GET", "/testbucket", "list-type=2&max-keys=-1").status)
        val xml = list("max-keys=5000")
        assertTrue(xml.contains("<MaxKeys>1000</MaxKeys>"), xml)
    }

    @Test
    fun anUnsupportedDelimiterIs501() = testApplication {
        setup()
        seedTree(emptyList(), listOf("a.txt"))
        assertEquals(HttpStatusCode.NotImplemented, s3("GET", "/testbucket", "list-type=2&delimiter=%3A").status)
    }

    @Test
    fun encodingTypeUrlEncodesTheKeys() = testApplication {
        setup()
        seedTree(emptyList(), listOf("a b.txt"))
        val xml = list("encoding-type=url")
        assertEquals(listOf("a%20b.txt"), keysOf(xml))
    }

    @Test
    fun pagingConcatenationEqualsTheFullList() = testApplication {
        setup()
        val dirs = mutableListOf<String>()
        val files = mutableListOf<String>()
        repeat(6) { i ->
            val d = "d$i"
            dirs += d
            repeat(3) { j -> files += "$d/f$j.txt" }
            repeat(2) { j ->
                val sub = "$d/s$j"
                dirs += sub
                repeat(3) { k -> files += "$sub/g$k.txt" }
            }
        }
        repeat(5) { files += "root$it.txt" }
        seedTree(dirs, files)
        for (delimiter in listOf("", "%2F")) {
            for (prefix in listOf("", "d1%2F", "d1%2Fs")) {
                val base = "delimiter=$delimiter&prefix=$prefix"
                val full = list("$base&max-keys=1000")
                val expectedKeys = keysOf(full)
                val expectedPrefixes = prefixesOf(full)
                for (page in listOf(1, 2, 3, 7)) {
                    val gotKeys = mutableListOf<String>()
                    val gotPrefixes = mutableListOf<String>()
                    var token: String? = null
                    var guard = 0
                    do {
                        val q = "$base&max-keys=$page" + (token?.let { "&continuation-token=$it" } ?: "")
                        val xml = list(q)
                        gotKeys += keysOf(xml)
                        gotPrefixes += prefixesOf(xml)
                        token = nextToken(xml)
                        assertTrue(xml.contains("<IsTruncated>true</IsTruncated>") == (token != null), xml)
                        guard++
                        assertTrue(guard < 500, "分页没有终止")
                    } while (token != null)
                    assertEquals(expectedKeys, gotKeys, "delimiter=$delimiter prefix=$prefix page=$page 的键序列不一致")
                    assertEquals(expectedPrefixes, gotPrefixes, "delimiter=$delimiter prefix=$prefix page=$page 的前缀序列不一致")
                }
            }
        }
    }

    @Test
    fun aMalformedContinuationTokenIs400() = testApplication {
        setup()
        seedTree(emptyList(), listOf("a.txt"))
        fun tok(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val id = UUID.randomUUID()
        for (bad in listOf("not-base64!!", tok("v2\nx\n$id"), tok("v1\n\n$id"))) {
            val r = s3("GET", "/testbucket", "list-type=2&continuation-token=$bad")
            assertEquals(HttpStatusCode.BadRequest, r.status, "$bad -> ${r.status}")
            assertTrue(r.bodyAsText().contains("<Code>InvalidArgument</Code>"), r.bodyAsText())
        }
    }

    @Test
    fun aContinuationTokenWhoseEntryVanishedIsEmpty() = testApplication {
        setup()
        seedTree(listOf("a"), listOf("a/one.txt", "a/two.txt", "root.txt"))
        val token = nextToken(list("max-keys=1"))!!
        val first = keysOf(list("max-keys=1")).single()
        transaction(DatabaseFactory.db) {
            FilesTable.update({ FilesTable.name eq first.substringAfterLast('/') }) {
                it[FilesTable.deletedAt] = System.currentTimeMillis()
            }
        }
        // spec §9.3：游标指向的条目没了就给空页，不是报错也不是从头再列一遍。
        val resumed = list("max-keys=1&continuation-token=$token")
        assertTrue(keysOf(resumed).isEmpty(), resumed)
        assertTrue(resumed.contains("<IsTruncated>false</IsTruncated>"), resumed)
    }

    @Test
    fun aPrefixThatDoesNotExistIsEmpty() = testApplication {
        setup()
        seedTree(listOf("a"), listOf("a/one.txt", "root.txt"))
        val xml = list("prefix=nope%2F")
        assertTrue(keysOf(xml).isEmpty(), xml)
        assertTrue(prefixesOf(xml).isEmpty(), xml)
        assertTrue(xml.contains("<KeyCount>0</KeyCount>"), xml)
    }

    @Test
    fun startAfterSkipsTheNamedEntry() = testApplication {
        setup()
        seedTree(emptyList(), listOf("a.txt", "b.txt", "c.txt"))
        assertEquals(listOf("b.txt", "c.txt"), keysOf(list("start-after=a.txt")))
        // 不存在的名字：按名字位置跳过
        assertEquals(listOf("b.txt", "c.txt"), keysOf(list("start-after=az.txt")))
    }
}