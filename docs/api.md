# API 参考

所有请求/响应体为 JSON（二进制端点除外）。除 auth、`/health` 与 `/api/version` 外均需 `Authorization: Bearer <accessToken>`。

错误统一为：

```json
{"error": {"code": "NAME_CONFLICT", "message": "同级已存在同名文件夹或文件"}}
```

错误码全集：`INVALID_CREDENTIALS`、`TOKEN_INVALID`、`TOKEN_EXPIRED`、`USERNAME_INVALID`、`USERNAME_TAKEN`、`PASSWORD_TOO_SHORT`、`VALIDATION_ERROR`、`NOT_FOUND`、`NAME_CONFLICT`、`SESSION_NOT_FOUND`、`SESSION_COMPLETED`、`SESSION_EXPIRED`、`CHUNK_INVALID`、`CHUNK_MISSING`、`FILE_TOO_LARGE`、`INTERNAL_ERROR`。

注（以实现为准）：v0.0.1 未提供文件夹移动 API（`PATCH /api/folders/{id}` 仅接受 `name`），因此 `FOLDER_INTO_DESCENDANT` 暂不触发；`SESSION_NOT_FOUND` 统一以 404 `NOT_FOUND` 表达；`FILE_TOO_LARGE` 为预留码（服务端单块大小已校验，整文件大小上限未强制）。

## 健康检查

### GET /health

无认证。响应：`{"status":"ok"}`

## 版本查询

### GET /api/version

无认证。返回服务端构建信息，以及服务端能解析到的最新上游发布版本：

```json
{"name": "BareZen-Drive", "serverVersion": "0.0.1", "apiVersion": 1,
 "latestVersion": "0.0.1", "releaseUrl": "https://github.com/.../releases/tag/v0.0.1",
 "updateAvailable": false}
```

- 由服务端统一查询上游发布（默认仓库取 `BuildInfo.REPOSITORY_URL`，可用 `UPDATE_REPO_URL`
  覆盖；可选 `GITHUB_TOKEN` 提高速率上限），结果缓存 10 分钟，失败不缓存下次重试。客户端不直连
  GitHub，因此受限网络下仍可得到结果。
- 查询失败（离线、限流、响应异常）时 `latestVersion` 与 `releaseUrl` 为 `null`，
  `updateAvailable` 为 `false`：未知不会被当作「有新版本」。
- `updateAvailable` 仅在 `latestVersion` 已知且严格新于 `serverVersion` 时为 `true`。
- `apiVersion` 为客户端/服务端 REST 契约版本（`BuildInfo.API_VERSION`），与 `serverVersion`
  的产品版本相互独立。

## 认证

### POST /api/auth/register

```json
// 请求
{"username": "alice", "password": "password123"}
// 201 响应
{"user": {"id": "...", "username": "alice", "createdAt": "..."}}
```

用户名 3–32 位 `[a-zA-Z0-9_]`；密码至少 8 位。错误：`USERNAME_INVALID`、`USERNAME_TAKEN`、`PASSWORD_TOO_SHORT`。

### POST /api/auth/login

```json
// 请求
{"username": "alice", "password": "password123"}
// 200 响应
{"accessToken": "<JWT>", "refreshToken": "<opaque 32B>", "user": {...}}
```

错误：401 `INVALID_CREDENTIALS`。

### POST /api/auth/refresh

```json
// 请求
{"refreshToken": "<opaque>"}
// 200 响应
{"accessToken": "<新JWT>", "refreshToken": "<新opaque>"}
```

轮换语义：旧 refresh token 作废。错误：401 `TOKEN_INVALID` / `TOKEN_EXPIRED`。

### GET /api/me

返回当前用户信息（同 register 的 `user` 结构）。

## 文件夹

### GET /api/folders/{id}/contents

`id` 为 `root` 表示根层。响应：

```json
{"folder": {...} | null, "folders": [FolderDto], "files": [FileDto]}
```

FolderDto/FileDto 内时间字段为 ISO-8601 字符串（库内 epoch-millis，仅 DTO 边界转换）。folders/files 均按 name 小写排序。目录不存在 -> 404。

### POST /api/folders

```json
{"parentId": "<uuid 或省略=根层>", "name": "Photos"}
// 201 响应 {folder: FolderDto}
```

同级重名（folder/file 同命名空间）-> 409 `NAME_CONFLICT`。错误：400 名称非法（空/超 255/含 `/`）、404 父目录不存在。

### PATCH /api/folders/{id}

```json
{"name": "2027"}
// 200 响应 {folder: FolderDto}
```

自排除重名检查（自身不算冲突）。

### DELETE /api/folders/{id}

递归删除子树（含全部文件）。204。同时移除子树内全部上传会话。blob 按引用计数物理删除。

## 文件

### PATCH /api/files/{id}

```json
{"name": "新名字", "folderId": "<uuid | \"root\" | 省略=不变>"}
// 200 响应 {file: FileDto}
```

`folderId: "root"` 移到根层；省略 = 不移动。目标位置重名 -> 409。

### DELETE /api/files/{id}

204。引用计数为 0 时物理删除 blob。

### GET /api/files/{id}/content

下载文件内容。支持 `Range: bytes=a-b | a- | -n`（206 + Content-Range；不支持多段 Range）；不满足 -> 416 `bytes */total`。Content-Type 取文件 mimeType（无效值回退 octet-stream）。

### GET /api/files/{id}/thumbnail

获取客户端生成的缩略图封面（JPEG）。需要 Bearer，或携带有效 `exp`+`sig` 签名参数。响应 200 时附带 `ETag: <sha256>` 与 `Cache-Control: public, max-age=31536000, immutable`（封面内容寻址，不可变）。无封面 -> 404。

### PUT /api/files/{id}/thumbnail

上传文件封面（仅限文件属主，Bearer 认证）。请求体为 JPEG，<=512KB（超出 -> 400）。按源文件 sha256 内容寻址存储（`thumbs/<sha256>.jpg`），已存在同名封面时跳过写入（幂等）；写入后同 sha256 的所有文件行 `has_thumbnail` 置位。响应 200 空体。

### GET /api/files/{id}/link

为文件生成短时签名下载 URL（供浏览器新标签页、播放器等无法携带 Authorization 头的场景）。TTL 请求参数钳制在 30-3600 秒（默认 300）。响应：

```json
{"url": "/api/files/{id}/content?exp=1757068800&sig=<64位hex>", "expiresAt": "2026-09-05T15:35:00Z"}
```

`sig = hex(HMAC-SHA256(JWT_SECRET, "content:<fileId>:<exp>"))`，无状态校验；`GET content/thumbnail` 在无 Bearer 时以此放行，过期或篡改 -> 401。

## 分享链接（只读）

持久化分享（v0.2）：`share_links` 表，token 只存 SHA-256，文件/文件夹删除时 FK 级联清理。链接形式 `https://<host>/s/<64位hex>`，Web SPA 在该路径渲染公开分享页（无需登录）。过期/撤销/不存在的 token 统一 404 `NOT_FOUND`，不区分原因。

### POST /api/shares（属主，Bearer）

```json
// 请求（fileId 与 folderId 必须二选一；expiresInHours 缺省/null = 永久）
{"fileId": "<uuid>", "expiresInHours": 24}
// 201 响应（url 中 token 仅此次返回，此后不可再取出）
{"id": "<uuid>", "url": "/s/<64hex>", "targetType": "file", "targetName": "a.jpg",
 "expiresAt": "2026-09-10T00:00:00Z", "createdAt": "2026-09-09T00:00:00Z",
 "viewCount": 0, "downloadCount": 0}
```

`expiresInHours` 换算的 TTL 钳制在 1 小时 - 365 天。目标不存在或不属于当前用户 -> 404。错误：400 二选一校验失败。

`viewCount` 每次公开访问分享元数据或目录时 +1；`downloadCount` 每次公开下载文件体时 +1，计数同时出现在列表响应中。

### GET /api/shares?fileId=<uuid> 或 ?folderId=<uuid>（属主，Bearer）

返回该目标的活跃分享（排除已撤销/已过期）；不带查询参数时返回全部活跃分享（分享管理页使用）：

```json
{"shares": [{"id": "...", "url": "/s/<token>", "targetType": "file", "targetName": "a.jpg",
 "expiresAt": "...|null", "createdAt": "..."}]}
```

列表中的 `url` 为占位 `/s/<token>`（token 不可从哈希还原）。同时传 fileId 与 folderId 时以 fileId 为准。

### DELETE /api/shares/{id}（属主，Bearer）

物理删除分享行，链接即刻失效。204。错误：404 分享不存在。

### GET /api/public/shares/{token}（公开）

分享元数据（无属主信息、无 sha256）：

```json
{"type": "file", "name": "a.jpg", "fileId": "<uuid>", "size": 1024,
 "mimeType": "image/jpeg", "updatedAt": "..."}
// 文件夹分享
{"type": "folder", "name": "Photos", "fileId": null, "size": null, "mimeType": null, "updatedAt": null}
```

### GET /api/public/shares/{token}/contents?folder=<uuid 可选>（公开，仅文件夹分享）

列出分享根（或子树内某文件夹）的一级内容，按 name 小写排序。`folder` 缺省/`root` = 分享根。请求子树外的文件夹 -> 404。响应：

```json
{"folders": [{"id": "...", "name": "Day1"}],
 "files": [{"id": "...", "name": "a.jpg", "size": 1024, "mimeType": "image/jpeg", "hasThumbnail": true}]}
```

文件分享调用此端点 -> 404。

### GET /api/public/shares/{token}/files/{fid}/content（公开）

下载分享内文件。文件分享仅放行其所指文件；文件夹分享要求文件的 folder 链可达分享根（服务端子树边界校验，改参数不可越权）。支持 `Range`（206/416，与认证下载一致）。Content-Type 取 mimeType（无效回退 octet-stream）。

### GET /api/public/shares/{token}/files/{fid}/thumbnail（公开）

分享内文件的缩略图封面（JPEG，`ETag` + immutable 缓存头，与认证缩略图一致）。无封面 -> 404。

注：公开分享端点暂无速率限制（与全站一致，1C1G 自托管场景）；外网暴露部署可自行加反代限流。

### GET /api/album?limit=200&before=<cursor>&root=<folderId 可选>

相册时间轴：仅返回 `image/*` 文件，按 `updatedAt` 倒序，keyset 分页（`before` 格式 `<epochMillis>:<uuid>`，来自上一页 `nextCursor`）。`limit` 钳制 1-500。`root` 可选：限定扫描指定文件夹的整个子树（客户端用于只显示专用相册文件夹树）。月份分组由客户端按本地时区完成。响应：

```json
{"files": [FileDto], "nextCursor": "<epochMillis>:<uuid 或 null>"}
```

## 上传

### POST /api/uploads/init

```json
// 请求
{"folderId": "<uuid 或省略=根层>", "name": "a.bin", "size": 1024,
 "mimeType": "application/octet-stream", "sha256": "<64hex 可选>",
 "chunkSize": 5242880}
// 200 响应（正常）
{"uploadId": "<uuid>", "chunkSize": 5242880, "receivedChunks": [0,1]}
// 200 响应（秒传：sha256 命中已有 blob 且不重名）
{"uploadId": "", "chunkSize": 5242880, "receivedChunks": [], "instantUpload": true,
 "file": {FileDto}}
```

`chunkSize` 钳制到 1–20 MiB。断点续传：同用户同目录同名同尺寸存在 open 会话时复用，返回 `receivedChunks`。秒传响应中 `uploadId` 为空串。

### PUT /api/uploads/{id}/chunks/{index}

请求体为原始二进制（octet-stream），大小必须等于 chunkSize（末块为余数）。可选头 `X-Chunk-Sha256: <64hex>`，不符 -> 400 `CHUNK_INVALID`。成功 204。错误：`CHUNK_INVALID`（越界/大小不符）、409 `SESSION_EXPIRED` / `SESSION_COMPLETED`、404 会话不存在或已取消。

### POST /api/uploads/{id}/complete

分块齐全校验 -> 流式合并并计算整体 SHA-256（与 init 提供的 sha256 不符 -> 400 `CHUNK_INVALID`）-> 存 blob -> 建 files 行。缺块 -> 400 `CHUNK_MISSING`。同级重名 -> 409 `NAME_CONFLICT`。响应 `{file: FileDto}`。

### DELETE /api/uploads/{id}

放弃上传：session 置 aborted、清理 tmp。204。

## Web 静态托管

`GET /` 与任意非保留路径返回 Web 客户端（SPA 回退 index.html）；真实 wasm/js/css 资源在 `/` 下按名服务；`/api/**` 与 `/health` 优先于静态路由，未知 `/api/*` 子路径返回 404。
