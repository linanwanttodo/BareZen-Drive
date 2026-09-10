# 网盘核心体验（v0.2 第一批）设计

日期：2026-09-05
状态：已与用户对齐（缩略图策略、预览范围、相册形态、导航结构均已确认）
范围：图片/视频缩略图、在线预览（图片/视频/音频/文本/PDF）、相册月度时间轴、列表日期展示、导航调整为 首页/文件/设置 三 tab、登录页宽度验证。

## 已确认的产品决策

1. **缩略图全客户端生成**：图片与视频封面都在上传时由客户端生成并提交；服务端只存储与下发，零解码负担（1 核 1G 友好）。第三方客户端上传的内容无封面时回退为类型图标。
2. **预览类型全覆盖**：图片（含缩放）、视频、音频、文本/代码、PDF。
3. **相册按月时间轴**：按文件 `updatedAt` 分月（客户端本地时区分组），瀑布流网格，点开左右滑动浏览。EXIF 不做。
4. **导航结构（用户指定）**：底栏 3 个 tab —— **首页**（内含最近文件、相册板块、文件快捷入口）/ **文件** / **设置**；完整相册时间轴从首页"查看全部"进入（push 页面，不是 tab）。

## 服务端设计

### 缩略图存储（内容寻址）

- 封面 key：`thumbs/<sha256>.jpg`，与 blob 同用 `StorageProvider`（key 即相对路径，LocalStorageProvider 直接支持）。
- 规范：JPEG，最长边 <=512px，质量 ~80，单文件 <=512KB；由客户端保证，服务端只做大小上限校验。
- `LocalStorageProvider.put` 幂等（已存在则跳过），天然与秒传联动。

### 数据库

- `files` 表加列 `has_thumbnail BOOLEAN NOT NULL DEFAULT FALSE`（`createMissingTablesAndColumns` 自动补列，无需迁移脚本）。
- `FileDto` 增加 `hasThumbnail: Boolean = false`（默认值保证旧客户端 JSON 兼容）。
- PUT 封面时 `UPDATE files SET has_thumbnail = true WHERE sha256 = ? AND user_id = ?`，同内容多行同步置位。
- 上传 complete 落库时按 `storage.exists(thumbKey)` 初始化该列（秒传时即正确）。

### 新端点

| 方法/路径 | 认证 | 语义 |
|---|---|---|
| `PUT /api/files/{id}/thumbnail` | Bearer（必须） | 请求体 JPEG <=512KB；校验属主；幂等写入 `thumbs/<sha256>.jpg`；同 sha 置位 |
| `GET /api/files/{id}/thumbnail` | Bearer 或签名 | 返回 JPEG（`Cache-Control: immutable` + `ETag: <sha256>`）；无封面 404 |
| `GET /api/files/{id}/link?ttl=300` | Bearer（必须） | 返回 `{url, expiresAt}`；`url` 为带 `exp`+`sig` 的相对路径，TTL 钳制 30s–3600s |
| `GET /api/album?limit=200&before=<cursor>` | Bearer（必须） | 仅图片（mime 以 `image/` 开头），按 `updatedAt` 倒序 keyset 分页；返回 `{files, nextCursor}`；`limit` 钳制 1–500 |

### 签名 URL（为分享链接打地基）

- `sig = hex(HMAC-SHA256(JWT_SECRET, "content:<fileId>:<exp>"))`，无状态。
- `content` / `thumbnail` GET 端点挂到 `authenticate(optional = true)` 下：Bearer 有效 -> 放行；否则校验 `exp > now` 且 `sig` 匹配 -> 放行；否则 401。
- 过期/篡改一律 401，不区分原因（不泄漏信息）。

## 客户端设计（app/shared，Android + wasmJs）

### 封面生成（expect/actual）

```
expect suspend fun generateCover(file: PickedFile): ByteArray?
```

- commonMain 契约：返回 JPEG 字节（<=512KB）或 null（不支持/失败，静默跳过）。
- Android：图片 `BitmapFactory` inSampleSize 降采样 + `ExifInterface` 方向纠正；视频 `MediaMetadataRetriever.getFrameAtTime(0s)`；再统一缩到 <=512px、JPEG 80。
- Web：图片 `createImageBitmap(blob, resize)` -> OffscreenCanvas -> JPEG；视频 `<video>` 离屏 seek 0.1s -> canvas 截帧（kotlinx-browser DOM 访问）。
- 不把整个文件读进内存：按 readRange/平台句柄访问。

### 上传管线扩展

- `UploadManager.Phase` 增加 `COVER`；complete 成功后若 `!instantUpload && dto 可生成封面`（图片/视频）-> 生成 -> `PUT thumbnail`；失败不影响上传结果（Result 仍 success）。
- instantUpload=true 时跳过（封面按 sha256 已存在）。

### 缩略图展示

- 自研轻量 `ThumbnailLoader`（约 100 行，遵循本项目"最小依赖"风格，不引入 coil）：内存 LRU（预算 48MB）+ 同 key 去重 in-flight + 失败负缓存；数据源 `GET /thumbnail`（带 Bearer，走 ApiClient）。404 -> 永久图标。
- `FilesScreen` 列表行/网格卡片：有 `hasThumbnail` 的图片/视频显示缩略图，其余保持图标；第二行 "大小 · 日期"。
- 日期格式化用 `kotlinx-datetime`（新增依赖，唯一一个），本地时区 `yyyy-MM-dd HH:mm`。

### 预览路由（PreviewScreen 重构）

- 入参增加上下文 `(files: List<FileDto>, index: Int)`；按 mime 分发：
  - 图片：先缩略图后全图，`HorizontalPager` 在同批图片间滑动，双指缩放/拖动/双击复位。
  - 视频/音频：Android 用 **Media3 ExoPlayer**（PlayerView/音频控制条，播 signed URL，Range 边下边播）；Web `window.open` 新标签页浏览器原生播放。
  - 文本/代码：Range 请求取前 256KB，等宽字体滚动视图 + "仅显示前 256KB"提示；mime 判定：`text/*`、`application/json|xml|javascript`、常见代码扩展名。
  - PDF：Android pdfium（`com.github.mhiew:android-pdf-viewer`，jitpack；下载到 cache 后渲染）；Web 新标签页。
  - 其他：保持现状（触发下载）。

### 首页与相册

- `MainTab` 改为 `HOME("首页") / FILES("文件") / SETTINGS("设置")`。
- `HomeScreen`（纵向滚动）：相册板块（标题 + 最近 12 张横滑缩略图 + "查看全部"）-> 最近文件板块（现有 Recent 逻辑 + 日期）。预留顶部状态栏位（下一个 spec）。
- `AlbumScreen`（push）：按月分组瀑布流（`LazyVerticalStaggeredGrid`），月份标题 "2026年9月"，触底加载下一页；点图进预览（可滑动）。
- 最近/相册列表项统一显示 "大小 · 日期"。

## 登录页宽度

实现起点先在本地起 server + Web 端 1920px 实测 dcc517e 的 `widthIn(max=480.dp)`；生效即关闭该项，否则修复。

## 非目标（后续独立 spec）

首页状态仪表盘（延迟/CPU/内存/速率）、集群多节点接口预留、EXIF、分享链接完整 UI（本次仅落签名 URL 地基）、服务端缩略图兜底生成、并行上传多进度。

## 测试

- 服务端（H2 + testApplication，沿用现有模式）：thumbnail PUT/GET（幂等、属主校验、超限 400、无封面 404、同 sha 置位、秒传初始化）；link（签名通过/过期/篡改 401）；album（仅图片、倒序、keyset 分页）。
- 客户端：UploadManager COVER 阶段注入 fake generator 的单元测试；ThumbnailLoader LRU/负缓存逻辑测试（jvmTest）。
- 全量 `./gradlew :server:test :app:shared:jvmTest` + Android assembleDebug + wasmJs dist 构建通过。
