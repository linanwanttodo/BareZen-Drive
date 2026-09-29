package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UserDto
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Avatar upload: the bytes are decoded, centre-cropped and re-encoded at 512px.
 *
 * That work used to run on the Netty event-loop, and the decode was a full
 * raster: a 4 MiB upload of a 12-megapixel photo materialised ~48 MB of pixels
 * while the raw body was still held, all on the thread that also has to accept
 * every other request. Decoding with subsampling means only the pixels that
 * survive into the 512px output are ever allocated - which also retires the old
 * "reject anything above 12 megapixels" ceiling, because a source that was too
 * large to raster is not too large to sample.
 */
class AvatarScalingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun cfg() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-avatar").toString(),
        maxFileSize = 1L shl 30,
    )

    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"owner1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"owner1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    /** Built off the request thread: encoding a 13 MP image is the workload here. */
    private suspend fun jpeg(width: Int, height: Int): ByteArray = withContext(Dispatchers.Default) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(30, 90, 200)
        g.fillRect(0, 0, width, height)
        g.color = Color(220, 60, 40)
        g.fillOval(width / 4, height / 4, width / 2, height / 2)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        out.toByteArray()
    }

    private suspend fun ApplicationTestBuilder.putAvatar(bytes: ByteArray) =
        client.put("/api/me/avatar") {
            header(HttpHeaders.Authorization, auth)
            setBody(bytes)
        }

    private suspend fun ApplicationTestBuilder.myId(): String {
        val me = client.get("/api/me") { header(HttpHeaders.Authorization, auth) }
        return json.decodeFromString<UserDto>(me.bodyAsText()).id
    }

    @Test
    fun aPortraitPhotoBecomesA512SquareJpeg() = testApplication {
        setup()
        val res = putAvatar(jpeg(3000, 3600))
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())

        val back = client.get("/api/users/${myId()}/avatar")
        assertEquals(HttpStatusCode.OK, back.status, back.bodyAsText())
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(back.body<ByteArray>())), "stored avatar must be an image")
        assertEquals(512, decoded.width, "avatar width")
        assertEquals(512, decoded.height, "avatar height")
    }

    /**
     * Past the old 12 MP ceiling. It used to be refused outright, so a modern
     * phone's default camera output could not be used as an avatar at all.
     */
    @Test
    fun aSourceLargerThanTheOldPixelCeilingIsStillAccepted() = testApplication {
        setup()
        val res = putAvatar(jpeg(5000, 2600)) // 13 MP
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val back = client.get("/api/users/${myId()}/avatar")
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(back.body<ByteArray>())))
        assertEquals(512, decoded.width)
    }

    @Test
    fun garbageIsStillRejected() = testApplication {
        setup()
        val res = putAvatar("this is not an image".encodeToByteArray())
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
    }

    @Test
    fun anEmptyBodyIsStillRejected() = testApplication {
        setup()
        val res = putAvatar(ByteArray(0))
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
    }

    @Test
    fun anOversizedBodyIsRefusedBeforeAnyDecoding() = testApplication {
        setup()
        // 4 MiB + 1 byte: the bounded read stops at the limit, so the answer is
        // the size error and no image decoder ever sees the body.
        val res = putAvatar(ByteArray(4 * 1024 * 1024 + 1) { 0x41 })
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status, res.bodyAsText())
    }
}
