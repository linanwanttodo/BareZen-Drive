# BareZen-Drive v0.0.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 v0.0.1 —— Ktor 服务器（认证/虚拟文件系统/分块上传/下载）+ Android 客户端 + Compose Web 客户端 + Docker Compose 一键部署。

**Architecture:** Kotlin 全栈 monorepo。Server = Ktor 3.5.2 + Exposed + PostgreSQL，文件内容按 SHA-256 内容寻址（File/Blob 分离），上传协议按断点续传设计。客户端共享 `:app:shared`（Compose UI + 数据层），Android 与 Web(wasmJs) 通过 expect/actual 注入平台能力。API DTO 定义在 `:core` 双端共享。

**Tech Stack:** Kotlin 2.4.10 / Ktor 3.5.2 / Compose Multiplatform 1.11.1 / Exposed 0.61.0 / PostgreSQL 16 / H2(测试) / kotlinx-serialization / BCrypt(at.favre) / HikariCP。

**Spec:** `docs/superpowers/specs/2026-09-02-barezen-drive-v0.0.1-design.md`（实现有疑问时以 spec 为准；wasm DOM 互操作如遇 wrappers API 命名差异，按编译器提示做机械调整、行为不变，不算偏离计划）。

## Global Constraints

- 版本号统一 **0.0.1**：server `version = "0.0.1"`；Android `versionName = "0.0.1"`。
- KMP target 精简：`:core` = android + wasmJs + jvm；`:app:shared` = android + wasmJs；`:app:webApp` = 仅 wasmJs；`:app:desktopApp` 在 settings.gradle 注释。iOS/js 源文件留在磁盘不删。
- 依赖白名单（不引入 Coil / navigation / kotlinx-datetime）：exposed-core/jdbc 0.61.0、postgresql 42.7.4、HikariCP 6.2.1、at.favre bcrypt 0.10.2、h2 2.3.232（仅测试）、androidx-security-crypto 1.1.0-alpha06、kotlinx-serialization-json 1.9.0、ktor-client-core/okhttp/js/auth/content-negotiation（ktor 3.5.2）。
- bcrypt **cost 10**；access JWT **15 分钟**（HS256）；refresh token **30 天**，库中只存 SHA-256。
- 分块：默认 **5 MiB**，钳制 **1–20 MiB**；单文件上限 10 GiB。
- 错误响应统一 `{"error":{"code":"...","message":"..."}}`；错误码以 `:core` `ErrorCodes` 为准。
- 镜像：maven/google 走 Aliyun，Gradle wrapper 走腾讯镜像。
- 测试库：H2 `jdbc:h2:mem:<唯一名>;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`。
- 每个 Task 结束必须 commit；测试未过不 commit。JDK 21。
- 用户明确要求：**任何提交前先征得用户同意的初始提交（Task 0 的 commit 内容已获准为"脚手架+文档"）**；后续 task 的 commit 属实施常规操作。
- **代码零 emoji、零特殊 unicode 符号**（用户硬性要求）：所有代码文件（.kt/.kts/.xml/.yml/.html）的注释与符号只用 ASCII；不得出现 emoji、checkmark、star、->、…… 等装饰符号；图标一律用 Material Icons 矢量图标（Task 11）；UI 中文文案允许，但其中不得夹带 emoji/装饰符号。

## File Structure（总览）

```text
core/src/commonMain/kotlin/com/linan/barezen_drive/core/dto/
  Dto.kt                    # 全部 @Serializable DTO
  ErrorCode.kt              # ErrorCodes 常量 + 错误码集合
server/src/main/kotlin/com/linan/barezen_drive/
  Application.kt            # main + module() 装配
  config/AppConfig.kt       # env 读取
  db/DatabaseFactory.kt     # Hikari + Exposed + 建表
  db/Tables.kt              # 6 张表
  api/ApiException.kt       # ApiException + code->HTTP 映射
  auth/{PasswordHasher,JwtService,AuthService,AuthRoutes}.kt
  files/{FileService,FolderRoutes,FileRoutes,UploadService,UploadRoutes}.kt
  storage/{StorageProvider,LocalStorageProvider}.kt
  plugins/StaticWeb.kt      # web 静态托管
  jobs/UploadCleanupJob.kt
server/src/main/resources/web/index.html   # 本地/测试占位；Docker 构建时被真实 dist 覆盖
server/src/test/kotlin/com/linan/barezen_drive/{TestApp,AuthTest,FolderFileTest,UploadTest,DownloadTest}.kt
app/shared/src/{commonMain,androidMain,wasmJsMain}/kotlin/com/linan/barezen_drive/
  platform/{Sha256er,PureSha256,TokenStorage,FilePicker,FileSaver}.kt（expect/actual 分平台）
  data/api/{ApiClient,ApiFailure}.kt
  data/repo/{AuthRepository,FilesRepository}.kt
  data/upload/UploadManager.kt
  ui/theme/Theme.kt  ui/screens/login/LoginScreen.kt  ui/screens/files/FilesScreen.kt  ui/screens/preview/PreviewScreen.kt
  App.kt
app/androidApp/src/main/kotlin/com/linan/barezen_drive/{MainActivity,AndroidContext}.kt
app/webApp/src/webMain/kotlin/com/linan/barezen_drive/main.kt
Dockerfile  docker-compose.yml  .env.example  .dockerignore
docs/{architecture,api,development,roadmap}.md
```

---

### Task 0: 构建减重、镜像、依赖登记、版本号、初始提交

**Files:**
- Modify: `settings.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties`、`gradle/libs.versions.toml`
- Modify: `core/build.gradle.kts`、`app/shared/build.gradle.kts`、`app/webApp/build.gradle.kts`、`server/build.gradle.kts`、`app/androidApp/build.gradle.kts`
- Modify: `docs/README.md`（状态行）

**Interfaces:** Produces —— 后续所有任务依赖的依赖别名（`libs.plugins.kotlinSerialization`、`libs.kotlinx.serializationJson`、`libs.exposed.core` 等）。

- [ ] **Step 1: 重写 settings.gradle.kts（镜像 + 裁剪 desktopApp）**

```kotlin
rootProject.name = "BareZen-Drive"

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        maven("https://maven.aliyun.com/repository/public")
        google {
            mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        maven("https://maven.aliyun.com/repository/public")
        google {
            mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app:androidApp")
include(":app:shared")
include(":app:webApp")
include(":core")
include(":server")
// include(":app:desktopApp") // v0.2 恢复桌面端
```

- [ ] **Step 2: wrapper 换腾讯镜像**

`gradle/wrapper/gradle-wrapper.properties` 中 `distributionUrl` 改为：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.7.1-bin.zip
```

- [ ] **Step 3: libs.versions.toml 登记新依赖**

`[versions]` 追加：

```toml
kotlinx-serialization = "1.9.0"
exposed = "0.61.0"
postgresql = "42.7.4"
hikari = "6.2.1"
bcrypt = "0.10.2"
h2 = "2.3.232"
androidx-security-crypto = "1.1.0-alpha06"
```

`[libraries]` 追加：

```toml
kotlinx-serializationJson = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
hikari = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
bcrypt = { module = "at.favre.lib:bcrypt", version.ref = "bcrypt" }
h2 = { module = "com.h2database:h2", version.ref = "h2" }
androidx-securityCrypto = { module = "androidx.security:security-crypto", version.ref = "androidx-security-crypto" }
ktor-clientCore = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-clientOkhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-clientJs = { module = "io.ktor:ktor-client-js", version.ref = "ktor" }
ktor-clientAuth = { module = "io.ktor:ktor-client-auth", version.ref = "ktor" }
ktor-clientContentNegotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serverContentNegotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serializationJson = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-serverAuth = { module = "io.ktor:ktor-server-auth", version.ref = "ktor" }
ktor-serverAuthJwt = { module = "io.ktor:ktor-server-auth-jwt", version.ref = "ktor" }
ktor-serverStatusPages = { module = "io.ktor:ktor-server-status-pages", version.ref = "ktor" }
ktor-serverCallLogging = { module = "io.ktor:ktor-server-call-logging", version.ref = "ktor" }
ktor-serverPartialContent = { module = "io.ktor:ktor-server-partial-content", version.ref = "ktor" }
```

`[plugins]` 追加：

```toml
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 4: core 裁剪 target + 序列化**

`core/build.gradle.kts`：删除 `iosArm64()`、`iosSimulatorArm64()`、`js { browser() }` 三个 target 块；plugins 加 `alias(libs.plugins.kotlinSerialization)`；commonMain.dependencies 加 `implementation(libs.kotlinx.serializationJson)`。结果 kotlin 块为：

```kotlin
kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    android {
        namespace = "com.linan.barezen_drive.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        androidResources { enable = true }
        withHostTest { isIncludeAndroidResources = true }
    }
    // sourceSets 不变
}
```

（磁盘上的 `core/src/jsMain`、`iosMain` 目录保留不动，Gradle 忽略即可。）

- [ ] **Step 5: shared 裁剪 target + 客户端依赖**

`app/shared/build.gradle.kts`：删除 iOS framework 块、`jvm()`、`js { browser() }`；删除 `jsMain.dependencies` 块，新增：

```kotlin
        wasmJsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
```

commonMain.dependencies 追加：

```kotlin
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientAuth)
            implementation(libs.ktor.clientContentNegotiation)
```

androidMain.dependencies 追加：

```kotlin
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.androidx.securityCrypto)
```

wasmJsMain.dependencies 追加：

```kotlin
            implementation(libs.ktor.clientJs)
```

plugins 追加 `alias(libs.plugins.kotlinSerialization)`。

- [ ] **Step 6: webApp 移除 js target**

`app/webApp/build.gradle.kts` 的 kotlin 块只留：

```kotlin
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    // sourceSets 不变
}
```

- [ ] **Step 7: 版本号 0.0.1**

`server/build.gradle.kts`：`version = "0.0.1"`。`app/androidApp/build.gradle.kts`：`versionName = "0.0.1"`（versionCode 保持 1）。

- [ ] **Step 8: docs/README.md 状态行**

把设计文档一行状态"待审核"改为"已定稿"。

- [ ] **Step 9: 验证构建配置**

Run: `./gradlew :core:compileKotlinWasmJs :server:compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL（首次会经镜像下载依赖，分钟级）。

- [ ] **Step 10: 初始提交（用户已批准：脚手架 + 文档）**

```bash
git add -A && git commit -m "chore: initial KMP scaffold + v0.0.1 design docs + build slimming"
```

---

### Task 1: :core DTO 与错误码

**Files:**
- Create: `core/src/commonMain/kotlin/com/linan/barezen_drive/core/dto/Dto.kt`
- Create: `core/src/commonMain/kotlin/com/linan/barezen_drive/core/dto/ErrorCode.kt`
- Test: `core/src/commonTest/kotlin/com/linan/barezen_drive/core/dto/DtoSerializationTest.kt`

**Interfaces:**
- Produces: `UserDto`, `FolderDto`, `FileDto`, `ContentsResponse`, `LoginRequest/RegisterRequest/RefreshRequest`, `LoginResponse/RefreshResponse`, `CreateFolderRequest`, `RenameFolderRequest`, `UpdateFileRequest`, `UploadInitRequest/UploadInitResponse`, `UploadCompleteResponse`, `ErrorResponse`, `ErrorCodes`（全部 @Serializable；id/folderId 一律 `String`）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive.core.dto

import kotlinx.serialization.json.Json
import kotlin.test.*

class DtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun fileDtoRoundTrip() {
        val f = FileDto("id1", "a.jpg", "fold1", 123L, "image/jpeg", "ab".repeat(32), "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z")
        assertEquals(f, json.decodeFromString(json.encodeToString(f)))
    }

    @Test
    fun uploadInitResponseDefaults() {
        val decoded = json.decodeFromString<UploadInitResponse>("""{"uploadId":"u1","chunkSize":5242880,"receivedChunks":[0,2]}""")
        assertFalse(decoded.instantUpload); assertEquals(null, decoded.file); assertEquals(listOf(0, 2), decoded.receivedChunks)
    }

    @Test
    fun errorEnvelopeShape() {
        val e = json.decodeFromString<ErrorResponse>("""{"error":{"code":"NAME_CONFLICT","message":"x"}}""")
        assertEquals(ErrorCodes.NAME_CONFLICT, e.error.code)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:jvmTest --tests "*DtoSerializationTest*" --console=plain`
Expected: FAIL（未解析引用）

- [ ] **Step 3: 实现**

`ErrorCode.kt`：

```kotlin
package com.linan.barezen_drive.core.dto

object ErrorCodes {
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val TOKEN_INVALID = "TOKEN_INVALID"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val USERNAME_INVALID = "USERNAME_INVALID"
    const val USERNAME_TAKEN = "USERNAME_TAKEN"
    const val PASSWORD_TOO_SHORT = "PASSWORD_TOO_SHORT"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val NOT_FOUND = "NOT_FOUND"
    const val NAME_CONFLICT = "NAME_CONFLICT"
    const val FOLDER_INTO_DESCENDANT = "FOLDER_INTO_DESCENDANT"
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    const val SESSION_COMPLETED = "SESSION_COMPLETED"
    const val SESSION_EXPIRED = "SESSION_EXPIRED"
    const val CHUNK_INVALID = "CHUNK_INVALID"
    const val CHUNK_MISSING = "CHUNK_MISSING"
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
```

`Dto.kt`：

```kotlin
package com.linan.barezen_drive.core.dto

import kotlinx.serialization.Serializable

@Serializable data class UserDto(val id: String, val username: String, val createdAt: String)
@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class RegisterRequest(val username: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class LoginResponse(val accessToken: String, val refreshToken: String, val user: UserDto)
@Serializable data class RefreshResponse(val accessToken: String, val refreshToken: String)
@Serializable data class FolderDto(val id: String, val name: String, val parentId: String?, val createdAt: String, val updatedAt: String)
@Serializable data class FileDto(val id: String, val name: String, val folderId: String?, val size: Long, val mimeType: String?, val sha256: String, val createdAt: String, val updatedAt: String)
@Serializable data class ContentsResponse(val folder: FolderDto?, val folders: List<FolderDto>, val files: List<FileDto>)
@Serializable data class CreateFolderRequest(val parentId: String?, val name: String)
@Serializable data class RenameFolderRequest(val name: String)
@Serializable data class UpdateFileRequest(val name: String? = null, val folderId: String? = null)
@Serializable data class UploadInitRequest(val folderId: String? = null, val name: String, val size: Long, val mimeType: String? = null, val sha256: String? = null, val chunkSize: Long? = null)
@Serializable data class UploadInitResponse(val uploadId: String, val chunkSize: Long, val receivedChunks: List<Int> = emptyList(), val instantUpload: Boolean = false, val file: FileDto? = null)
@Serializable data class UploadCompleteResponse(val file: FileDto)
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class ErrorResponse(val error: ApiError)
```

- [ ] **Step 4: 测试通过**

Run: `./gradlew :core:jvmTest --tests "*DtoSerializationTest*" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(core): shared API DTOs and error codes"
```

---

### Task 2: Server 骨架 —— 配置、数据库、表、装配、health

**Files:**
- Create: `server/src/main/kotlin/com/linan/barezen_drive/config/AppConfig.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/db/DatabaseFactory.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/db/Tables.kt`
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/Application.kt`（整文件替换）
- Modify: `server/build.gradle.kts`（deps）
- Modify: `server/src/test/kotlin/com/linan/barezen_drive/ApplicationTest.kt`（整文件替换）

**Interfaces:**
- Produces: `AppConfig(port, jdbcUrl, dbUser, dbPassword, jwtSecret, storageDir, maxFileSize)` 与 `AppConfig.fromEnv()`；`DatabaseFactory.connect(jdbcUrl, user, pass)`；6 张 Exposed 表对象；`Application.module(cfg, storage)`（storage 参数 Task 3 才有，本 Task 先用局部实现存根 `StorageProvider` 接口的最小版本？——不，Task 3 定义接口；本 Task module 签名 `Application.module(cfg: AppConfig)`，Task 7 扩为 `module(cfg, storage)`）。` health GET /health。
- Consumes: 无。

- [ ] **Step 1: server/build.gradle.kts 依赖**

dependencies 块追加：

```kotlin
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationJson)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverPartialContent)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.bcrypt)
    testImplementation(libs.h2)
```

- [ ] **Step 2: 写失败测试（替换 ApplicationTest.kt）**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {
    private fun testConfig() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = java.nio.file.Files.createTempDirectory("bz-test").toString(),
        maxFileSize = 1L shl 30,
    )

    @Test
    fun healthOk() = testApplication {
        val cfg = testConfig()
        application { module(cfg) }
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("ok"))
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :server:test --console=plain`
Expected: FAIL（AppConfig/module 未定义）

- [ ] **Step 4: 实现 AppConfig / Tables / DatabaseFactory / Application**

`config/AppConfig.kt`：

```kotlin
package com.linan.barezen_drive.config

data class AppConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val storageDir: String,
    val maxFileSize: Long,
) {
    companion object {
        private fun env(name: String, default: String? = null): String =
            System.getenv(name) ?: default ?: error("缺少环境变量 $name")

        fun fromEnv(): AppConfig = AppConfig(
            port = env("SERVER_PORT", "8080").toInt(),
            jdbcUrl = env("JDBC_URL"),
            dbUser = env("DB_USER"),
            dbPassword = env("DB_PASSWORD"),
            jwtSecret = env("JWT_SECRET").also { require(it.length >= 32) { "JWT_SECRET 至少 32 字节" } },
            storageDir = env("STORAGE_DIR", "./data/storage"),
            maxFileSize = env("MAX_FILE_SIZE", (10L * 1024 * 1024 * 1024).toString()).toLong(),
        )
    }
}
```

`db/Tables.kt`：

```kotlin
package com.linan.barezen_drive.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object UsersTable : Table("users") {
    val id = uuid("id")
    val username = varchar("username", 32).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokensTable : Table("refresh_tokens") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FoldersTable : Table("folders") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val parent = uuid("parent_id").references(FoldersTable.id).nullable()
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(user, parent, name) }
}

object FilesTable : Table("files") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val folder = uuid("folder_id").references(FoldersTable.id).nullable()
    val name = varchar("name", 255)
    val size = long("size")
    val mimeType = varchar("mime_type", 255).nullable()
    val sha256 = varchar("sha256", 64)
    val storageKey = varchar("storage_key", 512)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(user, folder, name) }
}

object UploadSessionsTable : Table("upload_sessions") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val folder = uuid("folder_id").references(FoldersTable.id).nullable()
    val name = varchar("name", 255)
    val size = long("size")
    val mimeType = varchar("mime_type", 255).nullable()
    val chunkSize = long("chunk_size")
    val clientSha256 = varchar("client_sha256", 64).nullable()
    val status = varchar("status", 16).clientDefault { "open" }
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(id)
}

object UploadChunksTable : Table("upload_chunks") {
    val session = uuid("session_id").references(UploadSessionsTable.id)
    val chunkIndex = integer("chunk_index")
    val size = long("size")
    override val primaryKey = PrimaryKey(session, chunkIndex)
}
```

`db/DatabaseFactory.kt`：

```kotlin
package com.linan.barezen_drive.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    val ALL_TABLES = arrayOf(UsersTable, RefreshTokensTable, FoldersTable, FilesTable, UploadSessionsTable, UploadChunksTable)

    fun connect(jdbcUrl: String, user: String, password: String) {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = jdbcUrl; username = user; password = password
            maximumPoolSize = 5; isAutoCommit = false
        })
        Database.connect(ds)
        transaction { SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES) }
    }
}
```

`Application.kt`（整文件替换）：

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    val cfg = AppConfig.fromEnv()
    DatabaseFactory.connect(cfg.jdbcUrl, cfg.dbUser, cfg.dbPassword)
    embeddedServer(Netty, port = cfg.port) { module(cfg) }.start(wait = true)
}

fun Application.module(cfg: AppConfig) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(CallLogging)
    routing {
        get("/health") { call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json) }
    }
}
```

（注：Exposed `javatime.timestamp` 需要 `org.jetbrains.exposed:exposed-jdbc`（含）——0.61 中 `javatime` 扩展在 exposed-core 内，若编译缺类则改用 `long("...")` 存 epochMilli —— 这是允许的唯一调整点，二者行为等价，转换写在 DTO 映射处。）

- [ ] **Step 5: 测试通过**

Run: `./gradlew :server:test --console=plain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(server): config, PostgreSQL schema via Exposed, module skeleton with /health"
```

---

### Task 3: 存储层 StorageProvider + LocalStorageProvider

**Files:**
- Create: `server/src/main/kotlin/com/linan/barezen_drive/storage/StorageProvider.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/storage/LocalStorageProvider.kt`
- Test: `server/src/test/kotlin/com/linan/barezen_drive/StorageTest.kt`

**Interfaces:**
- Produces: `interface StorageProvider { fun blobKey(sha256: String): String; suspend fun put(key, channel); suspend fun get(key): ByteReadChannel; suspend fun delete(key); suspend fun exists(key): Boolean }`；`class LocalStorageProvider(root: java.nio.file.Path)`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class StorageTest {
    private fun sha(n: Int) = n.toString(16).padStart(64, '0')

    @Test
    fun putGetDeleteRoundTrip() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("st"))
        val key = storage.blobKey(sha(1))
        assertFalse(storage.exists(key))
        storage.put(key, ByteReadChannel("hello blob".encodeToByteArray()))
        assertTrue(storage.exists(key))
        assertEquals("hello blob", storage.get(key).toByteArray().decodeToString())
        storage.delete(key)
        assertFalse(storage.exists(key))
    }

    @Test
    fun putTwiceSkipsRewrite() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("st"))
        val key = storage.blobKey(sha(2))
        storage.put(key, ByteReadChannel("v1".encodeToByteArray()))
        storage.put(key, ByteReadChannel("V2-DIFFERENT".encodeToByteArray())) // 已存在 -> 丢弃，不覆盖
        assertEquals("v1", storage.get(key).toByteArray().decodeToString())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :server:test --tests "*StorageTest*" --console=plain` -> FAIL

- [ ] **Step 3: 实现**

`storage/StorageProvider.kt`：

```kotlin
package com.linan.barezen_drive.storage

import io.ktor.utils.io.*

interface StorageProvider {
    fun blobKey(sha256: String): String
    suspend fun put(key: String, channel: ByteReadChannel)
    suspend fun get(key: String): ByteReadChannel
    suspend fun delete(key: String)
    suspend fun exists(key: String): Boolean
}
```

`storage/LocalStorageProvider.kt`：

```kotlin
package com.linan.barezen_drive.storage

import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class LocalStorageProvider(private val root: Path) : StorageProvider {
    private val blobsDir = root.resolve("blobs")
    val tmpDir: Path get() = root.resolve("tmp")

    init {
        Files.createDirectories(blobsDir); Files.createDirectories(tmpDir)
    }

    override fun blobKey(sha256: String): String =
        "blobs/${sha256.substring(0, 2)}/${sha256.substring(2, 4)}/$sha256"

    override suspend fun put(key: String, channel: ByteReadChannel) = withContext(Dispatchers.IO) {
        val target = root.resolve(key)
        if (Files.exists(target)) { channel.discard(); return@withContext }
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "put-", ".tmp")
        try {
            FileOutputStream(tmp.toFile()).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            Files.deleteIfExists(tmp); throw t
        } finally {
            channel.cancel()
        }
    }

    override suspend fun get(key: String): ByteReadChannel = withContext(Dispatchers.IO) {
        java.io.FileInputStream(root.resolve(key).toFile()).toByteReadChannel()
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(root.resolve(key)); Unit
    }

    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(root.resolve(key))
    }
}
```

（`toByteReadChannel` 导入：`io.ktor.utils.io.jvm.javaio.toByteReadChannel`。`readAvailable(ByteArray, Int, Int)` 在 ktor 3 返回 `Int`，`-1` 为 EOF。）

- [ ] **Step 4: 测试通过**

Run: `./gradlew :server:test --tests "*StorageTest*" --console=plain` -> PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(server): content-addressed StorageProvider with local disk implementation"
```

---

### Task 4: 认证 + 统一错误处理

**Files:**
- Create: `server/src/main/kotlin/com/linan/barezen_drive/api/ApiException.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/auth/PasswordHasher.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/auth/JwtService.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/auth/AuthService.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/auth/AuthRoutes.kt`
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/Application.kt`（装 plugin + auth 路由 + jwt）
- Test: `server/src/test/kotlin/com/linan/barezen_drive/AuthTest.kt`

**Interfaces:**
- Produces: `class ApiException(val code: String, val status: HttpStatusCode, override val message: String)`；`PasswordHasher.hash/verify`；`JwtService.init(secret)/issue(userId)/verifier`；`AuthService.register/login/refresh/createTokens`；`ApplicationCall.userId: UUID` 扩展；路由 `POST /api/auth/register|login|refresh`、`GET /api/me`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class AuthTest {
    private fun testConfig() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = java.nio.file.Files.createTempDirectory("bz-auth").toString(),
        maxFileSize = 1L shl 30,
    )

    private suspend fun ApplicationTestBuilder.setup() = testConfig().also { cfg -> application { module(cfg) } }

    @Test
    fun registerLoginRefreshFlow() = testApplication {
        setup()
        val c = client
        val reg = c.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"linan","password":"password123"}""") }
        assertEquals(HttpStatusCode.Created, reg.status)

        val dup = c.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"linan","password":"password123"}""") }
        assertEquals(HttpStatusCode.Conflict, dup.status); assertTrue(dup.bodyAsText().contains("USERNAME_TAKEN"))

        val bad = c.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"x","password":"123"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val login = c.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"linan","password":"password123"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
        val tokens = Regex(""""accessToken":"([^"]+)","refreshToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValue.let { it }

        val me = c.get("/api/me") { header(HttpHeaders.Authorization, "Bearer " + tokens.substringBefore("\",\"").removePrefix("\"accessToken\":\"")) }
        assertEquals(HttpStatusCode.OK, me.status)

        val noAuth = c.get("/api/me")
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)

        val refresh = c.post("/api/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${tokens.substringAfter("refreshToken\":\"").removeSuffix("\"}")}""") }
        assertEquals(HttpStatusCode.OK, refresh.status)
        val refresh2 = c.post("/api/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${tokens.substringAfter("refreshToken\":\"").removeSuffix("\"}")}""") }
        assertEquals(HttpStatusCode.Unauthorized, refresh2.status) // 轮换后旧 token 失效
    }

    @Test
    fun wrongPassword401() = testApplication {
        setup()
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"bob","password":"password123"}""") }
        val res = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"bob","password":"wrongpass1"}""") }
        assertEquals(HttpStatusCode.Unauthorized, res.status); assertTrue(res.bodyAsText().contains("INVALID_CREDENTIALS"))
    }
}
```

（测试里的 token 提取写得太绕 —— 执行时允许把上述正则提取简化为对 `login.bodyAsText()` 的 `kotlinx.serialization` 解析，行为不变。）

- [ ] **Step 2: 运行确认失败**：`./gradlew :server:test --tests "*AuthTest*" --console=plain` -> FAIL

- [ ] **Step 3: 实现**

`api/ApiException.kt`：

```kotlin
package com.linan.barezen_drive.api

import com.linan.barezen_drive.core.dto.ApiError
import com.linan.barezen_drive.core.dto.ErrorCodes
import io.ktor.http.*

class ApiException(val code: String, val status: HttpStatusCode, override val message: String) : RuntimeException(message) {
    val error get() = ApiError(code, message)
    companion object {
        fun badRequest(message: String, code: String = ErrorCodes.VALIDATION_ERROR) = ApiException(code, HttpStatusCode.BadRequest, message)
        fun notFound(message: String = "资源不存在") = ApiException(ErrorCodes.NOT_FOUND, HttpStatusCode.NotFound, message)
        fun conflict(code: String, message: String) = ApiException(code, HttpStatusCode.Conflict, message)
        fun unauthorized(message: String, code: String = ErrorCodes.INVALID_CREDENTIALS) = ApiException(code, HttpStatusCode.Unauthorized, message)
    }
}
```

`auth/PasswordHasher.kt`：

```kotlin
package com.linan.barezen_drive.auth

import at.favre.lib.crypto.bcrypt.BCrypt

object PasswordHasher {
    private const val COST = 10
    fun hash(password: String): String = BCrypt.withDefaults().hashToString(COST, password.toCharArray())
    fun verify(password: String, hash: String): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}
```

`auth/JwtService.kt`：

```kotlin
package com.linan.barezen_drive.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

object JwtService {
    private const val ISSUER = "barezen"
    private const val TTL_MS = 15L * 60 * 1000
    private lateinit var algorithm: Algorithm

    fun init(secret: String) { algorithm = Algorithm.HMAC256(secret) }
    fun issue(userId: String): String =
        JWT.create().withIssuer(ISSUER).withClaim("sub", userId)
            .withExpiresAt(Date(System.currentTimeMillis() + TTL_MS))
            .sign(algorithm)
    val verifier: JWTVerifier get() = JWT.require(algorithm).withIssuer(ISSUER).build()
}
```

`auth/AuthService.kt`：

```kotlin
package com.linan.barezen_drive.auth

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.db.RefreshTokensTable
import com.linan.barezen_drive.db.UsersTable
import io.ktor.http.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

object AuthService {
    private const val REFRESH_TTL = Duration.ofDays(30)
    private val rnd = SecureRandom()
    private val USERNAME_RE = Regex("^[a-zA-Z0-9_]{3,32}$")

    private fun hashToken(t: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(t.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun newRefreshToken(): Pair<String, Instant> {
        val bytes = ByteArray(32); rnd.nextBytes(bytes)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return token to Instant.now().plus(REFRESH_TTL)
    }

    fun register(username: String, password: String): UserDto {
        if (!USERNAME_RE.matches(username)) throw ApiException.badRequest("用户名需 3-32 位字母数字下划线", ErrorCodes.USERNAME_INVALID)
        if (password.length < 8) throw ApiException.badRequest("密码至少 8 位", ErrorCodes.PASSWORD_TOO_SHORT)
        return transaction {
            if (UsersTable.selectAll().where { UsersTable.username eq username }.any()) {
                throw ApiException.conflict(ErrorCodes.USERNAME_TAKEN, "用户名已存在")
            }
            val id = UUID.randomUUID()
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.username] = username
                it[passwordHash] = PasswordHasher.hash(password)
            }
            UserDto(id.toString(), username, Instant.now().toString())
        }
    }

    fun login(username: String, password: String): LoginResponse = transaction {
        val row = UsersTable.selectAll().where { UsersTable.username eq username }.singleOrNull()
            ?: throw ApiException.unauthorized("用户名或密码错误")
        if (!PasswordHasher.verify(password, row[UsersTable.passwordHash])) {
            throw ApiException.unauthorized("用户名或密码错误")
        }
        val uid = row[UsersTable.id].value
        issueTokens(uid)
    }

    fun issueTokens(uid: UUID): LoginResponse {
        val (raw, exp) = newRefreshToken()
        transaction {
            RefreshTokensTable.insert {
                it[id] = UUID.randomUUID()
                it[user] = uid
                it[tokenHash] = hashToken(raw)
                it[expiresAt] = exp
            }
        }
        val user = transaction {
            val r = UsersTable.selectAll().where { UsersTable.id eq uid }.single()
            UserDto(r[UsersTable.id].value.toString(), r[UsersTable.username], r[UsersTable.createdAt].toString())
        }
        return LoginResponse(JwtService.issue(uid.toString()), raw, user)
    }

    fun refresh(raw: String): RefreshResponse = transaction {
        val h = hashToken(raw)
        val row = RefreshTokensTable.selectAll().where { RefreshTokensTable.tokenHash eq h }.singleOrNull()
            ?: throw ApiException.unauthorized("refresh token 无效", ErrorCodes.TOKEN_INVALID)
        if (row[RefreshTokensTable.revokedAt] != null) throw ApiException.unauthorized("refresh token 已失效", ErrorCodes.TOKEN_INVALID)
        if (row[RefreshTokensTable.expiresAt].toEpochMilli() < System.currentTimeMillis()) {
            throw ApiException.unauthorized("refresh token 已过期", ErrorCodes.TOKEN_EXPIRED)
        }
        val uid = row[RefreshTokensTable.user].value
        RefreshTokensTable.update({ RefreshTokensTable.tokenHash eq h }) { it[revokedAt] = Instant.now() }
        val (newRaw, exp) = newRefreshToken()
        RefreshTokensTable.insert {
            it[id] = UUID.randomUUID(); it[user] = uid
            it[tokenHash] = hashToken(newRaw); it[expiresAt] = exp
        }
        RefreshResponse(JwtService.issue(uid.toString()), newRaw)
    }
}
```

（若 Exposed 0.61 的 `timestamp()` 类型不是 `java.time.Instant` 而报编译错，按 Task 2 注同样的唯一调整点处理。）

`auth/AuthRoutes.kt`：

```kotlin
package com.linan.barezen_drive.auth

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.db.UsersTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

val ApplicationCall.userId: UUID
    get() = principal<JWTPrincipal>()?.payload?.getClaim("sub")?.asString()?.let { UUID.fromString(it) }
        ?: throw ApiException.unauthorized("未登录", "TOKEN_INVALID")

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            val req = call.receive<RegisterRequest>()
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { AuthService.register(req.username, req.password) })
        }
        post("/login") {
            val req = call.receive<LoginRequest>()
            call.respond(withContext(Dispatchers.IO) { AuthService.login(req.username, req.password) })
        }
        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            call.respond(withContext(Dispatchers.IO) { AuthService.refresh(req.refreshToken) })
        }
    }
    authenticate("auth-jwt") {
        get("/api/me") {
            val uid = call.userId
            val user = withContext(Dispatchers.IO) {
                transaction {
                    val r = UsersTable.selectAll().where { UsersTable.id eq uid }.single()
                    UserDto(r[UsersTable.id].value.toString(), r[UsersTable.username], r[UsersTable.createdAt].toString())
                }
            }
            call.respond(user)
        }
    }
}
```

`Application.kt` 的 `module()` 补齐（在 install(ContentNegotiation) 之后）：

```kotlin
    install(StatusPages) {
        exception<com.linan.barezen_drive.api.ApiException> { call, cause ->
            call.respond(cause.status, com.linan.barezen_drive.core.dto.ErrorResponse(cause.error))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled", cause)
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                com.linan.barezen_drive.core.dto.ErrorResponse(
                    com.linan.barezen_drive.core.dto.ApiError(com.linan.barezen_drive.core.dto.ErrorCodes.INTERNAL_ERROR, "服务器内部错误")))
        }
    }
    install(io.ktor.server.auth.Authentication) {
        jwt("auth-jwt") {
            realm = "barezen"
            verifier(com.linan.barezen_drive.auth.JwtService.verifier)
            validate { cred -> cred.payload.getClaim("sub")?.asString()?.let { JWTPrincipal(it.payload) } }
            challenge { _, _ ->
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized,
                    com.linan.barezen_drive.core.dto.ErrorResponse(
                        com.linan.barezen_drive.core.dto.ApiError(com.linan.barezen_drive.core.dto.ErrorCodes.TOKEN_INVALID, "未登录或 token 无效")))
            }
        }
    }
```

main() 中 `DatabaseFactory.connect(...)` 后加 `com.linan.barezen_drive.auth.JwtService.init(cfg.jwtSecret)`；`module()` 里测试路径也要 init（module 开头加 `JwtService.init(cfg.jwtSecret)`，幂等）。

routing 里 `authRoutes()` 加入。

- [ ] **Step 4: 测试通过**：`./gradlew :server:test --console=plain` -> PASS
- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(server): auth (register/login/refresh rotation, JWT), unified error envelope"
```

---

### Task 5: 虚拟文件系统 —— 文件夹与文件元数据

**Files:**
- Create: `server/src/main/kotlin/com/linan/barezen_drive/files/FileService.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/files/FolderRoutes.kt`（含 file 的 PATCH/DELETE 路由）
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/Application.kt`（routing 挂载；module 签名改为 `module(cfg, storage: StorageProvider)`，main/测试传 LocalStorageProvider）
- Test: `server/src/test/kotlin/com/linan/barezen_drive/FolderFileTest.kt`

**Interfaces:**
- Produces: `FileService.contents(userId, folderId)`, `.createFolder(userId, parentId, name)`, `.renameFolder(userId, id, name)`, `.deleteFolder(userId, id): List<String>`（返回待物理删除的 storage key 列表，由路由层在事务外删）, `.updateFile(userId, id, name?, folderId?)`, `.deleteFile(userId, id): List<String>`, `.getFileMeta(userId, id)`；DTO 映射 `row.toFolderDto()/toFileDto()`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

class FolderFileTest {
    private val storageDir = Files.createTempDirectory("bz-ff").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
    private fun setup() = testApplication {
        val c = cfg(); val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"u1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"u1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }
    private var auth = ""

    private suspend fun ApplicationTestBuilder.mkFolder(parent: String?, name: String): String {
        val body = if (parent == null) """{"name":"$name"}""" else """{"parentId":"$parent","name":"$name"}"""
        val r = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, r.status)
        return Regex(""""id":"([^"]+)"""").find(r.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun folderCrudAndConflicts() = testApplication {
        setup()
        val a = mkFolder(null, "Photos")
        val b = mkFolder(a, "2026")
        // 列 root：只有 Photos
        var list = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        assertTrue(list.bodyAsText().contains("Photos")); assertTrue(!Regex(""""name":"2026"""").containsMatchIn(list.bodyAsText()))
        // 重名冲突
        val dup = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"Photos"}""") }
        assertEquals(HttpStatusCode.Conflict, dup.status); assertTrue(dup.bodyAsText().contains("NAME_CONFLICT"))
        // 重命名
        val ren = client.patch("/api/folders/$b") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"2027"}""") }
        assertEquals(HttpStatusCode.OK, ren.status); assertTrue(ren.bodyAsText().contains("2027"))
        // 移入自己的子孙 -> 400
        val intoSelf = client.patch("/api/folders/$a") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"Photos","folderId":"$b"}""") }
        assertEquals(HttpStatusCode.BadRequest, intoSelf.status); assertTrue(intoSelf.bodyAsText().contains("FOLDER_INTO_DESCENDANT"))
        // 递归删除 a -> b 也没了
        val del = client.delete("/api/folders/$a") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        list = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        assertFalse(list.bodyAsText().contains("Photos"))
    }

    @Test
    fun fileEndpoints404WithoutFiles() = testApplication {
        setup()
        // v0.0.1 只有上传能产生文件记录（Task 6），本测试验证对不存在文件的操作返回 404；
        // 重命名/移动/删除的正向路径由 Task 6 的 UploadTest 以真实上传的文件覆盖。
        val miss = client.patch("/api/files/00000000-0000-0000-0000-000000000000") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"x"}""") }
        assertEquals(HttpStatusCode.NotFound, miss.status); assertTrue(miss.bodyAsText().contains("NOT_FOUND"))
        val delMiss = client.delete("/api/files/00000000-0000-0000-0000-000000000000") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, delMiss.status); assertTrue(delMiss.bodyAsText().contains("NOT_FOUND"))
    }
}
```

（注：文件重命名/移动/删除的正向路径在 Task 6 的 `UploadTest` 中以真实上传的文件覆盖 —— 因为 v0.0.1 只有上传能产生文件记录。**同时 Task 6 的 UploadTest 需追加一条用例**：上传成功后 PATCH 重命名 -> 200 且名字变更；PATCH `folderId` 移入子文件夹 -> 200；`GET` 旧目录 contents 不再包含该文件；DELETE -> 204 且 contents 为空、blob 引用计数正确。）

- [ ] **Step 2: 运行确认失败**：`./gradlew :server:test --tests "*FolderFileTest*" --console=plain` -> FAIL
- [ ] **Step 3: 实现**

`files/FileService.kt`：

```kotlin
package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.core.dto.ErrorCodes
import io.ktor.http.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

data class FileMeta(val id: UUID, val userId: UUID, val folderId: UUID?, val name: String, val size: Long, val mimeType: String?, val sha256: String, val storageKey: String)

object FileService {
    private fun nameOk(name: String) = name.isNotBlank() && name.length <= 255 && !name.contains('/')

    fun contents(userId: UUID, folderId: String): ContentsResponse = transaction {
        val parent: UUID? = if (folderId == "root") null else {
            val id = UUID.fromString(folderId)
            FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
                ?: throw ApiException.notFound("文件夹不存在")
            id
        }
        val folders = (if (parent == null) {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() }
        } else {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) }
        }).map { it.toFolderDto() }
        val files = (if (parent == null) {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() }
        } else {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) }
        }).map { it.toFileDto() }
        val cur = parent?.let { pid -> FoldersTable.selectAll().where { FoldersTable.id eq pid }.single().toFolderDto() }
        ContentsResponse(cur, folders.sortedBy { it.name.lowercase() }, files.sortedBy { it.name.lowercase() })
    }

    private fun nameConflict(userId: UUID, parent: UUID?, name: String, excludeFolder: UUID? = null, excludeFile: UUID? = null): Boolean {
        val folderHit = (if (parent == null) FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() and (FoldersTable.name eq name) }
        else FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) and (FoldersTable.name eq name) }).any()
        val fileHit = (if (parent == null) FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.name eq name) }
        else FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.name eq name) }).any()
        return folderHit || fileHit
    }

    fun createFolder(userId: UUID, parentId: String?, name: String): FolderDto = transaction {
        if (!nameOk(name)) throw ApiException.badRequest("名称非法")
        val parent: UUID? = parentId?.let {
            val pid = UUID.fromString(it)
            FoldersTable.selectAll().where { (FoldersTable.id eq pid) and (FoldersTable.user eq userId) }.singleOrNull()
                ?: throw ApiException.notFound("目标文件夹不存在")
            pid
        }
        if (nameConflict(userId, parent, name)) throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件夹或文件")
        val id = UUID.randomUUID()
        FoldersTable.insert {
            it[FoldersTable.id] = id; it[user] = userId; it[FoldersTable.parent] = parent; it[FoldersTable.name] = name
        }
        FolderDto(id.toString(), name, parentId, Instant.now().toString(), Instant.now().toString())
    }

    fun renameFolder(userId: UUID, id: UUID, newName: String): FolderDto = transaction {
        val row = FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件夹不存在")
        if (!nameOk(newName)) throw ApiException.badRequest("名称非法")
        if (nameConflict(userId, row[FoldersTable.parent]?.value, newName, excludeFolder = id)) {
            throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件夹或文件")
        }
        FoldersTable.update({ FoldersTable.id eq id }) { it[name] = newName; it[updatedAt] = Instant.now() }
        row.toFolderDto().copy(name = newName)
    }

    /** 返回递归删除后需要物理清理的 storage key（引用计数为 0 的 blob） */
    fun deleteFolder(userId: UUID, id: UUID): List<String> = transaction {
        FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件夹不存在")
        val all = mutableListOf(id)
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            FoldersTable.selectAll().where { FoldersTable.parent eq cur }.forEach { r ->
                val cid = r[FoldersTable.id].value; all.add(cid); queue.add(cid)
            }
        }
        val keys = mutableListOf<String>()
        for (fid in all) {
            FilesTable.selectAll().where { FilesTable.folder eq fid }.forEach { r ->
                keys.add(r[FilesTable.storageKey]); r[FilesTable.id].value
            }
        }
        for (fid in all) FilesTable.deleteWhere { folder eq fid }
        FoldersTable.deleteWhere { FoldersTable.id inList all }
        keys.distinct().filter { key ->
            FilesTable.selectAll().where { FilesTable.storageKey eq key }.count() == 0L
        }
    }

    fun getFileMeta(userId: UUID, id: UUID): FileMeta = transaction {
        val r = FilesTable.selectAll().where { (FilesTable.id eq id) and (FilesTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件不存在")
        FileMeta(r[FilesTable.id].value, r[FilesTable.user].value, r[FilesTable.folder]?.value, r[FilesTable.name], r[FilesTable.size], r[FilesTable.mimeType], r[FilesTable.sha256], r[FilesTable.storageKey])
    }

    fun updateFile(userId: UUID, id: UUID, newName: String?, newFolderId: String?): FileDto = transaction {
        val cur = getFileMeta(userId, id)
        if (newName != null) {
            if (!nameOk(newName)) throw ApiException.badRequest("名称非法")
        }
        val target: UUID? = when {
            newFolderId == null -> cur.folderId
            newFolderId == "root" -> null
            else -> {
                val tid = UUID.fromString(newFolderId)
                FoldersTable.selectAll().where { (FoldersTable.id eq tid) and (FoldersTable.user eq userId) }.singleOrNull()
                    ?: throw ApiException.notFound("目标文件夹不存在")
                tid
            }
        }
        val finalName = newName ?: cur.name
        // 移动目标不能是自己所在位置的子树 —— 文件无子树，仅需重名检查
        if (nameConflict(userId, target, finalName, excludeFile = id)) {
            throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "目标位置已存在同名文件夹或文件")
        }
        FilesTable.update({ FilesTable.id eq id }) {
            if (newName != null) it[name] = newName
            it[folder] = target
            it[updatedAt] = Instant.now()
        }
        FileDto(id.toString(), finalName, target?.toString(), cur.size, cur.mimeType, cur.sha256, Instant.now().toString(), Instant.now().toString())
    }

    fun deleteFile(userId: UUID, id: UUID): List<String> = transaction {
        val cur = getFileMeta(userId, id)
        FilesTable.deleteWhere { FilesTable.id eq id }
        if (FilesTable.selectAll().where { FilesTable.storageKey eq cur.storageKey }.count() == 0L) listOf(cur.storageKey) else emptyList()
    }
}

internal fun ResultRow.toFolderDto(): FolderDto = FolderDto(
    this[FoldersTable.id].value.toString(), this[FoldersTable.name],
    this[FoldersTable.parent]?.value?.toString(), this[FoldersTable.createdAt].toString(), this[FoldersTable.updatedAt].toString(),
)

internal fun ResultRow.toFileDto(): FileDto = FileDto(
    this[FilesTable.id].value.toString(), this[FilesTable.name], this[FilesTable.folder]?.value?.toString(),
    this[FilesTable.size], this[FilesTable.mimeType], this[FilesTable.sha256],
    this[FilesTable.createdAt].toString(), this[FilesTable.updatedAt].toString(),
)
```

`files/FolderRoutes.kt`：

```kotlin
package com.linan.barezen_drive.files

import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

fun Route.folderRoutes(storage: StorageProvider) {
    route("/api/folders") {
        get("/{id}/contents") {
            call.respond(withContext(Dispatchers.IO) { FileService.contents(call.userId, call.parameters["id"]!!) })
        }
        post {
            val req = call.receive<CreateFolderRequest>()
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { FileService.createFolder(call.userId, req.parentId, req.name) })
        }
        patch("/{id}") {
            val req = call.receive<RenameFolderRequest>()
            val id = UUID.fromString(call.parameters["id"]!!)
            call.respond(withContext(Dispatchers.IO) { FileService.renameFolder(call.userId, id, req.name) })
        }
        delete("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val keys = withContext(Dispatchers.IO) { FileService.deleteFolder(call.userId, id) }
            keys.forEach { storage.delete(it) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/api/files") {
        patch("/{id}") {
            val req = call.receive<UpdateFileRequest>()
            val id = UUID.fromString(call.parameters["id"]!!)
            call.respond(withContext(Dispatchers.IO) { FileService.updateFile(call.userId, id, req.name, req.folderId) })
        }
        delete("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val keys = withContext(Dispatchers.IO) { FileService.deleteFile(call.userId, id) }
            keys.forEach { storage.delete(it) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

Application.kt：`fun Application.module(cfg: AppConfig, storage: com.linan.barezen_drive.storage.StorageProvider)`；routing 加 `folderRoutes(storage)`；main() 传 `LocalStorageProvider(java.nio.file.Path.of(cfg.storageDir))`；Task 2 测试同步加 storage 参数。

- [ ] **Step 4: 测试通过**：`./gradlew :server:test --console=plain` -> PASS
- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(server): virtual filesystem — folders CRUD, file rename/move/delete, blob refcount"
```

---

### Task 6: 分块上传协议（含断点续传、秒传、清理任务）

**Files:**
- Create: `server/src/main/kotlin/com/linan/barezen_drive/files/UploadService.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/files/UploadRoutes.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/jobs/UploadCleanupJob.kt`
- Modify: `Application.kt`（路由挂载 + 清理任务启动）
- Test: `server/src/test/kotlin/com/linan/barezen_drive/UploadTest.kt`

**Interfaces:**
- Produces: `UploadService.initUpload(userId, req): UploadInitResponse`、`.putChunk(userId, sessionId, index, body: ByteArray, chunkSha: String?)`、`.complete(userId, sessionId, storage): UploadCompleteResponse`、`.abort(userId, sessionId)`、`.cleanupExpired(storage)`；路由 `POST /api/uploads/init`、`PUT /api/uploads/{id}/chunks/{index}`、`POST /api/uploads/{id}/complete`、`DELETE /api/uploads/{id}`。
- Consumes: Task 3 `StorageProvider`、Task 5 `FileService.nameConflict` 逻辑（通过内部复用实现，不直接调用）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

class UploadTest {
    private val storageDir = Files.createTempDirectory("bz-up").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
    private fun setup() = testApplication {
        val c = cfg(); val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"u1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"u1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }
    private var auth = ""

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.upload(fileBody: ByteArray, name: String, sha: String? = null, folderId: String? = null): HttpResponse {
        val initBody = buildString {
            append("""{"name":"$name","size":${fileBody.size}""")
            if (folderId != null) append(""","folderId":"$folderId"""")
            if (sha != null) append(""","sha256":"$sha"""")
            append(""","chunkSize":8}""") // 8 字节分块，逼出多块
        }
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody) }
        assertEquals(HttpStatusCode.OK, init.status)
        val text = init.bodyAsText()
        if (text.contains(""""instantUpload":true""")) return init
        val uploadId = Regex(""""uploadId":"([^"]+)"""").find(text)!!.groupValues[1]
        var i = 0
        while (i * 8 < fileBody.size) {
            val from = i * 8; val to = minOf(from + 8, fileBody.size)
            val chunk = fileBody.copyOfRange(from, to)
            val put = client.put("/api/uploads/$uploadId/chunks/$i") {
                header(HttpHeaders.Authorization, auth); setBody(chunk)
            }
            assertEquals(HttpStatusCode.NoContent, put.status, "chunk $i: ${put.bodyAsText()}")
            i++
        }
        return client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
    }

    @Test
    fun multiChunkUploadThenDownloadReady() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray() // 20 字节 -> 3 块(8,8,4)
        val res = upload(body, "notes.txt")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val fileId = Regex(""""id":"([^"]+)"""").find(res.bodyAsText())!!.groupValues[1]
        val dl = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(body.decodeToString(), dl.bodyAsText())
        // 目录里可见
        val list = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        assertTrue(list.bodyAsText().contains("notes.txt"))
    }

    @Test
    fun instantUploadBySha() = testApplication {
        setup()
        val body = "same-content-xyz".encodeToByteArray()
        upload(body, "a.txt")
        val res = upload(body, "b.txt", sha = sha256hex(body))
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertTrue(res.bodyAsText().contains(""""instantUpload":true"""))
    }

    @Test
    fun resumeWithReceivedChunks() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"r.bin","size":${body.size},"chunkSize":8}""") }
        val uploadId = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(0, 8)) }
        // 模拟重连：同参数再次 init -> receivedChunks=[0]
        val re = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"r.bin","size":${body.size},"chunkSize":8}""") }
        assertTrue(re.bodyAsText().contains(""""receivedChunks":[0]"""))
        client.put("/api/uploads/$uploadId/chunks/1") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(8, 16)) }
        client.put("/api/uploads/$uploadId/chunks/2") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(16, 20)) }
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
    }

    @Test
    fun completeMissingChunkFails() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"m.bin","size":${body.size},"chunkSize":8}""") }
        val uploadId = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(0, 8)) }
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, done.status); assertTrue(done.bodyAsText().contains("CHUNK_MISSING"))
    }

    @Test
    fun wrongChunkShaRejected() = testApplication {
        setup()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"s.bin","size":8,"chunkSize":8}""") }
        val uploadId = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        val bad = client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(ByteArray(8)); header("X-Chunk-Sha256", "deadbeef" + "0".repeat(56)) }
        assertEquals(HttpStatusCode.BadRequest, bad.status); assertTrue(bad.bodyAsText().contains("CHUNK_INVALID"))
    }

    @Test
    fun abortCleansSession() = testApplication {
        setup()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"x.bin","size":16,"chunkSize":8}""") }
        val uploadId = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        val del = client.delete("/api/uploads/$uploadId") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, done.status)
    }
}
```

- [ ] **Step 2: 运行确认失败** -> FAIL

- [ ] **Step 3: 实现**

`files/UploadService.kt`：

```kotlin
package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.db.*
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.utils.io.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.io.path.outputStream

object UploadService {
    private val MIB = 1024L * 1024
    const val DEFAULT_CHUNK = 5L * MIB
    const val MIN_CHUNK = MIB
    const val MAX_CHUNK = 20L * MIB
    private const val SESSION_TTL_HOURS = 24L

    private fun expectedChunks(size: Long, chunkSize: Long) = ((size + chunkSize - 1) / chunkSize).toInt()

    private fun openSession(userId: UUID, sessionId: UUID): ResultRow {
        val row = transaction {
            UploadSessionsTable.selectAll().where { (UploadSessionsTable.id eq sessionId) and (UploadSessionsTable.user eq userId) }.singleOrNull()
        } ?: throw ApiException.notFound("上传会话不存在")
        when (row[UploadSessionsTable.status]) {
            "completed" -> throw ApiException.conflict(ErrorCodes.SESSION_COMPLETED, "会话已完成")
            "aborted" -> throw ApiException.notFound("会话已取消")
        }
        if (row[UploadSessionsTable.expiresAt].toEpochMilli() < System.currentTimeMillis()) {
            throw ApiException.conflict(ErrorCodes.SESSION_EXPIRED, "会话已过期")
        }
        return row
    }

    fun initUpload(userId: UUID, req: UploadInitRequest, storage: StorageProvider): UploadInitResponse {
        if (req.name.isBlank() || req.name.contains('/') || req.name.length > 255) throw ApiException.badRequest("文件名非法")
        if (req.size < 0) throw ApiException.badRequest("size 非法")
        val parent: UUID? = req.folderId?.let {
            val pid = UUID.fromString(it)
            transaction { FoldersTable.selectAll().where { (FoldersTable.id eq pid) and (FoldersTable.user eq userId) }.singleOrNull() }
                ?: throw ApiException.notFound("目标文件夹不存在")
            pid
        }
        val chunkSize = (req.chunkSize ?: DEFAULT_CHUNK).coerceIn(MIN_CHUNK, MAX_CHUNK)
        val sha = req.sha256?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }

        // 秒传：内容已存在且不重名
        if (sha != null) {
            val key = storage.blobKey(sha)
            if (storage.exists(key)) {
                val created = withContext(Dispatchers.IO) {
                    transaction {
                        val conflict = run {
                            val fHit = (if (parent == null) FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.name eq req.name) }
                            else FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.name eq req.name) }).any()
                            val dHit = (if (parent == null) FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() and (FoldersTable.name eq req.name) }
                            else FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) and (FoldersTable.name eq req.name) }).any()
                            fHit || dHit
                        }
                        if (!conflict) {
                            val id = UUID.randomUUID()
                            FilesTable.insert {
                                it[FilesTable.id] = id; it[user] = userId; it[folder] = parent
                                it[FilesTable.name] = req.name; it[FilesTable.size] = req.size
                                it[mimeType] = req.mimeType; it[FilesTable.sha256] = sha; it[storageKey] = key
                            }
                            FileDto(id.toString(), req.name, parent?.toString(), req.size, req.mimeType, sha, Instant.now().toString(), Instant.now().toString())
                        } else null
                    }
                }
                if (created != null) return UploadInitResponse("", chunkSize, emptyList(), instantUpload = true, file = created)
            }
        }

        // 断点续传匹配：同用户同目录同名同大小的 open session
        val existing = transaction {
            (if (parent == null)
                UploadSessionsTable.selectAll().where { (UploadSessionsTable.user eq userId) and UploadSessionsTable.folder.isNull() and (UploadSessionsTable.name eq req.name) and (UploadSessionsTable.size eq req.size) and (UploadSessionsTable.status eq "open") }
            else
                UploadSessionsTable.selectAll().where { (UploadSessionsTable.user eq userId) and (UploadSessionsTable.folder eq parent) and (UploadSessionsTable.name eq req.name) and (UploadSessionsTable.size eq req.size) and (UploadSessionsTable.status eq "open") }
                    ).firstOrNull()
        }
        val sessionId = existing?.get(UploadSessionsTable.id)?.value ?: UUID.randomUUID().also { sid ->
            transaction {
                UploadSessionsTable.insert {
                    it[id] = sid; it[user] = userId; it[folder] = parent
                    it[UploadSessionsTable.name] = req.name; it[UploadSessionsTable.size] = req.size
                    it[mimeType] = req.mimeType; it[UploadSessionsTable.chunkSize] = chunkSize
                    it[clientSha256] = sha; it[expiresAt] = Instant.now().plusSeconds(SESSION_TTL_HOURS * 3600)
                }
            }
        }
        val received = transaction {
            UploadChunksTable.selectAll().where { UploadChunksTable.session eq sessionId }.map { it[UploadChunksTable.chunkIndex] }
        }
        return UploadInitResponse(sessionId.toString(), chunkSize, received.sorted())
    }

    fun putChunk(userId: UUID, sessionId: UUID, index: Int, body: ByteArray, chunkSha: String?, storage: StorageProvider) {
        val row = openSession(userId, sessionId)
        val chunkSize = row[UploadSessionsTable.chunkSize]
        val total = row[UploadSessionsTable.size]
        val expected = expectedChunks(total, chunkSize)
        if (index !in 0 until expected) throw ApiException.badRequest("chunk index 越界", ErrorCodes.CHUNK_INVALID)
        val expectedSize = if (index == expected - 1) total - chunkSize * (expected - 1) else chunkSize
        if (body.size.toLong() != expectedSize) throw ApiException.badRequest("分块大小不符", ErrorCodes.CHUNK_INVALID)
        if (chunkSha != null && !chunkSha.equals(sha256hex(body), ignoreCase = true)) {
            throw ApiException.badRequest("分块校验不符", ErrorCodes.CHUNK_INVALID)
        }
        withContext(Dispatchers.IO) {
            val dir = storage.tmpDir.resolve(sessionId.toString()).toFile().apply { mkdirs() }
            val tmp = File(dir, "$index.part.tmp")
            File(dir, "$index.part").writeBytes(body)
            tmp.delete()
        }
        transaction {
            UploadChunksTable.upsert { it[session] = sessionId; it[UploadChunksTable.chunkIndex] = index; it[size] = body.size.toLong() }
        }
    }

    private fun sha256hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun complete(userId: UUID, sessionId: UUID, storage: StorageProvider): UploadCompleteResponse = withContext(Dispatchers.IO) {
        val row = openSession(userId, sessionId)
        val chunkSize = row[UploadSessionsTable.chunkSize]
        val total = row[UploadSessionsTable.size]
        val parent = row[UploadSessionsTable.folder]?.value
        val name = row[UploadSessionsTable.name]
        val mime = row[UploadSessionsTable.mimeType]
        val clientSha = row[UploadSessionsTable.clientSha256]
        val expected = expectedChunks(total, chunkSize)
        val have = transaction { UploadChunksTable.selectAll().where { UploadChunksTable.session eq sessionId }.count() }
        if (have != expected.toLong()) throw ApiException.badRequest("缺少分块", ErrorCodes.CHUNK_MISSING)

        val mergeFile = storage.tmpDir.resolve("merge-$sessionId").toFile()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(mergeFile).use { out ->
                val buf = ByteArray(1 shl 16)
                for (i in 0 until expected) {
                    val part = storage.tmpDir.resolve(sessionId.toString()).resolve("$i.part").toFile()
                    FileInputStream(part).use { ins ->
                        while (true) { val n = ins.read(buf); if (n < 0) break; digest.update(buf, 0, n); out.write(buf, 0, n) }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            if (clientSha != null && !clientSha.equals(sha, ignoreCase = true)) {
                throw ApiException.badRequest("整体哈希与 init 不符", ErrorCodes.CHUNK_INVALID)
            }
            val key = storage.blobKey(sha)
            if (!storage.exists(key)) storage.put(key, FileInputStream(mergeFile).toByteReadChannel())
            val fileDto = transaction {
                val conflict = (if (parent == null) FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.name eq name) }
                else FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.name eq name) }).any()
                if (conflict) throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件")
                val id = UUID.randomUUID()
                FilesTable.insert {
                    it[FilesTable.id] = id; it[user] = userId; it[folder] = parent
                    it[FilesTable.name] = name; it[FilesTable.size] = total
                    it[mimeType] = mime; it[FilesTable.sha256] = sha; it[storageKey] = key
                }
                UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[status] = "completed" }
                FileDto(id.toString(), name, parent?.toString(), total, mime, sha, Instant.now().toString(), Instant.now().toString())
            }
            cleanupSessionDir(storage, sessionId)
            UploadCompleteResponse(fileDto)
        } finally {
            mergeFile.delete()
        }
    }

    fun abort(userId: UUID, sessionId: UUID, storage: StorageProvider) {
        openSession(userId, sessionId)
        transaction { UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[status] = "aborted" } }
        cleanupSessionDir(storage, sessionId)
    }

    fun cleanupExpired(storage: StorageProvider) {
        val expired = transaction {
            UploadSessionsTable.selectAll().where { (UploadSessionsTable.status eq "open") and (UploadSessionsTable.expiresAt less Instant.now()) }
                .map { it[UploadSessionsTable.id].value }
        }
        expired.forEach { sid ->
            transaction { UploadSessionsTable.deleteWhere { UploadSessionsTable.id eq sid } }
            cleanupSessionDir(storage, sid)
        }
    }

    private fun cleanupSessionDir(storage: StorageProvider, sessionId: UUID) {
        storage.tmpDir.resolve(sessionId.toString()).toFile().deleteRecursively()
    }
}
```

`files/UploadRoutes.kt`：

```kotlin
package com.linan.barezen_drive.files

import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.uploadRoutes(storage: StorageProvider) {
    route("/api/uploads") {
        post("/init") {
            val req = call.receive<UploadInitRequest>()
            call.respond(UploadService.initUpload(call.userId, req, storage))
        }
        put("/{id}/chunks/{index}") {
            val sessionId = UUID.fromString(call.parameters["id"]!!)
            val index = call.parameters["index"]!!.toInt()
            val bytes = ByteArray(call.request.contentLength()!!).also { var off = 0; while (off < it.size) { val n = call.request.receiveChannel().readAvailable(it, off, it.size - off); if (n == -1) break; off += n } }
            val chunkSha = call.request.headers["X-Chunk-Sha256"]
            UploadService.putChunk(call.userId, sessionId, index, bytes, chunkSha, storage)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/complete") {
            val sessionId = UUID.fromString(call.parameters["id"]!!)
            call.respond(UploadService.complete(call.userId, sessionId, storage))
        }
        delete("/{id}") {
            val sessionId = UUID.fromString(call.parameters["id"]!!)
            UploadService.abort(call.userId, sessionId, storage)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

`jobs/UploadCleanupJob.kt`：

```kotlin
package com.linan.barezen_drive.jobs

import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.StorageProvider
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.hours

object UploadCleanupJob {
    fun start(scope: CoroutineScope, storage: StorageProvider): Job = scope.launch {
        while (isActive) {
            runCatching { UploadService.cleanupExpired(storage) }.onFailure { it.printStackTrace() }
            delay(6.hours)
        }
    }
}
```

Application.kt：routing 加 `uploadRoutes(storage)`；main() `CoroutineScope(Dispatchers.Default + SupervisorJob()).let { UploadCleanupJob.start(it, storage) }`。

- [ ] **Step 4: 测试通过**：`./gradlew :server:test --console=plain` -> PASS
- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(server): resumable chunked upload protocol, instant upload, abort, expiry cleanup"
```

---

### Task 7: 下载（Range 支持）+ Web 静态托管

**Files:**
- Create: `server/src/main/kotlin/com/linan/barezen_drive/files/FileContent.kt`
- Create: `server/src/main/kotlin/com/linan/barezen_drive/plugins/StaticWeb.kt`
- Create: `server/src/main/resources/web/index.html`（占位页）
- Modify: `Application.kt`（routing：file content 路由 + staticWeb；install PartialContent）
- Test: `server/src/test/kotlin/com/linan/barezen_drive/DownloadTest.kt`

**Interfaces:**
- Produces: `GET /api/files/{id}/content`（支持 `Range: bytes=a-b`，206 响应）；`staticWeb()`（`staticResources("/", "web") { default("index.html") }`，`/api/**` 优先）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

class DownloadTest {
    private val storageDir = Files.createTempDirectory("bz-dl").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
    private fun setup() = testApplication {
        val c = cfg(); val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"u1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"u1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }
    private var auth = ""

    @Test
    fun rangeRequestReturns206() = testApplication {
        setup()
        val body = "0123456789".encodeToByteArray()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"r.txt","size":10,"chunkSize":1048576}""") }
        val uploadId = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        val fileId = Regex(""""id":"([^"]+)"""").find(client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }.bodyAsText())!!.groupValues[1]
        val r206 = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.Range, "bytes=0-4") }
        assertEquals(HttpStatusCode.PartialContent, r206.status)
        assertEquals("01234", r206.bodyAsText())
        val rLast = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.Range, "bytes=-3") }
        assertEquals(HttpStatusCode.PartialContent, rLast.status)
        assertEquals("789", rLast.bodyAsText())
    }

    @Test
    fun rootServesWebIndex() = testApplication {
        setup()
        val res = client.get("/")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("<html", ignoreCase = true))
    }
}
```

- [ ] **Step 2: 运行确认失败** -> FAIL
- [ ] **Step 3: 实现**

`files/FileContent.kt`：

```kotlin
package com.linan.barezen_drive.files

import io.ktor.server.response.*
import io.ktor.utils.io.*
import io.ktor.http.content.*

class FileContent(private val channel: ByteReadChannel, private val length: Long) : OutgoingContent.ReadChannelContent() {
    override val contentLength: Long = length
    override fun readTo(): ByteReadChannel = channel
}
```

`plugins/StaticWeb.kt`：

```kotlin
package com.linan.barezen_drive.plugins

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

fun Application.staticWeb() {
    routing {
        staticResources("/", "web") {
            default("index.html")
            enableAutoHeadResponse()
        }
    }
}
```

`server/src/main/resources/web/index.html`：

```html
<!doctype html>
<html lang="zh">
<head><meta charset="utf-8"><title>BareZen Drive</title></head>
<body>Web 客户端构建产物挂载点（Docker 构建时替换）。</body>
</html>
```

Application.kt：`install(io.ktor.server.plugins.partialcontent.PartialContent)`；routing 里 `get("/api/files/{id}/content")`（置于 `authenticate("auth-jwt")` 内）：

```kotlin
        get("/api/files/{id}/content") {
            val id = java.util.UUID.fromString(call.parameters["id"]!!)
            val meta = withContext(Dispatchers.IO) { FileService.getFileMeta(call.userId, id) }
            val channel = storage.get(meta.storageKey)
            call.respond(FileContent(channel, meta.size))
        }
```

末尾 `staticWeb()`。注意：静态资源注册在所有 API 路由之后，Ktor 按注册顺序匹配，`/api/**` 优先。

- [ ] **Step 4: 测试通过**：`./gradlew :server:test --console=plain` -> PASS
- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(server): ranged download, web static hosting with SPA fallback"
```

---

### Task 8: 客户端平台层（TokenStorage / Sha256er / FilePicker / FileSaver）

**Files:**
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/platform/Sha256er.kt`（expect + 纯实现）
- Create: `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/platform/Sha256er.android.kt`
- Create: `app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/platform/Sha256er.wasm.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/local/TokenStorage.kt`（expect 接口 + 默认实例）
- Create: `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/data/local/TokenStorage.android.kt`
- Create: `app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/data/local/TokenStorage.wasm.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/platform/FilePicker.kt`
- Create: `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/platform/FilePicker.android.kt`
- Create: `app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/platform/FilePicker.wasm.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/platform/FileSaver.kt`
- Create: `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/platform/FileSaver.android.kt`
- Create: `app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/platform/FileSaver.wasm.kt`
- Test: `app/shared/src/commonTest/kotlin/com/linan/barezen_drive/Sha256Test.kt`

**Interfaces:**
- Produces:
  - `interface TokenStore { var baseUrl: String; var accessToken: String?; var refreshToken: String?; fun clear() }`；`expect object TokenStorage : TokenStore`（平台默认实现）。
  - `expect class Sha256er { fun update(bytes: ByteArray); fun digestHex(): String; companion object { fun newInstance(): Sha256er } }`。
  - `interface PickedFile { val name: String; val size: Long; val mimeType: String?; suspend fun readRange(offset: Long, length: Int): ByteArray? }`；`@Composable expect fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit`；`@Composable expect fun rememberFileSaver(onDone: (String?) -> Unit): (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.platform.Sha256er
import kotlin.test.*

class Sha256Test {
    @Test
    fun abcVector() {
        val h = Sha256er.newInstance(); h.update("abc".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h.digestHex())
    }
    @Test
    fun emptyVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256er.newInstance().digestHex())
    }
    @Test
    fun chunkedSameAsWhole() {
        val data = (0 until 1000).map { (it % 251).toByte() }.toByteArray()
        val whole = Sha256er.newInstance().apply { update(data) }.digestHex()
        val chunked = Sha256er.newInstance().apply {
            update(data.copyOfRange(0, 7)); update(data.copyOfRange(7, 64)); update(data.copyOfRange(64, data.size))
        }.digestHex()
        assertEquals(whole, chunked)
    }
}
```

- [ ] **Step 2: 运行确认失败**：`./gradlew :app:shared:jvmTest --tests "*Sha256Test*" --console=plain` -> FAIL（jvm target 已裁剪 —— 改跑 `:app:shared:testAndroidHostTest`；若模板无此任务名，用 `./gradlew :app:shared:testDebugUnitTest`，以实际任务名为准）
- [ ] **Step 3: 实现全部 expect/actual**

`commonMain/platform/Sha256er.kt`（expect + 公共纯实现）：

```kotlin
package com.linan.barezen_drive.platform

expect class Sha256er {
    fun update(bytes: ByteArray)
    fun digestHex(): String
    companion object { fun newInstance(): Sha256er }
}

/** 纯 Kotlin 增量 SHA-256，供无 MessageDigest 的平台（wasm）actual 复用 */
internal class PureSha256 {
    private var state = intArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
    private val block = ByteArray(64)
    private var blockLen = 0
    private var totalBytes = 0L
    private val w = IntArray(64)

    fun update(bytes: ByteArray) {
        totalBytes += bytes.size
        var i = 0
        while (i < bytes.size) {
            val n = minOf(64 - blockLen, bytes.size - i)
            bytes.copyInto(block, blockLen, i, i + n)
            blockLen += n; i += n
            if (blockLen == 64) { processBlock(); blockLen = 0 }
        }
    }

    fun digestHex(): String {
        val bitLen = totalBytes * 8
        block[blockLen++] = 0x80.toByte()
        if (blockLen > 56) { while (blockLen < 64) block[blockLen++] = 0; processBlock(); blockLen = 0 }
        while (blockLen < 56) block[blockLen++] = 0
        for (shift in 56 downTo 0 step 8) block[blockLen++] = ((bitLen ushr shift) and 0xff).toByte()
        processBlock()
        val out = ByteArray(32); var j = 0
        for (s in state) {
            out[j++] = (s ushr 24).toByte(); out[j++] = (s ushr 16).toByte()
            out[j++] = (s ushr 8).toByte(); out[j++] = s.toByte()
        }
        return out.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun processBlock() {
        for (t in 0 until 16) {
            val b = t * 4
            w[t] = ((block[b].toInt() and 0xff) shl 24) or ((block[b + 1].toInt() and 0xff) shl 16) or
                   ((block[b + 2].toInt() and 0xff) shl 8) or (block[b + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val x = w[t - 15]; val y = w[t - 2]
            val s0 = ((x ushr 7) or (x shl 25)) xor ((x ushr 18) or (x shl 14)) xor (x ushr 3)
            val s1 = ((y ushr 17) or (y shl 15)) xor ((y ushr 19) or (y shl 13)) xor (y ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (t in 0 until 64) {
            val S1 = ((e ushr 6) or (e shl 26)) xor ((e ushr 11) or (e shl 21)) xor ((e ushr 25) or (e shl 7))
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + S1 + ch + K[t] + w[t]
            val S0 = ((a ushr 2) or (a shl 30)) xor ((a ushr 13) or (a shl 19)) xor ((a ushr 22) or (a shl 10))
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = S0 + maj
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    companion object {
        private val K = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        )
    }
}
```

`androidMain/platform/Sha256er.android.kt`：

```kotlin
package com.linan.barezen_drive.platform

import java.security.MessageDigest

actual class Sha256er private constructor(private val md: MessageDigest) {
    actual fun update(bytes: ByteArray) { md.update(bytes) }
    actual fun digestHex(): String = md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    actual companion object {
        actual fun newInstance(): Sha256er = Sha256er(MessageDigest.getInstance("SHA-256"))
    }
}
```

`wasmJsMain/platform/Sha256er.wasm.kt`：

```kotlin
package com.linan.barezen_drive.platform

actual class Sha256er private constructor(private val impl: PureSha256) {
    actual fun update(bytes: ByteArray) = impl.update(bytes)
    actual fun digestHex(): String = impl.digestHex()
    actual companion object {
        actual fun newInstance(): Sha256er = Sha256er(PureSha256())
    }
}
```

`commonMain/data/local/TokenStorage.kt`：

```kotlin
package com.linan.barezen_drive.data.local

interface TokenStore {
    var baseUrl: String
    var accessToken: String?
    var refreshToken: String?
    fun clear()
}

expect object TokenStorage : TokenStore {
    override var baseUrl: String
    override var accessToken: String?
    override var refreshToken: String?
    override fun clear()
}
```

`androidMain/data/local/TokenStorage.android.kt`：

```kotlin
package com.linan.barezen_drive.data.local

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.linan.barezen_drive.AndroidContext

actual object TokenStorage : TokenStore {
    private val prefs by lazy {
        val ctx = AndroidContext.app
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "barezen_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    actual override var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v).apply()
    actual override var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()
    actual override var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()
    actual override fun clear() = prefs.edit().clear().apply()
}
```

`AndroidContext`（androidApp 模块，Task 12 创建；为使 shared 编译通过，先在本 Task 于 `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/AndroidContext.kt` 创建）：

```kotlin
package com.linan.barezen_drive

import android.content.Context

object AndroidContext {
    lateinit var app: Context
        private set
    fun init(ctx: Context) { app = ctx.applicationContext }
}
```

`wasmJsMain/data/local/TokenStorage.wasm.kt`：

```kotlin
package com.linan.barezen_drive.data.local

import kotlinx.browser.window

actual object TokenStorage : TokenStore {
    private fun get(k: String): String? = window.localStorage.getItem(k)
    private fun set(k: String, v: String?) { if (v == null) window.localStorage.removeItem(k) else window.localStorage.setItem(k, v) }
    actual override var baseUrl: String
        get() = get("base_url") ?: ""
        set(v) = set("base_url", v)
    actual override var accessToken: String?
        get() = get("access_token")
        set(v) = set("access_token", v)
    actual override var refreshToken: String?
        get() = get("refresh_token")
        set(v) = set("refresh_token", v)
    actual override fun clear() { accessToken = null; refreshToken = null }
}
```

`commonMain/platform/FilePicker.kt`：

```kotlin
package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable

interface PickedFile {
    val name: String
    val size: Long
    val mimeType: String?
    /** 读取 [offset, offset+length) 字节；越界返回 null */
    suspend fun readRange(offset: Long, length: Int): ByteArray?
}

@Composable
expect fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit
```

`androidMain/platform/FilePicker.android.kt`：

```kotlin
package com.linan.barezen_drive.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidPickedFile(private val ctx: Context, private val uri: Uri) : PickedFile {
    override val name: String
    override val size: Long
    override val mimeType: String? = ctx.contentResolver.getType(uri)

    init {
        var n = uri.toString(); var s = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) { if (iName >= 0) n = c.getString(iName); if (iSize >= 0) s = c.getLong(iSize) }
        }
        name = n; size = s
    }

    override suspend fun readRange(offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            var toSkip = offset
            while (toSkip > 0) { val n = ins.skip(toSkip); if (n <= 0) return@use null; toSkip -= n }
            val buf = ByteArray(length); var read = 0
            while (read < length) { val r = ins.read(buf, read, length - read); if (r < 0) break; read += r }
            if (read == 0) null else buf.copyOf(read)
        }
    }
}

@Composable
actual fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onResult(uris.map { AndroidPickedFile(ctx, it) })
    }
    return { launcher.launch(arrayOf("*/*")) }
}
```

`androidMain/platform/FileSaver.android.kt`：

```kotlin
package com.linan.barezen_drive.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.ktor.utils.io.*
import kotlinx.coroutines.launch

@Composable
actual fun rememberFileSaver(onDone: (String?) -> Unit): (String, String?, suspend () -> ByteReadChannel) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, suspend () -> ByteReadChannel>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pending; pending = null
        if (uri == null || p == null) { onDone(null); return@rememberLauncherForActivityResult }
        scope.launch {
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                val ch = p.second; val buf = ByteArray(1 shl 16)
                while (true) { val n = ch.readAvailable(buf, 0, buf.size); if (n == -1) break; out.write(buf, 0, n) }
                out.flush()
            }
            onDone(uri.toString())
        }
    }
    return { name, _, open -> pending = name to open; launcher.launch(name) }
}
```

`commonMain/platform/FileSaver.kt`：

```kotlin
package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import io.ktor.utils.io.ByteReadChannel

@Composable
expect fun rememberFileSaver(onDone: (String?) -> Unit): (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit
```

`wasmJsMain/platform/FilePicker.wasm.kt`（DOM 互操作 best-effort，编译报错时按编译器提示机械调整）：

```kotlin
package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

internal class WasmPickedFile(private val file: File) : PickedFile {
    override val name: String = file.name
    override val size: Long = file.size.toLong()
    override val mimeType: String? = file.type.ifBlank { null }
    override suspend fun readRange(offset: Long, length: Int): ByteArray? {
        if (offset >= size) return null
        val end = minOf(offset + length, size)
        val blob = file.slice(offset.toDouble(), end.toDouble())
        val buf = kotlinx.browser.window.await<ArrayBuffer?>(...) // 见下方说明
        return buf
    }
}
```

（执行说明：wasm 互操作三行的准确写法 —— `blob.arrayBuffer()` 返回 `Promise<ArrayBuffer>`，用 `kotlinx.coroutines.await()`；`ArrayBuffer -> ByteArray` 用 `org.khronos.webgn.Int8Array(buf)` 再逐元素转。写成：

```kotlin
val ab = blob.arrayBuffer().await()
val i8 = org.khronos.webgn.Int8Array(ab.unsafeCast<org.khronos.webgn.ArrayBuffer>())
return ByteArray(i8.length) { i8[it].toByte() }
```

导入 `kotlinx.coroutines.await`。若 wrappers 版本间类型名有差异，按编译器提示调整。）

```kotlin
@Composable
actual fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit {
    return {
        val input = document.createElement("input").unsafeCast<HTMLInputElement>()
        input.type = "file"; input.multiple = true
        input.onchange = {
            val files = input.files
            if (files != null) {
                val list = (0 until files.length).mapNotNull { files.item(it)?.let(::WasmPickedFile) }
                onResult(list)
            }
            Unit
        }
        input.click()
    }
}
```

`wasmJsMain/platform/FileSaver.wasm.kt`：

```kotlin
package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.ktor.utils.io.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
actual fun rememberFileSaver(onDone: (String?) -> Unit): (String, String?, suspend () -> ByteReadChannel) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, _, open ->
        scope.launch {
            val ch = open(); val all = ArrayList<Byte>(); val buf = ByteArray(1 shl 16)
            while (true) { val n = ch.readAvailable(buf, 0, buf.size); if (n == -1) break; all.addAll(buf.copyOf(n).asList()) }
            val bytes = all.toByteArray()
            val blob = org.w3c.dom.Blob(arrayOf(bytes).toJsArrayish(), options = null) // 见下
            val url = window.URL.createObjectURL(blob)
            val a = document.createElement("a").unsafeCast<org.w3c.dom.HTMLAnchorElement>()
            a.href = url; a.download = name
            document.body!!.appendChild(a); a.click(); document.body!!.removeChild(a)
            window.URL.revokeObjectURL(url)
            onDone(name)
        }
    }
}
```

（执行说明：`bytes -> Blob part` 的 wasm 互操作按编译器提示处理，推荐 `org.khronos.webgn.Int8Array(bytes)` 直接作为 BlobPart；收集字节改为预分配 `ByteArray(总大小)` 累计写入以避免 ArrayList 开销。）

- [ ] **Step 4: 测试通过**

Run: `./gradlew :app:shared:testAndroidHostTest :core:compileKotlinWasmJs --console=plain`（任务名以实际为准）
Expected: PASS（SHA 向量全绿）

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(shared): platform layer — token store, streaming sha256, SAF file picker/saver, wasm actuals"
```

---

### Task 9: ApiClient + Repository（Bearer 自动刷新）

**Files:**
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/api/ApiFailure.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/api/ApiClient.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/repo/AuthRepository.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/repo/FilesRepository.kt`
- Test: `app/shared/src/commonTest/kotlin/com/linan/barezen_drive/ApiClientTest.kt`

**Interfaces:**
- Produces:
  - `class ApiFailure(val code: String?, message: String, val unauthorized: Boolean = false) : Exception`。
  - `class ApiClient(store: TokenStore = TokenStorage)`，方法：`suspend fun register/login/refresh/me/contents/createFolder/renameFolder/deleteFolder/updateFile/deleteFile/uploadInit/uploadChunk/complete/abort/downloadChannel`；返回 `Result<T, ApiFailure>`（网络调用用 `runApi { }` 包装）。
  - `AuthRepository(api, store)`：`register/login/logout`。
  - `FilesRepository(api)`：同时实现 `interface UploadApi { suspend fun init(req): Result<UploadInitResponse, ApiFailure>; suspend fun putChunk(id, index, bytes, sha): Result<Unit, ApiFailure>; suspend fun complete(id): Result<FileDto, ApiFailure> }`。

- [ ] **Step 1: 写失败测试（MockEngine）**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.local.TokenStore
import com.linan.barezen_drive.core.dto.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FakeTokenStore : TokenStore {
    override var baseUrl: String = "http://test"
    override var accessToken: String? = "old"
    override var refreshToken: String? = "r-old"
    override fun clear() { accessToken = null; refreshToken = null }
}

class ApiClientTest {
    @Test
    fun refreshOn401ThenReplay() = runTest {
        val store = FakeTokenStore()
        var refreshCalls = 0
        val engine = MockEngine { req ->
            val auth = req.headers["Authorization"]
            when {
                req.url.encodedPath == "/api/auth/refresh" -> {
                    refreshCalls++
                    respond("""{"accessToken":"new","refreshToken":"r-new"}"", HttpStatusCode.OK, contentType(ContentType.Application.Json))
                }
                auth == "Bearer new" -> respond("""{"user":{"id":"u","username":"n","createdAt":"t"}}""", HttpStatusCode.OK, contentType(ContentType.Application.Json))
                else -> respond("""{"error":{"code":"TOKEN_INVALID","message":"expired"}}""", HttpStatusCode.Unauthorized, contentType(ContentType.Application.Json))
            }
        }
        val api = ApiClient(store, engineOverride = engine)
        val me = api.me()
        assertTrue(me.isSuccess)
        assertEquals("n", me.getOrNull()!!.username)
        assertEquals("new", store.accessToken)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun errorEnvelopeMapped() = runTest {
        val engine = MockEngine { respond("""{"error":{"code":"NAME_CONFLICT","message":"x"}}""", HttpStatusCode.Conflict, contentType(ContentType.Application.Json)) }
        val api = ApiClient(FakeTokenStore(), engineOverride = engine)
        val res = api.createFolder(null, "dup")
        assertTrue(res.isFailure)
        assertEquals("NAME_CONFLICT", res.exceptionOrNull()!!.code)
    }
}
```

- [ ] **Step 2: 运行确认失败**：`./gradlew :app:shared:testAndroidHostTest --tests "*ApiClientTest*" --console=plain` -> FAIL
- [ ] **Step 3: 实现**

`data/api/ApiFailure.kt`：

```kotlin
package com.linan.barezen_drive.data.api

class ApiFailure(val code: String?, message: String, val httpStatus: Int) : Exception(message)
```

`data/api/ApiClient.kt`：

```kotlin
package com.linan.barezen_drive.data.api

import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.data.local.TokenStore
import com.linan.barezen_drive.data.local.TokenStorage
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json

class ApiClient(
    private val store: TokenStore = TokenStorage,
    engineOverride: HttpClientEngine? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    val baseUrl: String get() = store.baseUrl.trimEnd('/')

    private val refreshClient = engineOverride?.let { HttpClient(it) { install(ContentNegotiation) { json(json) } } }
        ?: HttpClient { install(ContentNegotiation) { json(json) } }

    val http: HttpClient = HttpClient(engineOverride) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(Auth) {
            bearer {
                loadTokens { store.accessToken?.let { BearerTokens(it, store.refreshToken ?: "") } }
                refreshTokens {
                    val rt = store.refreshToken ?: return@refreshTokens null
                    runCatching {
                        refreshClient.post("$baseUrl/api/auth/refresh") {
                            contentType(ContentType.Application.Json)
                            setBody(RefreshRequest(rt))
                        }.body<RefreshResponse>()
                    }.getOrNull()?.also { r ->
                        store.accessToken = r.accessToken; store.refreshToken = r.refreshToken
                    }?.let { BearerTokens(it.accessToken, it.refreshToken) }
                }
            }
        }
    }

    private suspend inline fun <T> runApi(crossinline block: suspend () -> T): Result<T> = runCatching { block() }.recoverCatching { e ->
        throw e.toApiFailure()
    }

    private suspend fun Throwable.toApiFailure(): ApiFailure {
        val resp = (this as? ResponseException)?.response ?: return ApiFailure(null, message ?: "网络错误", 0)
        val code = runCatching { resp.bodyAsText() }.getOrNull()
            ?.let { runCatching { json.decodeFromString<ErrorResponse>(it) }.getOrNull() }?.error?.code
        return ApiFailure(code, message ?: resp.status.toString(), resp.status.value)
    }

    suspend fun register(username: String, password: String): Result<Unit> = runApi {
        http.post("$baseUrl/api/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest(username, password)) }
        Unit
    }

    suspend fun login(username: String, password: String): Result<LoginResponse> = runApi {
        http.post("$baseUrl/api/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(username, password)) }.body()
    }

    suspend fun me(): Result<UserDto> = runApi { http.get("$baseUrl/api/me").body() }

    suspend fun contents(folderId: String): Result<ContentsResponse> = runApi { http.get("$baseUrl/api/folders/$folderId/contents").body() }

    suspend fun createFolder(parentId: String?, name: String): Result<FolderDto> = runApi {
        http.post("$baseUrl/api/folders") { contentType(ContentType.Application.Json); setBody(CreateFolderRequest(parentId, name)) }.body()
    }

    suspend fun renameFolder(id: String, name: String): Result<FolderDto> = runApi {
        http.patch("$baseUrl/api/folders/$id") { contentType(ContentType.Application.Json); setBody(RenameFolderRequest(name)) }.body()
    }

    suspend fun deleteFolder(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/folders/$id"); Unit
    }

    suspend fun updateFile(id: String, name: String?, folderId: String?): Result<FileDto> = runApi {
        http.patch("$baseUrl/api/files/$id") { contentType(ContentType.Application.Json); setBody(UpdateFileRequest(name, folderId)) }.body()
    }

    suspend fun deleteFile(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/files/$id"); Unit
    }

    suspend fun uploadInit(req: UploadInitRequest): Result<UploadInitResponse> = runApi {
        http.post("$baseUrl/api/uploads/init") { contentType(ContentType.Application.Json); setBody(req) }.body()
    }

    suspend fun uploadChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit> = runApi {
        http.put("$baseUrl/api/uploads/$id/chunks/$index") {
            setBody(bytes); sha?.let { header("X-Chunk-Sha256", it) }
        }
        Unit
    }

    suspend fun uploadComplete(id: String): Result<FileDto> = runApi {
        http.post("$baseUrl/api/uploads/$id/complete").body<UploadCompleteResponse>().file
    }

    suspend fun uploadAbort(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/uploads/$id"); Unit
    }

    suspend fun download(id: String): ByteReadChannel =
        http.get("$baseUrl/api/files/$id/content").bodyAsChannel()

    suspend fun previewBytes(id: String): ByteArray =
        http.get("$baseUrl/api/files/$id/content").bodyAsBytes()
}
```

`data/repo/AuthRepository.kt`：

```kotlin
package com.linan.barezen_drive.data.repo

import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.data.local.TokenStore
import com.linan.barezen_drive.core.dto.UserDto

class AuthRepository(private val api: ApiClient, private val store: TokenStore) {
    suspend fun register(host: String, username: String, password: String): Result<Unit> {
        store.baseUrl = host
        return api.register(username, password)
    }
    suspend fun login(host: String, username: String, password: String): Result<UserDto> {
        store.baseUrl = host
        return api.login(username, password).mapCatching { r ->
            store.accessToken = r.accessToken; store.refreshToken = r.refreshToken
            r.user
        }
    }
    fun logout() { store.accessToken = null; store.refreshToken = null }
}
```

`data/repo/FilesRepository.kt`：

```kotlin
package com.linan.barezen_drive.data.repo

import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.api.ApiFailure
import io.ktor.utils.io.ByteReadChannel

interface UploadApi {
    suspend fun init(req: UploadInitRequest): Result<UploadInitResponse, ApiFailure>
    suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit, ApiFailure>
    suspend fun complete(id: String): Result<FileDto, ApiFailure>
}

class FilesRepository(private val api: ApiClient) : UploadApi {
    // Result<T, ApiFailure> = kotlin Result 的泛型变体 —— 用自定义轻量包装：
    // 见下方说明；简单起见所有 Result 统一为 kotlin.result.Result<T>，ApiFailure 经 exceptionOrNull() 拿到。
    override suspend fun init(req: UploadInitRequest) = api.uploadInit(req)
    override suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?) = api.uploadChunk(id, index, bytes, sha)
    override suspend fun complete(id: String) = api.uploadComplete(id)

    suspend fun contents(folderId: String) = api.contents(folderId)
    suspend fun createFolder(parentId: String?, name: String) = api.createFolder(parentId, name)
    suspend fun renameFolder(id: String, name: String) = api.renameFolder(id, name)
    suspend fun deleteFolder(id: String) = api.deleteFolder(id)
    suspend fun updateFile(id: String, name: String?, folderId: String?) = api.updateFile(id, name, folderId)
    suspend fun deleteFile(id: String) = api.deleteFile(id)
    suspend fun download(id: String) = api.download(id)
    suspend fun previewBytes(id: String) = api.previewBytes(id)
}
```

（执行说明：去掉 `UploadApi` 里 `Result<..., ApiFailure>` 双参数签名的复杂化 —— 全部用标准 `kotlin.Result<T>`，失败时 `exceptionOrNull()` 是 `ApiFailure`。接口定义为：

```kotlin
interface UploadApi {
    suspend fun init(req: UploadInitRequest): Result<UploadInitResponse>
    suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit>
    suspend fun complete(id: String): Result<FileDto>
}
```
）

- [ ] **Step 4: 测试通过**：`./gradlew :app:shared:testAndroidHostTest --tests "*ApiClientTest*" --console=plain` -> PASS
- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(shared): ktor-client API with bearer auto-refresh, auth/file repositories"
```

---

### Task 10: UploadManager（分块、整文件哈希、断点续传、重试、进度）

**Files:**
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/upload/UploadManager.kt`
- Test: `app/shared/src/commonTest/kotlin/com/linan/barezen_drive/UploadManagerTest.kt`

**Interfaces:**
- Produces: `class UploadManager(api: UploadApi)`；`suspend fun upload(file: PickedFile, folderId: String?, onProgress: (Long, Long) -> Unit): Result<FileDto>`；`data class Progress(val uploaded: Long, val total: Long)` 由 UI 通过回调或 StateFlow 观察（实现用 `StateFlow<Map<String, Progress>>`，key = `name:size`）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.data.repo.UploadApi
import com.linan.barezen_drive.core.dto.*
import kotlin.test.*

private class FakePickedFile(private val data: ByteArray) : com.linan.barezen_drive.platform.PickedFile {
    override val name = "test.bin"; override val size = data.size.toLong(); override val mimeType = null
    override suspend fun readRange(offset: Long, length: Int): ByteArray? {
        if (offset >= size) return null
        val end = minOf(offset + length, size).toInt()
        return data.copyOfRange(offset.toInt(), end)
    }
}

private class FakeApi(private val data: ByteArray) : UploadApi {
    val chunks = mutableMapOf<Int, ByteArray>(); var inited = 0; var completed = false
    var failTimes = 0
    override suspend fun init(req: UploadInitRequest): Result<UploadInitResponse> {
        inited++
        val received = chunks.keys.filter { it < (data.size + req.chunkSize!! - 1) / req.chunkSize!! }
        return Result.success(UploadInitResponse("u1", req.chunkSize ?: 8, received))
    }
    override suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit> {
        if (failTimes > 0 && index == 1) { failTimes--; return Result.failure(RuntimeException("flaky")) }
        chunks[index] = bytes; return Result.success(Unit)
    }
    override suspend fun complete(id: String): Result<FileDto> {
        completed = true
        return Result.success(FileDto("f1", "test.bin", null, data.size.toLong(), null, "0".repeat(64), "t", "t"))
    }
}

class UploadManagerTest {
    @Test
    fun uploadsAllChunksInOrder() = runTest2 {
        val data = (0 until 30).map { it.toByte() }.toByteArray()
        val api = FakeApi(data)
        val mgr = UploadManager(api)
        val res = mgr.upload(FakePickedFile(data), null)
        assertTrue(res.isSuccess); assertTrue(api.completed)
        assertEquals(listOf(0, 1, 2, 3), api.chunks.keys.sorted())
        assertEquals(data.toList(), api.chunks.toSortedMap().values.flatMap { it.toList() })
    }

    @Test
    fun retriesFlakyChunk() = runTest2 {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data); api.failTimes = 1
        val res = UploadManager(api).upload(FakePickedFile(data), null)
        assertTrue(res.isSuccess, "重试后应成功")
    }

    private fun runTest2(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = kotlinx.coroutines.test.runTest(block = block)
}
```

- [ ] **Step 2: 运行确认失败** -> FAIL
- [ ] **Step 3: 实现**

```kotlin
package com.linan.barezen_drive.data.upload

import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.data.repo.UploadApi
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.Sha256er
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UploadManager(private val api: UploadApi) {
    data class Progress(val name: String, val uploaded: Long, val total: Long)

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    suspend fun upload(file: PickedFile, folderId: String?): Result<FileDto> {
        val key = "${file.name}:${file.size}"
        try {
            // 1) 整文件 SHA-256（流式两遍中的第一遍）
            _progress.value = _progress.value + (key to Progress(file.name, 0, file.size))
            val whole = Sha256er.newInstance()
            var off = 0L
            while (off < file.size) {
                val b = file.readRange(off, 1 shl 20) ?: break
                whole.update(b); off += b.size
                _progress.value = _progress.value + (key to Progress(file.name, off / 8, file.size))
            }
            // 2) init（服务端可秒传/续传匹配）
            val init = api.init(UploadInitRequest(folderId, file.name, file.size, file.mimeType, whole.digestHex()))
                .getOrElse { return Result.failure(it) }
            if (init.instantUpload) return Result.success(init.file!!)
            val chunkSize = init.chunkSize.toInt()
            // 3) 顺序分块上传（跳过服务端已收块；单块最多重试 3 次）
            var uploaded = init.receivedChunks.size.toLong() * chunkSize
            var index = 0
            var offset = 0L
            while (offset < file.size) {
                val len = minOf(chunkSize.toLong(), file.size - offset).toInt()
                val bytes = file.readRange(offset, len) ?: break
                if (index !in init.receivedChunks) {
                    val sha = Sha256er.newInstance().apply { update(bytes) }.digestHex()
                    var attempt = 0
                    while (true) {
                        val r = api.putChunk(init.uploadId, index, bytes, sha)
                        if (r.isSuccess) break
                        attempt++
                        if (attempt >= 3) return Result.failure(r.exceptionOrNull()!!)
                        delay(500L * (1L shl attempt)) // 1s, 2s
                    }
                    uploaded += bytes.size
                }
                _progress.value = _progress.value + (key to Progress(file.name, uploaded, file.size))
                offset += len; index++
            }
            // 4) complete
            return api.complete(init.uploadId)
        } finally {
            _progress.value = _progress.value - key
        }
    }
}
```

- [ ] **Step 4: 测试通过**：`./gradlew :app:shared:testAndroidHostTest --tests "*UploadManagerTest*" --console=plain` -> PASS
- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(shared): UploadManager — whole-file hash, sequential chunks, resume, retry, progress"
```

---

### Task 11: UI（主题 / 登录 / 文件浏览器 / 预览 / 导航栈）

**Files:**
- Modify: `gradle/libs.versions.toml`、`app/shared/build.gradle.kts`（material-icons-core，见 Step 0）
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/theme/Theme.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/screens/login/LoginScreen.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/screens/files/FilesScreen.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/screens/preview/PreviewScreen.kt`
- Modify: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/App.kt`（整文件替换）

**Interfaces:**
- Consumes: Task 8 平台层、Task 9 仓库、Task 10 UploadManager。
- Produces: `@Composable fun App()`（单入口）。

- [ ] **Step 0: 图标依赖（Material Icons 矢量图标，禁止 emoji/字符图标）**

`libs.versions.toml` `[libraries]` 追加：

```toml
compose-materialIconsCore = { module = "org.jetbrains.compose.material:material-icons-core", version = "1.7.3" }
```

`app/shared` commonMain.dependencies 追加 `implementation(libs.compose.materialIconsCore)`。

- [ ] **Step 1: 实现（UI 以构建通过 + 手动验证为准）**

`ui/theme/Theme.kt`：

```kotlin
package com.linan.barezen_drive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
```

`App.kt`（导航栈 + 依赖装配）：

```kotlin
package com.linan.barezen_drive

import androidx.compose.runtime.*
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.data.repo.AuthRepository
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.ui.screens.files.FilesScreen
import com.linan.barezen_drive.ui.screens.login.LoginScreen
import com.linan.barezen_drive.ui.screens.preview.PreviewScreen
import com.linan.barezen_drive.ui.theme.AppTheme

private sealed interface Screen {
    data object Login : Screen
    data class Files(val path: List<FolderDto>) : Screen
    data class Preview(val file: FileDto, val from: List<FolderDto>) : Screen
}

@Composable
fun App() {
    AppTheme {
        val api = remember { ApiClient() }
        val auth = remember { AuthRepository(api, TokenStorage) }
        val files = remember { FilesRepository(api) }
        val uploader = remember { UploadManager(files) }

        var stack by remember {
            mutableStateOf(listOf<Screen>(if (TokenStorage.accessToken != null) Screen.Files(emptyList()) else Screen.Login))
        }
        val current = stack.last()
        val push: (Screen) -> Unit = { stack = stack + it }
        val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

        when (current) {
            is Screen.Login -> LoginScreen(
                auth = auth,
                onLoggedIn = { stack = listOf(Screen.Files(emptyList())) },
            )
            is Screen.Files -> FilesScreen(
                path = current.path,
                repo = files,
                uploader = uploader,
                auth = auth,
                onOpenFolder = { push(Screen.Files(current.path + it)) },
                onJumpTo = { idx -> push(Screen.Files(current.path.take(idx + 1))) },
                onPreview = { push(Screen.Preview(it, current.path)) },
                onLoggedOut = { stack = listOf(Screen.Login) },
            )
            is Screen.Preview -> PreviewScreen(file = current.file, repo = files, onBack = pop)
        }
    }
}
```

`ui/screens/login/LoginScreen.kt`：

```kotlin
package com.linan.barezen_drive.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.repo.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(auth: AuthRepository, onLoggedIn: () -> Unit) {
    var mode by remember { mutableStateOf(0) } // 0 登录 1 注册
    var host by remember { mutableStateOf(auth.defaultHost()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("BareZen Drive", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        TabRow(selectedTabIndex = mode) {
            Tab(mode == 0, { mode = 0 }) { Text("登录", Modifier.padding(12.dp)) }
            Tab(mode == 1, { mode = 1 }) { Text("注册", Modifier.padding(12.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("服务器地址 (如 https://cloud.example.com)") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                val r = if (mode == 0) auth.login(host, username, password).map { }
                else auth.register(host, username, password).map { }
                busy = false
                r.fold(onSuccess = { onLoggedIn() }, onFailure = { error = it.message })
            }
        }, Modifier.fillMaxWidth()) { Text(if (busy) "请稍候…" else if (mode == 0) "登录" else "注册并登录") }
    }
}
```

（`auth.defaultHost()`：在 AuthRepository 加 `fun defaultHost(): String = store.baseUrl.ifBlank { "http://" }`。）

`ui/screens/files/FilesScreen.kt`（核心；约 200 行，完整实现，此处给骨架——所有元素都必须落地）：

功能清单（必须全部实现）：
1. 顶栏：面包屑（"根目录 / A / B"，点击跳层 `onJumpTo`）、退出登录图标。
2. 操作行：新建文件夹（AlertDialog + OutlinedTextField）、上传（`rememberFilePicker` -> `uploader.upload(file, currentFolderId())`，失败 toast）、进度区（`uploader.progress` 中有值时显示行内进度）。
3. 列表：`LaunchedEffect(path)` 调 `repo.contents(folderId)`（root 时 `"root"`，否则最后一级 id）；文件夹在前文件在后；每项 Row：Material Icons 图标（`Icons.Default.Folder` / `Icons.Default.Image` / `Icons.Default.Description`）+ 名称 + 大小；点文件夹 `onOpenFolder`；图片文件点 `onPreview`；其他文件点击触发下载（`rememberFileSaver`）。
4. 每项长按或右侧 `Icons.Default.MoreVert` 图标打开 `DropdownMenu`：文件夹 -> 重命名/删除；文件 -> 重命名/移动/下载/删除。
5. 移动对话框：列出"根目录 + 当前面包屑各级祖先"，选中后调 `repo.updateFile(id, null, targetId)`。
6. 错误统一 `SnackbarHost` 显示 `ApiFailure` 的 message。

骨架代码（执行者补全上述清单的细节，模式一致）：

```kotlin
package com.linan.barezen_drive.ui.screens.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.data.repo.AuthRepository
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.rememberFilePicker
import com.linan.barezen_drive.platform.rememberFileSaver
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    path: List<FolderDto>,
    repo: FilesRepository,
    uploader: UploadManager,
    auth: AuthRepository,
    onOpenFolder: (FolderDto) -> Unit,
    onJumpTo: (Int) -> Unit,
    onPreview: (FileDto) -> Unit,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val folderId = path.lastOrNull()?.id ?: "root"
    var state by remember(path) { mutableStateOf<ContentsUi?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    val picker = rememberFilePicker { picks ->
        scope.launch { picks.forEach { p -> uploader.upload(p, path.lastOrNull()?.id)
            .fold(onSuccess = { reload() }, onFailure = { snackbar.showSnackbar(it.message ?: "上传失败") }) } }
    }
    val saver = rememberFileSaver { ok -> if (ok == null) scope.launch { snackbar.showSnackbar("已取消") } }
    fun reload() { scope.launch { repo.contents(folderId).fold(onSuccess = { state = ContentsUi(it.folders, it.files) }, onFailure = { snackbar.showSnackbar(it.message ?: "加载失败") }) } }
    LaunchedEffect(path) { reload() }
    // Scaffold(topBar = 面包屑行 + 退出), 按钮行(新建文件夹/上传), LazyColumn(列表+菜单), 新建文件夹 AlertDialog, 移动对话框, SnackbarHost
}

private data class ContentsUi(val folders: List<FolderDto>, val files: List<FileDto>)
```

`ui/screens/preview/PreviewScreen.kt`：

```kotlin
package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository

@Composable
fun PreviewScreen(file: FileDto, repo: FilesRepository, onBack: () -> Unit) {
    var bitmap by remember(file.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.id) {
        runCatching { repo.previewBytes(file.id) }
            .onSuccess { bitmap = decodeToImageBitmap(it) }
            .onFailure { error = it.message }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(file.name) }, navigationIcon = {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                error != null -> Text("加载失败：$error")
                else -> CircularProgressIndicator()
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:shared:compileKotlinWasmJs :app:shared:compileDebugKotlinAndroid --console=plain`
Expected: BUILD SUCCESSFUL（wasm DOM 互操作按编译器提示机械修正）

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(shared): full Compose UI — login, file browser with menus/dialogs, image preview, nav stack"
```

---

### Task 12: Android 与 Web 入口装配 + 全量构建

**Files:**
- Modify: `app/androidApp/src/main/AndroidManifest.xml`（INTERNET 权限）
- Modify: `app/androidApp/src/main/kotlin/com/linan/barezen_drive/MainActivity.kt`
- Verify: `app/webApp/src/webMain/kotlin/com/linan/barezen_drive/main.kt`
- Modify: `app/androidApp/build.gradle.kts`（release minify 保持 false，无改动则跳过）

**Interfaces:** Consumes: `App()`。

- [ ] **Step 1: AndroidManifest 加权限**（`<manifest>` 根下、`<application>` 之前）

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: MainActivity**（确认/改为如下；启动时初始化 AndroidContext）

```kotlin
package com.linan.barezen_drive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContext.init(applicationContext)
        setContent { App() }
    }
}
```

- [ ] **Step 3: webApp main.kt** 确认调用 `App()`（模板为 `ComposeViewport(document.body!!) { App() }` 结构，保持模板形式仅替换内部为 `App()`）。

- [ ] **Step 4: 全量构建**

Run: `./gradlew :app:androidApp:assembleDebug :app:webApp:wasmJsBrowserDistribution --console=plain`
Expected: BUILD SUCCESSFUL；产物 `app/androidApp/build/outputs/apk/debug/app-debug.apk` 与 `app/webApp/build/dist/wasmJs/productionExecutable/`。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(apps): Android entry with INTERNET permission, web entry wired to shared App"
```

---

### Task 13: Docker 与 Compose（1G1C 调优）

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `docker-compose.yml`
- Create: `.env.example`

- [ ] **Step 1: Dockerfile**

```dockerfile
# ---- stage 1: web wasm dist ----
FROM gradle:9.7.1-jdk21 AS web
WORKDIR /src
COPY . .
RUN gradle :app:webApp:wasmJsBrowserDistribution --no-daemon

# ---- stage 2: server ----
FROM gradle:9.7.1-jdk21 AS server
WORKDIR /src
COPY . .
COPY --from=web /src/app/webApp/build/dist/wasmJs/productionExecutable /src/server/src/main/resources/web
RUN gradle :server:installDist --no-daemon

# ---- stage 3: runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=server /src/server/build/install/server ./
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m"
EXPOSE 8080
ENTRYPOINT ["./bin/server"]
```

- [ ] **Step 2: .dockerignore**

```text
.git
.gradle
build/
**/build/
data/
docs/
*.md
!README.md
```

- [ ] **Step 3: docker-compose.yml**

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-barezen}
      POSTGRES_USER: ${POSTGRES_USER:-barezen}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?请在 .env 中设置}
    command: ["postgres", "-c", "shared_buffers=64MB", "-c", "max_connections=20", "-c", "work_mem=4MB"]
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-barezen} -d ${POSTGRES_DB:-barezen}"]
      interval: 5s
      timeout: 3s
      retries: 10

  server:
    build: .
    environment:
      SERVER_PORT: "8080"
      JDBC_URL: jdbc:postgresql://db:5432/${POSTGRES_DB:-barezen}
      DB_USER: ${POSTGRES_USER:-barezen}
      DB_PASSWORD: ${POSTGRES_PASSWORD}
      JWT_SECRET: ${JWT_SECRET:?请在 .env 中设置}
      STORAGE_DIR: /data/storage
    volumes:
      - ./data/storage:/data/storage
    ports:
      - "${SERVER_PORT:-8080}:8080"
    depends_on:
      db:
        condition: service_healthy
    restart: unless-stopped

volumes:
  pgdata:
```

- [ ] **Step 4: .env.example**

```text
JWT_SECRET=请替换为至少32字节随机字符串
POSTGRES_DB=barezen
POSTGRES_USER=barezen
POSTGRES_PASSWORD=请替换为强密码
SERVER_PORT=8080
```

- [ ] **Step 5: 验证（本地若有 docker）**

Run: `cp .env.example .env && docker compose up -d && curl -s localhost:8080/health`
Expected: `{"status":"ok"}`；浏览器打开 `http://localhost:8080` 出现占位页（本地无 docker 则记录"待用户服务器验证"，不阻塞）。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "deploy: dockerfile, tuned compose (1 vCPU/1GB), env example"
```

---

### Task 14: 文档 + 收尾

**Files:**
- Create: `docs/architecture.md`、`docs/api.md`、`docs/development.md`、`docs/roadmap.md`
- Modify: `docs/README.md`（索引状态改"已产出"）

- [ ] **Step 1: docs/api.md** —— 照 spec 6.5 节表格逐端点展开：方法、路径、请求/响应 JSON 示例、错误码。以 Task 4–7 实现为准（若实现与 spec 有出入，以实现为准回写文档）。
- [ ] **Step 2: docs/architecture.md** —— spec 4 节图 + File/Blob 原则 + 上传时序（init->chunks->complete）文字版。
- [ ] **Step 3: docs/development.md** —— 本机开发：`./gradlew :server:run`（需本地 PG 或 H2 临时替换说明）、`./gradlew :app:androidApp:installDebug`、`./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`；测试命令；镜像说明。
- [ ] **Step 4: docs/roadmap.md** —— spec 14 节v0.2/v0.3 内容。
- [ ] **Step 5: 最终验证（用户服务器全量跑，spec 13 节清单）**

```bash
git clone <repo> && cd BareZen-Drive && cp .env.example .env && nano .env
docker compose up -d --build
curl -s https://<域名>/health
```

Android 装 debug APK 连同一服务器走 13 节第 2–4 条清单。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "docs: architecture/api/development/roadmap for v0.0.1"
```

---

## Self-Review 记录

1. **Spec 覆盖**：6.1–6.7 节（Task 2–7）、7.1–7.3 节（Task 8–11）、10 节（Task 13）、11 节（Task 14）、12 节（Task 0）、13 节（Task 14 步骤 5）、5.1 节（Task 0）。文件级重命名/移动的正向 UI 流在 Task 11 清单 5。[x] 无缺口。
2. **占位符扫描**：Task 6 `initUpload` 中"秒传返回 `uploadId=""`"与客户端 `UploadInitResponse.instantUpload` 分支一致（Task 10 Step 3 处理了 `init.file!!`）；无 TBD/TODO。wasm 互操作两处标注了"机械调整允许"，为编译期差异，非行为占位。[x]
3. **类型一致性**：`UploadApi` 统一为标准 `kotlin.Result<T>`（Task 9 说明已修正 Task 10 测试的签名）；`FileService.nameConflict` 为私有，Task 6 秒传路径内联实现（无跨任务引用）；`module(cfg, storage)` 自 Task 5 起签名，Task 2 测试在 Task 5 同步更新 —— Task 5 Step 3 已含说明。[x]
