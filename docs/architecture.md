# 总体架构

BareZen-Drive 是自托管个人云盘：全 Kotlin 技术栈，KMP + Compose Multiplatform 客户端（Android + Web），Ktor 服务器，PostgreSQL，Docker Compose 部署。目标环境 1 核 1 GB 服务器。当前版本 v0.0.1。

## 模块布局

```text
BareZen-Drive/
  core/          # KMP 共享 DTO 与错误码（android + wasmJs + jvm）
  server/        # Ktor 3.5.2 + Exposed 0.61 + HikariCP + PostgreSQL 16
  app/
      shared/    # Compose Multiplatform UI + 数据层（android + wasmJs）
      androidApp/# Android 入口（MainActivity）
      webApp/    # Web 入口（wasmJs）
  Dockerfile     # 三段构建：web dist -> server installDist -> JRE 运行
  docker-compose.yml
```

## 服务端架构

```text
server/src/main/kotlin/com/linan/barezen_drive/
  Application.kt        # module(cfg, storage)：config -> db -> plugins -> routes
  config/AppConfig.kt   # 环境变量读取（SERVER_PORT/JDBC_URL/DB_USER/DB_PASSWORD/JWT_SECRET/STORAGE_DIR）
  db/DatabaseFactory.kt # HikariCP（池 5，autoCommit=false）+ createMissingTablesAndColumns
  db/Tables.kt          # 7 张表（users/refresh_tokens/folders/files/upload_sessions/upload_chunks/share_links）
  api/ApiException.kt   # 业务异常 + code->HTTP 映射
  auth/                 # 注册/登录/刷新轮换/JWT(HS256,15min)/BCrypt(10)
  files/                # FileService/FolderRoutes/UploadService/UploadRoutes/FileContent
  storage/              # StorageProvider 接口 + LocalStorageProvider
  jobs/                 # UploadCleanupJob（6h 清理过期上传会话）
  plugins/              # Serialization/StatusPages/Authentication(JWT)/PartialContent/CallLogging/StaticWeb
```

### v0.2 新增：客户端缩略图 / 签名 URL / 相册 / 分享链接

- 缩略图全客户端生成（<=512px JPEG <=512KB）：Android（BitmapFactory inSampleSize + ExifInterface + MediaMetadataRetriever 抽帧）、Web（img/canvas 离屏，视频 seek 0.1s 截帧）。上传 complete 后经 `PUT /api/files/{id}/thumbnail` 提交；封面按源 sha256 内容寻址存于 `thumbs/<sha256>.jpg`，`files.has_thumbnail` 列置位，秒传自动继承。
- 下载/缩略图支持无状态签名 URL：`GET /api/files/{id}/link` 签发 `exp+sig`（HMAC-SHA256(JWT_SECRET)），content/thumbnail 端点挂 optional auth，Bearer 或有效签名二选一。
- 相册：`GET /api/album` 按 `(updated_at,id)` keyset 分页返回全部图片；月份分组在客户端按本地时区完成，瀑布流（LazyVerticalStaggeredGrid）。
- 预览路由 PreviewScreen 按 mime 分发：图片（HorizontalPager + transformable 缩放）、视频/音频（Android Media3 流式播 signed URL；Web 新标签页）、文本（Range 取前 256KB 等宽展示）、PDF（Android pdfium；Web 新标签页）。
- 客户端缩略图缓存 ThumbnailLoader：内存 LRU（48MB 预算）+ in-flight 去重 + 404 负缓存，无新增第三方依赖。
- 分享链接（只读）：`share_links` 表持久化，token 32 字节随机 hex、只存 SHA-256（沿用 refresh token 先例），`expires_at` 可空=永久，删除文件/文件夹时 FK 级联清理分享行。属主管理 `POST/GET/DELETE /api/shares`；公开访问 `GET /api/public/shares/{token}`（元数据/列目录/内容/缩略图），无任何认证，文件夹分享做服务端子树边界校验（沿 parent 链上溯到分享根）。Web SPA 以 `/s/<token>` 路径渲染公开分享页（静态托管的 SPA 回退天然承接），图片内联预览，其余类型下载；过期/撤销/不存在统一 404 不区分原因。

### 数据模型（7 张表）

- `users`：username UNIQUE，BCrypt cost 10 哈希。
- `refresh_tokens`：只存 SHA-256，轮换（旧 token 置 revoked），30 天有效。
- `folders`：自引用 `parent_id`（NULL = 根层，无物理根目录行），UNIQUE(user, parent, name)。
- `files`：`storage_key` 显式存储，`sha256` 建非唯一索引（引用计数查询），`has_thumbnail` 封面标记，UNIQUE(user, folder, name)。
- `upload_sessions` / `upload_chunks`：断点续传会话，24h 过期。
- `share_links`：只读分享，token 只存 SHA-256（唯一索引），file/folder 可空 FK 级联删除，`expires_at` 可空=永久，`revoked_at` 预留。

### 核心原则：File 与 Blob 分离（内容寻址）

- 文件内容存储为 blob：`STORAGE_DIR/blobs/<sha256 前 2 位>/<第 3-4 位>/<sha256>`。
- `put` 内容寻址：blob 已存在则跳过写入（天然去重，秒传的基础）。
- 删除文件行后按 `sha256` 计数，引用计数为 0 才物理删除 blob。
- 删除文件夹时：收集子树 -> 删除子树内全部上传会话（FK 要求）-> 删文件行 -> 子先于父删文件夹行 -> 事务提交后在路由层物理删除 refcount=0 的 blob。

### 上传协议（断点续传）

```text
客户端                          服务端
    POST /api/uploads/init         
     {name,size,sha256?,chunkSize}  -> 秒传：blob 已存在且不重名 -> instantUpload=true + file
    <- {uploadId,chunkSize,          -> 否则建/复用 open session（24h 过期）
        receivedChunks[]}          
    PUT /uploads/{id}/chunks/{i}    -> tmp/<uploadId>/<i>.part + upload_chunks 登记
      （可带 X-Chunk-Sha256 校验）  
    POST /uploads/{id}/complete     -> 校验块数 -> 流式合并边算 SHA-256 -> put blob -> files 行
    <- {file}                       
```

- 分块大小默认 5 MiB，init 传入值钳制到 1–20 MiB，响应返回最终值，客户端以其为准。
- 断点续传：同参数重调 init 返回 `receivedChunks[]`，客户端跳过已收块。
- 客户端 UploadManager：整文件流式 SHA-256（不整载）-> init -> 顺序发块（跳过已收）-> 单块失败退避重试 3 次 -> complete。

### 下载

`GET /api/files/{id}/content` 委托 Ktor `PartialContent` 插件：无 Range -> 200 全量流式；`bytes=a-b/a-/-n` -> 206 + Content-Range（惰性切片，不整载）；不满足 -> 416。web 静态资源托管在 `/`，SPA 回退到 index.html，`/api/**` 与 `/health` 优先。

## 客户端架构（app/shared，Android + Web 共享）

- 数据层：`ApiClient`（ktor-client，Bearer 自动刷新：401 -> POST /api/auth/refresh -> 更新 TokenStore -> 重放一次；失败清空 token）+ `AuthRepository`/`FilesRepository`（标准 `Result<T>`，失败为 `ApiFailure`）+ `UploadManager`（进度 StateFlow）。
- expect/actual 平台层：`TokenStorage`（Android EncryptedSharedPreferences / Web localStorage）、`Sha256er`（MessageDigest / 纯 Kotlin 增量 SHA-256）、`FilePicker`/`FileSaver`（SAF / 浏览器 input+Blob 下载）。
- UI：自实现导航栈（sealed Screen + BackHandler），三个 tab（首页=相册板块+最近 / 文件 / 设置），push 页面（相册时间轴、多类型预览、开源致谢）。主题为平面色板，单主色，无渐变、无 hover 效果。

## 客户端国际化（i18n）

界面文案全部走 `i18n` 包，不在 Composable 里硬编码字符串。

- `Strings` 接口：所有用户可见文案的唯一契约，`StringsEn` / `StringsZh` 分别提供英文与简体中文实现。
- `Language` 枚举：`EN` / `ZH`。`fromTag` 把 BCP-47 标签（如 `zh-CN`）映射到受支持语言，非中文一律回落 `EN`；`fromMode` / `modeOf` 负责与偏好里的整数互转。
- 两套读取入口，二者始终指向同一实例：
  - `LocalStrings`（`staticCompositionLocalOf`）：Composable 内使用，语言切换时触发重组，写法为 `LocalStrings.current.xxx`。
  - `I18n.strings`：非 Composable 上下文使用（`ApiClient` 网络错误文案、日期格式化、协程回调等），写法为 `I18n.strings.xxx`。
- 注入：`App()` 根部用 `CompositionLocalProvider(LocalStrings provides stringsFor(language))` 注入当前实现，并同步 `I18n.set(language)`。
- 偏好：`AppPreferences.languageMode`，`0` 跟随系统（默认）、`1` 中文、`2` 英文。跟随系统时用 expect/actual 的 `systemLanguageTag()` 读取设备语言（Android `Locale` / Web `navigator.language`）。
- 入口：设置页「语言」分段控件调用 `onLanguageModeChange`，写入偏好并即时生效。

新增一门语言的步骤：在 `Language` 增加枚举值与 `tag`，新增一个 `Strings` 实现，在 `stringsFor` 注册，在 `fromMode` / `modeOf` 补上偏好编码，并在设置页语言选项中加入对应标签。

## 部署

三段 Docker 构建（web dist 拷入 server resources/web），`docker-compose.yml` 按 1G1C 调优：JVM `-Xmx256m`，PG `shared_buffers=64MB / max_connections=20 / work_mem=4MB` + healthcheck。部署步骤见 `development.md`。
