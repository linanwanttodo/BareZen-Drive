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
  db/Tables.kt          # 9 张表（users/refresh_tokens/folders/files/file_versions/upload_sessions/upload_chunks/share_links/settings）
  api/ApiException.kt   # 业务异常 + code->HTTP 映射
  api/Throttle.kt       # 进程内固定窗口限流（认证接口）+ 分享访问去重；clientIp() 默认只认 socket 对端，TRUST_PROXY=true 才取 XFF 最右条
  api/UniqueViolation.kt # 唯一键冲突 -> 409 NAME_CONFLICT（把先查后写的 TOCTOU 竞态从 500 收敛为 409）
  auth/                 # 注册/登录/刷新轮换/JWT(HS256,15min)/BCrypt(10)
  files/                # FileService/FolderRoutes/FileContent/UploadService/UploadRoutes/TrashRoutes/VersionService/VersionRoutes/BlobPurge
  storage/              # StorageProvider 接口 + LocalStorageProvider + S3StorageProvider(SigV4) + StorageRegistry（多节点预留）
  jobs/                 # UploadCleanupJob（6h：过期上传会话 + 回收站 30 天保留期物理清理）
  system/               # SystemStatsService（/api/server/stats）+ VersionService（/api/version 检查更新）
  plugins/              # Serialization/StatusPages/Authentication(JWT)/PartialContent/CallLogging/StaticWeb
```

### v0.2 新增：客户端缩略图 / 签名 URL / 相册 / 分享链接

- 缩略图全客户端生成（<=512px JPEG <=512KB）：Android（BitmapFactory inSampleSize + ExifInterface + MediaMetadataRetriever 抽帧）、Web（img/canvas 离屏，视频 seek 0.1s 截帧）。上传 complete 后经 `PUT /api/files/{id}/thumbnail` 提交；封面按源 sha256 内容寻址存于 `thumbs/<sha256>.jpg`，`files.has_thumbnail` 列置位，秒传自动继承。
- 下载/缩略图支持无状态签名 URL：`GET /api/files/{id}/link` 签发 `exp+sig`（HMAC-SHA256(JWT_SECRET)），content/thumbnail 端点挂 optional auth，Bearer 或有效签名二选一。
- 相册：`GET /api/album` 按 `(updated_at,id)` keyset 分页返回全部图片；月份分组在客户端按本地时区完成，瀑布流（LazyVerticalStaggeredGrid）。
- 预览路由 PreviewScreen 按 mime 分发：图片（HorizontalPager + transformable 缩放）、视频/音频（Android Media3 流式播 signed URL；Web 新标签页）、文本（Range 取前 256KB 等宽展示）、PDF（Android pdfium；Web 新标签页）。
- 客户端缩略图缓存 ThumbnailLoader：内存 LRU（48MB 预算）+ in-flight 去重 + 404 负缓存，无新增第三方依赖。
- 分享链接（只读）：`share_links` 表持久化，token 32 字节随机 hex、只存 SHA-256（沿用 refresh token 先例），`expires_at` 可空=永久，删除文件/文件夹时 FK 级联清理分享行。属主管理 `POST/GET/DELETE /api/shares`；公开访问 `GET /api/public/shares/{token}`（元数据/列目录/内容/缩略图），无任何认证，文件夹分享做服务端子树边界校验（沿 parent 链上溯到分享根）。Web SPA 以 `/s/<token>` 路径渲染公开分享页（静态托管的 SPA 回退天然承接），图片内联预览，其余类型下载；过期/撤销/不存在统一 404 不区分原因。

### v0.2 新增：回收站 / 收藏 / 归档 / 相册自动备份 / 认证限流

- 回收站：`DELETE /api/files/{id}` 改为软删除（只置 `deleted_at`），行与 blob 保留；`/api/trash`
  提供列出、`POST /{id}/restore` 还原（同级重名 -> 409 `NAME_CONFLICT`）、`DELETE /{id}` 彻底删除、
  `DELETE` 清空。软删的同时撤销该文件的活跃分享链接（计数保留，还原不自动重新发布）。30 天保留期由
  `UploadCleanupJob` 每 6 小时的同一循环物理回收（`FileService.purgeExpiredTrash` + `deleteStoredBlobs`）。
- 收藏与归档：`PUT /api/files/{id}/favorite`、`PUT /api/files/{id}/archive` 两个标记位；归档不改路径、
  不换文件夹。时间线/搜索/最近默认过滤 `archived_at>0` 与 `deleted_at>0`，`GET /api/album` 用
  `favorite` / `archived` 参数切子集。客户端入口：相册多选栏、文件三点菜单、图片预览页底栏，
  归档列表与回收站各一页（设置 → 媒体库）。
- 相册自动备份（Android）：`sync/` 包 —— `SyncDb`（框架 SQLite 队列，主键 content Uri，重扫幂等
  upsert，编辑过的照片回到 pending）、`SyncScan`（MediaStore 全量读）、`SyncUploadQueue`（两路并发、
  断网连续两次早停、hash 缓存复用）、`SyncWorker`/`ReconcileWorker`（前台 `dataSync` 通知 / 每日比对
  并清理设备上已消失的行）、`MediaObserverBridge`（ContentObserver 去抖 5 秒触发）。按相册勾选存
  `sync_buckets`，旧版 prefs 集合一次性迁移为 DONE 行。
- 认证限流与竞态收敛：`Throttle` 对注册/登录/刷新按 IP 固定窗口计数（429 `RATE_LIMITED`），登录另按
  用户名统计失败次数（20 次 / 15 分钟）堵住换 IP 爆破；IP 只取 socket 对端，`TRUST_PROXY=true`（可信
  反向代理之后）才读 `X-Forwarded-For` 的最右一条。`mapNameConflict` 把唯一索引冲突统一映射为 409，
  消除先查后写的 500 竞态窗口。

### v0.2 新增：文件版本 / S3 存储后端 / 首页状态仪表盘

- 文件版本：`upload/init` 新增 `overwrite`，会话行 `upload_sessions.overwrite` 记录用户选择（先取消后
  确认可升级同一会话）。覆盖上传在事务内把旧内容写成一行 `file_versions`（独立 storage_key、每文件单调
  `revision`），files 行改指新内容且保留主键，故分享链接、收藏、归档标记延续；`complete` 响应从裸
  FileDto 改为 `{file, replacedVersion}`。每个文件保留 20 个版本，写入时裁掉最旧行并回收其孤立 blob。
  `GET /api/files/{id}/versions`、`POST .../{versionId}/restore`（换回：现行内容成为新版本，被选版本转为
  现行内容，总数不变）、`DELETE .../{versionId}` 三个端点在认证块内。引用计数改为 files + file_versions
  两张表共同判定（`FileService.orphanBlobKeys`），彻底删除文件与清空回收站会级联回收版本 blob。
  客户端在文件菜单提供「版本历史」，同名冲突时以对话框让用户选择覆盖或跳过（重试走断点续传，字节不重发）。
- 存储后端可选：`STORAGE_BACKEND=local|s3`（默认 local）。`S3StorageProvider` 不引 AWS SDK，用 JDK 自带
  的 `java.net.http` 客户端 + 自实现 SigV4 签名（保持产物体积），支持任意 S3 兼容端点（MinIO、R2、COS
  等，`S3_PATH_STYLE=true` 用路径式寻址），`S3_ENDPOINT/S3_REGION/S3_BUCKET/S3_ACCESS_KEY/S3_SECRET_KEY`
  配置；`tmpDir` 仍是本地目录（分块暂存与合并都在本地，put 时先落临时文件再签名上传，因为签名需要
  payload hash 与 Content-Length，代价是每次 put 一份临时副本）。`resolvePath` 在远端后端返回 null，
  调用方一律回退到流式读取。SigV4 实现以官方测试向量校验（`server/src/test/resources/sigv4/`）。
- 多节点接口预留（未启用）：`StorageRef` 把存储位置编码为 `<backend>:<key>`（无冒号即当前默认后端），
  `StorageRegistry` 按名注册/查找 provider 并提供 `copyObject` 跨后端流式搬运。今天只有一个后端，
  所有 key 仍是裸形式，请求路径直接用注入的 `StorageProvider`；接入第二节点时改为按行的
  `storage_key` 经 `StorageRegistry.resolve` 路由即可，两列都是 512 字符自由文本，无需迁移。
- 首页状态仪表盘：`GET /api/server/stats`（需登录）返回 CPU%/内存/磁盘/上下行速率/uptime，取不到的平台
  以 `-1` 表示。`SystemStatsService` 读 `/proc/stat`、`/proc/meminfo`、`/proc/net/dev`、`/proc/uptime`，
  磁盘取 `STORAGE_DIR` 所在卷的 `File.totalSpace/usableSpace`；CPU 与网络速率是相邻两次采样的差分
  （首次采样回 `-1`，因此数值随轮询稳定，也意味着速率反映的是整个主机/容器的网卡计数而非本进程）。
  客户端首页每 3 秒拉一次并并发测量 `ping()` 往返延迟，CPU/内存/磁盘用环形进度指示，取不到时显示
  破折号；服务端不可达时给出明确提示而不是永久转圈。

### 数据模型（9 张表）

- `users`：username UNIQUE，BCrypt cost 10 哈希。
- `refresh_tokens`：只存 SHA-256，轮换（旧 token 置 revoked），30 天有效。
- `folders`：自引用 `parent_id`（NULL = 根层，无物理根目录行），UNIQUE(user, parent, name)。
- `files`：`storage_key` 显式存储，`sha256` 建非唯一索引（引用计数查询），`has_thumbnail` 封面标记；软删除与相册过滤列 `is_favorite`、`archived_at`、`deleted_at`（0 = 未归档 / 未在回收站，非 0 存 epoch 毫秒时间戳），删除文件不再物理删行，改置 `deleted_at` 进回收站。唯一键是 `UNIQUE(user, folder, name, deleted_at)`：回收站里的行带着真实时间戳，同级同名仍可再次上传；另建 `(user, deleted_at)` 索引服务回收站列表与"仅活行"过滤。
- `file_versions`：覆盖上传留下的内容快照（`file_id` FK 级联删除、`sha256`、`storage_key`、size/mime/taken_at/created_at），`UNIQUE(file_id, revision)` 让并发覆盖不会抢到同一个版本号；`files` 行永远指向最新内容，本表只存历史。
- `upload_sessions` / `upload_chunks`：断点续传会话，24h 过期；`overwrite` 列记录用户是否同意替换同名文件（重新 init 时可从 false 升为 true）。
- `share_links`：只读分享，token 只存 SHA-256（唯一索引），file/folder 可空 FK 级联删除，`expires_at` 可空=永久，`revoked_at` 预留。
- `settings`：键值型全局设置（如注册开关），单行读写。

### 核心原则：File 与 Blob 分离（内容寻址）

- 文件内容存储为 blob：`STORAGE_DIR/blobs/<sha256 前 2 位>/<第 3-4 位>/<sha256>`。
- `put` 内容寻址：blob 已存在则跳过写入（天然去重，秒传的基础）。
- 删除文件行后按 `sha256` 计数，引用计数为 0 才物理删除 blob；计数同时覆盖 `files` 与 `file_versions`
  两张表（含回收站里的软删行），所以历史版本持有的字节不会被提前回收。
- 删除文件夹时：收集子树 -> 删除子树内全部上传会话（FK 要求）-> 删文件行 -> 子先于父删文件夹行 -> 事务提交后在路由层物理删除 refcount=0 的 blob。

### 上传协议（断点续传）

```text
客户端                          服务端
    POST /api/uploads/init         
     {name,size,sha256?,chunkSize,  -> 秒传：blob 已存在且名字可用（或 overwrite）-> instantUpload=true + file
      overwrite?}                   -> 否则建/复用 open session（24h 过期，记录 overwrite 选择）
    <- {uploadId,chunkSize,          -> 同级同名且未选覆盖：秒传路径不命中，冲突在 complete 报 409
        receivedChunks[]}          
    PUT /uploads/{id}/chunks/{i}    -> tmp/<uploadId>/<i>.part + upload_chunks 登记
      （可带 X-Chunk-Sha256 校验）  
    POST /uploads/{id}/complete     -> 校验块数 -> 流式合并边算 SHA-256 -> put blob -> files 行
    <- {file,replacedVersion}        -> overwrite 命中同名行时：旧内容写成 file_versions 一行
```

- 分块大小默认 5 MiB，init 传入值钳制到 1–20 MiB，响应返回最终值，客户端以其为准。
- 断点续传：同参数重调 init 返回 `receivedChunks[]`，客户端跳过已收块。
- 客户端 UploadManager：整文件流式 SHA-256（不整载）-> init -> 顺序发块（跳过已收）-> 单块失败退避重试 3 次 -> complete。

### 下载

`GET /api/files/{id}/content` 委托 Ktor `PartialContent` 插件：无 Range -> 200 全量流式；`bytes=a-b/a-/-n` -> 206 + Content-Range（惰性切片，不整载）；不满足 -> 416。web 静态资源托管在 `/`，SPA 回退到 index.html，`/api/**` 与 `/health` 优先。

## 客户端架构（app/shared，Android + Web 共享）

- 数据层：`ApiClient`（ktor-client，Bearer 自动刷新：401 -> POST /api/auth/refresh -> 更新 TokenStore -> 重放一次；失败清空 token）+ `AuthRepository`/`FilesRepository`（标准 `Result<T>`，失败为 `ApiFailure`）+ `UploadManager`（进度 StateFlow）+ `UpdateChecker`（比较服务端 `/api/version` 与本机版本）。
- expect/actual 平台层：`TokenStorage`（Android EncryptedSharedPreferences / Web localStorage）、`Sha256er`（MessageDigest / 纯 Kotlin 增量 SHA-256）、`FilePicker`/`FileSaver`（SAF / 浏览器 input+Blob 下载）、`AppUpdate`（`InstallChannel` / `openInBrowser` / `reloadApp`，Android 与 wasm 已实现，jvm 预留）。
- UI：自实现导航栈（sealed Screen + BackHandler），三个 tab（首页=相册板块+最近 / 文件 / 设置），push 页面（相册时间轴、多类型预览、开源致谢）。主题为平面色板，单主色，无渐变、无 hover 效果。设置页含跨端「检查更新」入口。

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

## 客户端检查更新

服务端是唯一的更新判定方，客户端不直连 GitHub。

- `BuildInfo`（core）：`VERSION` 是产品版本的唯一来源，同时供服务端 `/api/version`、客户端设置页与
  Android `versionName` 使用；`API_VERSION` 是 REST 契约版本；`isNewer` / `normalize` 是服务端与
  客户端共用的版本比较逻辑。
- 服务端 `VersionService` 查询上游最新发布（默认仓库取 `BuildInfo.REPOSITORY_URL`，可用
  `UPDATE_REPO_URL` 覆盖，可选 `GITHUB_TOKEN` 提高速率上限），结果缓存 10 分钟，失败不缓存、
  下次重试；`GET /api/version` 公开返回。
- 客户端 `UpdateChecker` 调用 `GET /api/version` 并用 `BuildInfo.isNewer` 与自身版本比较，返回
  `UpToDate` / `Available(version, releaseUrl, reloadOnly)` / `Failed`。设置页「检查更新」入口在
  **每个客户端**都显示（不再仅 Android）。
- 分发通道由 `InstallChannel`（ANDROID / IOS / DESKTOP / WEB）表达。Web 由同一服务端托管产物，
  版本落后只意味着当前页面陈旧，因此 `reloadOnly = true`，提示「重新加载」；打包端提示打开发布页。
  `App()` 根部的 `WebUpdatePrompt` 在 Web 启动时静默检查一次，这就是服务端升级触达已打开页面的
  机制。

## 部署

三段 Docker 构建（web dist 拷入 server resources/web），`docker-compose.yml` 按 1G1C 调优：JVM `-Xmx256m`，PG `shared_buffers=64MB / max_connections=20 / work_mem=4MB` + healthcheck。内容存储默认落在 `STORAGE_DIR` 卷；`STORAGE_BACKEND=s3` 时改写入任意 S3 兼容桶（`S3_ENDPOINT/S3_BUCKET/S3_REGION/S3_ACCESS_KEY/S3_SECRET_KEY/S3_PATH_STYLE`），`STORAGE_DIR` 仍需可写（分块暂存目录）。`TRUST_PROXY=true` 仅在该 compose 前面还有会重写
`X-Forwarded-For` 的反向代理时开启，否则认证限流按 socket 对端计数。部署步骤见 `development.md`。

## 持续集成与预留平台

单一工作流 `.github/workflows/pipeline.yml`：`version` 解析版本号 -> `test` 门跑服务端、core 与共享模块（Android host）测试 + wasm 编译门 -> `web` 一次编译出 wasm 产物供后续复用 -> `android` 出 debug/release APK -> `server` 内嵌 web 产物出 `installDist` -> `docker` 多架构镜像 -> `manifest` 合并多架构清单 -> `release` 发布产物与镜像。`desktop` 与 `ios` 是已存在的预留任务（`continue-on-error`，Apple target 需要 macOS runner 与真实签名才可能成功）。共享模块目前只声明 android + wasmJs，`jvmMain`/`iosMain`/`jsMain` 源码与 `AppUpdate.jvm.kt` 作为预留 actual 存在，启用对应 target 即可接入。`pull_request` 事件只跑 `version` + `test`。

## 已知限制

以下边界是当前实现（v0.0.1）的刻意取舍，非缺陷，留待 roadmap 收敛：

- 回收站只覆盖单个文件的软删除（`files.deleted_at` 置位、字节保留）；整棵文件夹树的删除仍是
  直接删除子树并物理回收 `refcount=0` 的 blob，不进回收站。软删的行超过 30 天由清理循环按上传会话
  过期同一节奏物理删除，`DELETE /api/trash` 也可手动即时清空。
- 收藏（`is_favorite`）与归档（`archived_at`）仅作用于文件，不支持文件夹；时间线、搜索与相册各视图
  默认隐藏已归档与已删除行，`GET /api/album` 通过 `favorite` / `archived` 查询参数切换子集。
- 秒传依赖上传 `init` 携带整文件 `sha256`：小文件可整文件流式哈希后命中；超大视频仍需在 `init` 前
  读完算哈希。「边读边算边传、`complete` 时才上送哈希」**评估后放弃，不实施**：它省下的只是一次本地读
  （分块阶段是页缓存热读），代价却是 `init` 时拿不到哈希、秒传判定整体失效——重复文件要白传完全部字节
  才在 `complete` 时发现内容已存在，而网络传输远贵于本地读。真正的大头相册同步队列本就靠 `hash_cache`
  跳过重复哈希，这一优化对它毫无收益。协议层面它还要求给 `/complete` 加请求体或改 `init` 语义，
  属跨模块改动，更没有理由为一个负收益买单。
- 文件版本只在「同名覆盖」时产生（不做定时快照，也不解决多设备并发改同一文件的冲突，那属于 v0.3
  的同步冲突解决）。每个文件上限 20 版，超出即在写入事务里静默裁掉最旧行。恢复是一次交换：现行内容
  变成新版本，被选中的版本转为现行内容，因此版本总数不变。
- S3 后端启动时只校验配置（缺 `S3_BUCKET`/凭据、`S3_ENDPOINT` 带路径、path-style 未给 endpoint 都会
  直接拒绝启动），不探测桶：桶不存在或凭据错误要到首次读写才暴露。首页仪表盘的磁盘读数取的是
  `STORAGE_DIR` 所在卷（本地暂存盘），不反映远端桶的用量；每次 put 会在本地临时目录留一份完整副本
  再上传（SigV4 需要先知道 payload hash 与长度）。
- 多节点仍只是接口预留：`StorageRegistry` 已可按名解析与跨后端搬运，但请求路径尚未按行读取
  `backend:key` 形式的 storage_key，注册表在运行时始终只有一个后端。
- 相册自动备份：`MediaSync`（Android）由 MediaStore ContentObserver 秒级触发（去抖 5 秒），外加
  6 小时周期与每日全量比对兜底；队列落在框架 SQLite（`SyncDb`，刻意不引入 Room/KSP 以免改构建面），
  上传走前台 `dataSync` 通知保活、按网络类型切换并发路数（`SyncPolicy.lanesFor`：不计费 2 路 / 计费
  1 路，读数未知时按计费处理，反向默认会悄悄多耗移动数据）、断网连续两次即早停，单文件最多重试 5 次，已算过的
  `sha256` 缓存复用避免重读整文件。变更触发的这一路是**增量扫描**：按 `DATE_MODIFIED` 水位回溯
  5 分钟重叠窗口，只有每日对账做全量并据此剪枝——增量结果分不清「已被删除」与「没有变化」，拿它剪枝
  会清空整个队列。失败重试用线性 10 分钟退避，WorkManager 默认的指数 30 秒起步会把断网后的头几分钟
  全花在撞死链上。队列在 pass 开始时一次性快照（`SyncDb.due()`），而首次备份可能跑数小时，所以每个
  item 上传前还会按 `SyncItem.bucket` 复核一次排除状态（一次主键查询，相对 20 MiB 上传可忽略）：
  pass 进行中排除的相册不会被本次采纳，队列行保持 PENDING，勾回后仍能被复用；被跳过的 item 也计入
  进度分子，否则进度条会停在半路。策略分两处落地：周期任务把「网络 + 仅充电 + 电量非低」整体交给
  WorkManager 约束，而变更触发那一路必须用加急任务（`setExpedited`）才谈得上秒级，偏偏**加急任务只允许
  网络与存储约束**（挂充电或低电约束会直接抛 `IllegalArgumentException`），所以它只挂网络约束，充电与
  电量策略改由 `SyncWorker` 开工前自查、不满足就空跑返回，用户设的规则始终是权威。`SyncPolicy` 与
  `backupPauseReason` 是这一层的纯函数内核（宿主测试覆盖，见 `SyncPolicyTest` /
  `BackupPauseReasonTest`）：`SyncDb` / `SyncScan` 建立在 SQLiteOpenHelper、Cursor 与 MediaStore
  之上，而项目的 `androidHostTest` 是不带 Robolectric 的纯 JVM 测试，碰这些类型的代码在宿主上跑不起来。
  首启的相册勾选引导（`AlbumReviewDialog`）与完整选择页共用同一套写入路径（`MediaSync.setBucketIncluded`），
  排除项落库后即刻重驱；两者都只在进入时调一次 `MediaSync.listBuckets()`——它内部是整库 MediaStore
  扫描，逐次重扫正是增量扫描那套改造要避免的开销。`listBuckets()` 刻意**不吞扫描异常**：权限被拒返回
  空列表会与「这台设备没有相册」无法区分，一次性引导就会被一个什么都没显示的对话框白白消耗，因此两个
  调用方各自把「失败」与「真空」分开呈现，弹窗也只在真的列出过相册时才算答复（`onDone(reviewed)`）。
  引导只列照片最多的前 8 个相册，因为设备常上报几十个只含单图的相册，全量铺开正是让人不读就划掉的成因。
  是否已问过由 `AppPrefs.albumBucketsReviewed` 记录，且同时在组合内留一份 state——偏好不是可观察的，
  只写 prefs 不会触发重组，对话框就关不掉。
  仍是**单向备份**：只把设备媒体补传上云，服务器侧的删除与整理不会反向改动手机相册；`DATE_TAKEN`
  缺失的媒体以 `DATE_MODIFIED` 参与变更判定。
- 删除账号不会立刻吊销已签发的 access token：JWT 无状态且没有吊销名单，被删用户手里的 token 最长还能再用
  15 分钟（`/api/me` 会因查不到用户行而返回 401，但其余已认证端点只读 token 内的 `sub`，仍会放行）。要立刻
  失效只能更换 `JWT_SECRET`，或后续引入 token 版本号。
- 头像走公开路径：`GET /api/users/{id}/avatar` 注册在任何鉴权块之外（分享页要显示头像，无法要求登录），只按
  id 返回图片，因此知道 id 就能读到任意用户的头像。头像是公开内容，不应上传含隐私的图片。
- `GET /api/album` 每页都要读出该账号的全部文件夹行、在内存里展开子树（`FileTree`）。查询次数已从「每层一次」
  降为固定 1 次，代价是内存与扫描量随文件夹总数增长；数万文件夹量级需要改为递归 CTE 或维护嵌套集。
- Android 相册自动备份按整文件计算 `sha256`：秒传判定要求在 `init` 之前拿到哈希，因此超大视频入队后仍要完整
  读一遍（哈希按 uri+size+modified 缓存复用，未变更的文件不重算）。
- 相册扫选（拖拽多选）只在「均匀网格」档可用：瀑布流档拿不到按坐标反查条目的手段（`LazyStaggeredGridState`
  未公开 `itemIndexAt`，只有 `LazyGridState` 有），因此瀑布流下只能逐张长按。扫选本身也没有边缘自动滚动：
  拖动只在当前可见范围内改变选择，跨屏范围需要松手、滚动、再拖动。两档都响应捏合改列数（2-6，持久化）。
