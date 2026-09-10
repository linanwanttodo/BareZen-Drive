# 网盘核心体验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 网盘核心体验：客户端生成缩略图（图片+视频封面）、全类型在线预览（图片/视频/音频/文本/PDF）、相册月度时间轴、列表日期展示、导航改为 首页/文件/设置。

**Architecture:** 服务端新增 4 个端点（thumbnail PUT/GET、link、album）+ files 表 `has_thumbnail` 列 + 无状态 HMAC 签名 URL；客户端经 expect/actual `generateCover` 生成封面随上传提交，自研 LRU ThumbnailLoader 展示，PreviewScreen 按 mime 分发（Android Media3/pdfium，Web 新标签页），MainTab 重构为 首页/文件/设置。

**Tech Stack:** Ktor 3.5.2 + Exposed 0.61（服务端）；Compose Multiplatform + kotlinx-datetime + Media3(Android) + android-pdf-viewer(Android) + kotlinx-browser(Web)。

**Spec:** `docs/superpowers/specs/2026-09-05-netdisk-core-experience-design.md`

## Global Constraints

- 目标环境 1 核 1G：服务端不做任何图片/视频解码；封面上限 512KB、512px、JPEG。
- 所有 DTO 改动必须保持旧 JSON 兼容（新增字段带默认值）。
- 失败静默降级：封面生成/上传失败不阻塞上传主流程；缩略图 404 回退类型图标。
- 时间戳一律走 `updatedAt`（文件时间），客户端本地时区格式化。
- 遵循仓库现状：无导航库（自实现 Screen 栈）、Result<T> + ApiFailure、手写格式化函数无 locale 依赖。
- commit 规范沿用 `feat(server)/feat(shared)/feat(android)` 前缀。

---

### Task 1: 服务端缩略图（DTO + 列 + 端点）

**Files:**
- Modify: `core/src/commonMain/kotlin/com/linan/barezen_drive/core/dto/Dto.kt`（FileDto.hasThumbnail）
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/db/Tables.kt`（FilesTable.hasThumbnail）
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/files/FileService.kt`（toFileDto、getFileMeta、deleteFile 级联说明）
- Create: `server/src/main/kotlin/com/linan/barezen_drive/files/ThumbnailRoutes.kt`
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/files/UploadService.kt`（complete 时初始化 hasThumbnail）
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/Application.kt`（路由挂载）
- Test: `server/src/test/kotlin/com/linan/barezen_drive/ThumbnailTest.kt`

**Interfaces:**
- Produces: `fun Route.thumbnailRoutes(storage: StorageProvider)`；`FilesTable.hasThumbnail: Column<Boolean>`；`FileMeta.hasThumbnail`；thumb key 约定 `thumbs/<sha256>.jpg`（`fun thumbKey(sha256: String)` 放 StorageProvider.kt 顶层）。

- [ ] DTO：`FileDto(..., val hasThumbnail: Boolean = false)`；core 构建 `:core:build`
- [ ] Tables：`val hasThumbnail = bool("has_thumbnail").clientDefault { false }`
- [ ] 测试先行（ThumbnailTest）：上传->PUT jpeg->GET 返回同字节 + `image/jpeg` + `ETag: <sha256>` + `Cache-Control` 含 immutable；GET 无封面 404；PUT 超过 512KB->400；PUT 他人文件->404；同 sha 两个文件行 PUT 一次后两行 `hasThumbnail=true`（contents 校验）；秒传新行 hasThumbnail=true
- [ ] 实现：ThumbnailRoutes（PUT 读 body 上限 512_000、属主校验、`storage.put(thumbKey(sha))`、UPDATE 同 sha 置位；GET hasThumbnail 判定、FileStream 复用）；UploadService complete 落库列初始化；Application.kt 在 authenticate 块挂 `thumbnailRoutes`
- [ ] `./gradlew :server:test --tests "*Thumbnail*"` 通过；commit `feat(server): client-generated thumbnails - storage, PUT/GET endpoints, has_thumbnail`

### Task 2: 签名 URL

**Files:**
- Create: `server/src/main/kotlin/com/linan/barezen_drive/auth/LinkService.kt`
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/files/FileContent.kt`（link 端点 + content/thumbnail 的 optional 认证与 sig 校验）
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/Application.kt`（fileContentRoutes/thumbnail GET 移入 `authenticate(optional=true)`；PUT thumbnail 保持强认证：principal==null 时 401）
- Modify: `core/.../Dto.kt`（`FileLinkResponse(url, expiresAt)`）
- Test: `server/src/test/kotlin/com/linan/barezen_drive/LinkTest.kt`

**Interfaces:**
- Produces: `object LinkService { fun sign(fileId: UUID, exp: Long, secret: String): String; fun verify(fileId: UUID, exp: Long, sig: String, secret: String, now: Long = System.currentTimeMillis()): Boolean }`；`suspend fun ApplicationCall.requireUserOrSig(fileId: UUID): UUID?`（返回 userId 或 null->401 由路由处理）。

- [ ] 测试先行：带 Bearer GET link -> 200 `{url 含 sig+exp, expiresAt}`；用 url 直接 GET content（无 Authorization）-> 200 全量；`exp` 过期 -> 401；篡改 sig -> 401；PUT thumbnail 无 Bearer -> 401
- [ ] 实现：HMAC-SHA256 hex；content GET 改为 optional-auth + sig 兜底（沿 FileStream/PartialContent 不变）；thumbnail GET 同
- [ ] `./gradlew :server:test --tests "*Link*"` 通过；commit `feat(server): short-lived signed download links (HMAC, optional auth)`

### Task 3: 相册端点

**Files:**
- Modify: `core/.../Dto.kt`（`AlbumPage(files, nextCursor)`）
- Modify: `server/src/main/kotlin/com/linan/barezen_drive/files/FileContent.kt`（GET /api/album）
- Test: `server/src/test/kotlin/com/linan/barezen_drive/AlbumTest.kt`

**Interfaces:**
- Produces: `GET /api/album?limit=200&before=<updatedAt:id>`；cursor 无更多->null。keyset 条件：`(updatedAt < b) or (updatedAt = b and id < bId)`。

- [ ] 测试先行：仅 image/*；updatedAt 倒序；limit 钳制；cursor 翻页衔接（第二页首条 < 第一页末条）；非图片不出现在结果
- [ ] 实现：FilesTable where user + mimeType like "image/%" + keyset；commit `feat(server): album timeline endpoint (image keyset pagination)`

### Task 4: 客户端数据层

**Files:**
- Modify: `app/shared/.../data/api/ApiClient.kt`（putThumbnail/getThumbnailBytes/link/album）
- Modify: `app/shared/.../data/repo/FilesRepository.kt`（对应方法 + ThumbnailApi）
- Modify: `app/shared/.../data/upload/UploadManager.kt`（Phase.COVER；构造注入 `coverGen: (suspend (PickedFile, FileDto) -> Unit)?`）
- Create: `app/shared/.../platform/CoverGenerator.kt`（expect）
- Test: `app/shared/src/jvmTest/.../UploadManagerCoverTest.kt`

**Interfaces:**
- Produces: `expect suspend fun generateCover(file: PickedFile): ByteArray?`；`FilesRepository.uploadThumbnail(id, bytes): Result<Unit>`、`thumbnailBytes(id): Result<ByteArray>`（404->ApiFailure.Http）、`fileLink(id, ttl=300): Result<FileLinkResponse>`、`album(limit, before): Result<AlbumPage>`；UploadManager 上传成功后自动尝试封面（失败静默）。
- [ ] jvmTest：fake UploadApi+generator 验证 COVER 阶段推进与失败不阻塞；`./gradlew :app:shared:jvmTest` 通过
- [ ] commit `feat(shared): cover upload pipeline + thumbnail/link/album api`

### Task 5: Android 封面生成

**Files:**
- Create: `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/platform/CoverGenerator.android.kt`
- Modify: `app/shared/src/androidMain/.../platform/FilePicker.kt`（PickedFile 暴露 Uri，internal）
- [ ] 图片：ContentResolver openInputStream + BitmapFactory inSampleSize（目标 <=1024 解码）-> ExifInterface 方向 -> 缩放 <=512 -> JPEG 80
- [ ] 视频：MediaMetadataRetriever.setDataSource(uri) getFrameAtTime(0) -> 同缩放管线
- [ ] 任何异常返回 null；`./gradlew :app:shared:assembleDebug` 编译通过；commit `feat(android): local cover generation for images and videos`

### Task 6: Web 封面生成

**Files:**
- Create: `app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/platform/CoverGenerator.wasmJs.kt`
- Modify: `app/shared/src/wasmJsMain/.../platform/FilePicker.kt`（PickedFile 暴露浏览器 File/Blob，internal）
- Modify: `gradle/libs.versions.toml` + `app/shared/build.gradle.kts`（kotlinx-browser；kotlinx-datetime）
- [ ] 图片：blob -> createImageBitmap(resize 512) -> OffscreenCanvas -> convertToBlob(jpeg .8) -> arrayBuffer
- [ ] 视频：document.createElement("video") + objectURL + seek 0.1 + canvas2d drawImage -> toBlob
- [ ] wasm dist 构建通过；commit `feat(web): canvas-based cover generation for images and videos`

### Task 7: 缩略图展示 + 日期

**Files:**
- Create: `app/shared/.../ui/media/ThumbnailLoader.kt`（内存 LRU 48MB + in-flight 去重 + 404 负缓存）
- Create: `app/shared/.../ui/media/Thumbnail.kt`（`@Composable fun Thumbnail(file, modifier)`：有 hasThumbnail->AsyncImage 样式加载，否则图标）
- Create: `app/shared/.../ui/media/DateText.kt`（`formatDateTime(iso): "yyyy-MM-dd HH:mm"`，kotlinx-datetime 本地时区）
- Modify: `FilesScreen.kt`（FileRow/FileTile 用 Thumbnail + "大小 · 日期"）、`RecentScreen.kt`（RecentRow 同）
- [ ] jvmTest：LRU 驱逐/负缓存/并发去重；commit `feat(shared): thumbnails and dates in file lists and grid`

### Task 8: 预览路由

**Files:**
- Modify: `app/shared/.../ui/screens/preview/PreviewScreen.kt` -> 拆分目录 preview/
  - `PreviewRouter.kt`（mime 分发；入参 `(files, index, repo, onBack)`）
  - `ImageViewer.kt`（HorizontalPager + transformable 缩放/拖动/双击复位 + 全图懒加载）
  - `TextViewer.kt`（Range 前 256KB 等宽滚动 + 提示条）
  - `OpenExternally.kt`（Web：取 link -> window.open）
- Create: `app/shared/src/androidMain/.../ui/preview/VideoPlayer.kt`（Media3 PlayerView AndroidView，播 signed URL）
- Create: `app/shared/src/androidMain/.../ui/preview/AudioPlayer.kt`
- Create: `app/shared/src/androidMain/.../ui/preview/PdfViewer.kt`（pdfium，缓存文件渲染）
- Modify: `app/shared/build.gradle.kts`（androidMain: media3-exoplayer/media3-ui；pdf-viewer jitpack）、根 `settings.gradle.kts`（maven jitpack）
- Modify: `app/shared/.../data/api/ApiClient.kt`（download(id, range: LongRange?)）
- Modify: `App.kt`（Screen.Preview 携带 files+index）
- [ ] 编译 + 手测路径：本地 server 起各类型预览；commit `feat(shared): full preview router - image zoom, media3 video/audio, text, pdf, web new-tab`

### Task 9: 首页 / 相册 / 导航

**Files:**
- Modify: `app/shared/.../ui/shell/MainShell.kt`（MainTab.HOME/FILES/SETTINGS）
- Create: `app/shared/.../ui/screens/home/HomeScreen.kt`（相册板块：横滑 12 张 + 查看全部；最近板块复用 Recent 列表）
- Create: `app/shared/.../ui/screens/album/AlbumScreen.kt`（月分组瀑布流 + 触底分页 + 点图进预览可滑动）
- Modify: `App.kt`（tab 默认 HOME；Screen.Album push/pop）
- Modify: `RecentScreen.kt`（并入 HomeScreen 或保留内部组件，二选一以实际改动最小为准）
- [ ] 编译 + 手测：三 tab、首页板块、相册分组/分页/预览滑动；commit `feat(shared): home tab with album section and monthly timeline album`

### Task 10: 验证与收尾

- [ ] 本地起 postgres 容器 + server（环境变量方式），浏览器 1920px 验证登录页宽度（不生效则修复并 commit）
- [ ] 端到端手测清单：上传图片/视频（观察 COVER 阶段）-> 缩略图显示 -> 相册 -> 各类型预览 -> 日期显示
- [ ] 文档：`docs/architecture.md`、`docs/api.md`、`docs/roadmap.md`（勾掉已完成项）；commit `docs: v0.2 core experience - thumbnails, preview, album`
- [ ] 全量 `./gradlew :server:test :app:shared:jvmTest :app:androidApp:assembleDebug` 通过
