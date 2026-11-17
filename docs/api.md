# API 参考

所有请求/响应体为 JSON（二进制端点除外）。除 auth、`/health` 与 `/api/version` 外均需 `Authorization: Bearer <accessToken>`。

错误统一为：

```json
{"error": {"code": "NAME_CONFLICT", "message": "同级已存在同名文件夹或文件"}}
```

错误码全集：`INVALID_CREDENTIALS`、`TOKEN_INVALID`、`TOKEN_EXPIRED`、`USERNAME_INVALID`、`USERNAME_TAKEN`、`PASSWORD_TOO_SHORT`、`VALIDATION_ERROR`、`FORBIDDEN`、`NOT_FOUND`、`NAME_CONFLICT`、`SESSION_NOT_FOUND`、`SESSION_COMPLETED`、`SESSION_EXPIRED`、`CHUNK_INVALID`、`CHUNK_MISSING`、`FILE_TOO_LARGE`、`RATE_LIMITED`、`REGISTRATION_DISABLED`、`INTERNAL_ERROR`。

注（以实现为准）：v0.0.1 未提供文件夹移动 API（`PATCH /api/folders/{id}` 仅接受 `name`），因此 `FOLDER_INTO_DESCENDANT` 暂不触发；`SESSION_NOT_FOUND` 统一以 404 `NOT_FOUND` 表达；`FILE_TOO_LARGE` 已在 `POST /api/uploads/init` 强制——`size` 超过 `MAX_FILE_SIZE`（环境变量，默认 10 GiB，启动时注入）时返回 413，分块单块超限返回 400 `CHUNK_INVALID`。该上限只按单次上传会话计，尚未做用户级总配额。

### 限流与计数去重

未认证的认证接口按调用方 IP 做固定窗口计数，超限返回 429 `RATE_LIMITED`：注册 5 次 / 5 分钟，
登录 10 次 / 1 分钟，刷新 30 次 / 1 分钟。登录另有一层按用户名的失败计数（20 次 / 15 分钟，**只统计
失败**），轮换来源地址无法对同一账号继续爆破——**该额度在尝试前原子占用、成功后退还**，否则并发的
N 次失败登录都能在计数落库前通过检查，把 20 次的上限一次性打穿。计数在进程内（单机自托管，无跨节点
协调），重启清零，并对键数量设上限。

**客户端地址默认只取 socket 对端**。`X-Forwarded-For` 只在请求对端落在 `TRUST_PROXY_CIDRS` 列出的
网段内时才解析（逗号分隔的 CIDR 或裸地址，非法项拒绝并告警），多级代理取最右「非受信段」那条。
不配就是完全不信任：旧的 `TRUST_PROXY=true` 对**任何**对端都采信 XFF，等于把限流桶交给能碰到端口
的人（每请求换一个伪造头就是一个新桶），它仅作兼容保留，两项同时给时以 CIDR 列表为准。对端取的是
`remoteAddress` 而非 `remoteHost`——后者是反向 DNS 出来的名字，可被 PTR 影响，还多一次 DNS 往返。

`install.sh` 的 HTTPS 安装走同机独立 Caddy 容器，应用看到的对端是 docker 网段地址，其 `.env` 已默认
写入 `TRUST_PROXY_CIDRS=172.16.0.0/12`。**不配的后果不是被攻击，而是所有访客共用 Caddy 一个桶**
（一人用满登录限额，全员 429）。直连部署什么都不用设。

分享链接的访问计数按「分享 + 来源」维度去重：同一来源 5 分钟内的重复访问只计一次，避免刷新刷
`viewCount`；下载计数不去重（每次文件下载都算一次）。

## 健康检查

### GET /health

无认证。响应：`{"status":"ok"}`。只做存活：HTTP 引擎起来了就返回 200，不碰数据库。

### GET /readyz

无认证。从连接池借一条连接跑 `SELECT 1`，因此它才是「能干活」的答案：库连不上时返回
`503 {"status":"unready","db":"down"}`，连上时返回 `200 {"status":"ready","db":"ok"}`。
镜像的 `HEALTHCHECK` 与 install.sh 的就绪等待都读这个。

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
- 数据源优先读取发布清单 `update.json`（`UPDATE_MANIFEST_URL` 可覆盖，默认指向本仓库最新
  Release 的 `releases/latest/download/update.json`，无限流），读取失败回落 GitHub API。返回体
  的 `assets` 字段携带清单中每个包的平台/架构/格式/直链/sha256/大小，客户端据此按平台直接下载：

```json
{"name":"BareZen-Drive","serverVersion":"0.0.1","apiVersion":1,
 "latestVersion":"0.0.2","releaseUrl":"https://github.com/.../releases/tag/v0.0.2",
 "updateAvailable":true,
 "assets":[{"platform":"android","arch":"any","kind":"apk","url":"https://...","sha256":"...","size":0}]}
```

## 服务器设置（注册开关）

### GET /api/settings/registration（公开）

登录页据此隐藏注册入口。响应：`{"open": true}`

### PATCH /api/settings/registration（需登录）

请求 `{"open": false}`，响应同 GET。设置持久化在 `settings` 表；新库默认开放（先建第一个账号，
再在设置里关闭）。

## 认证

### POST /api/auth/register

```json
// 请求
{"username": "alice", "password": "password123"}
// 201 响应（UserDto 本体，无包裹）
{"id": "...", "username": "alice", "createdAt": "..."}
```

用户名 3–32 位 `[a-zA-Z0-9_]`；密码至少 8 位。错误：`USERNAME_INVALID`、`USERNAME_TAKEN`、`PASSWORD_TOO_SHORT`。

注册受服务端「开放注册」开关控制，**默认关闭**。关闭时返回 403 `REGISTRATION_DISABLED`。开关在
客户端「设置 → 服务器」里切换（`PATCH /api/settings/registration`），关闭后登录页自动隐藏注册入口。

**为什么默认关闭**：实例 owner 是「最早创建的账号」，注册又曾经默认开放，于是公网抢先注册的第一个人
直接成为 owner——能列全部用户、删任意账号、重开注册。默认关闭后首账号只剩两条路：安装向导的
`BOOTSTRAP_ADMIN_USER` / `BOOTSTRAP_ADMIN_PASSWORD`（`install.sh` 与 `docker-compose.yml` 已默认写入），
或从服务器本机 loopback 直连注册。后者额外要求**请求不带任何转发头**：一旦有上游代理标注过，本机就
无法与公网区分，bootstrap 位随之关闭。

首个账号创建时用原子 CAS 把 owner 固化进 `settings.owner_id`，`requireOwner` 优先读固化值，并发抢注
只有一个人拿得到。**已有账号的老实例升级不受影响**：无固化值时取「最早 createdAt、id 升序」的第一个
原子写回。副作用是从未设过该开关的老实例升级后注册变为关闭，需要 owner 在设置里点一次「开放注册」——
这是「出厂默认关闭」的必然结果。

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

返回当前用户信息（同 register 响应，UserDto 本体）。

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
// 201 响应（FolderDto 本体，无包裹）
{"id": "...", "name": "Photos", "parentId": null, "createdAt": "...", "updatedAt": "..."}
```

同级重名（folder/file 同命名空间）-> 409 `NAME_CONFLICT`。错误：400 名称非法（空/超 255/含 `/`）、404 父目录不存在。

### PATCH /api/folders/{id}

```json
{"name": "2027"}
// 200 响应（FolderDto 本体，无包裹）
```

自排除重名检查（自身不算冲突）。

### DELETE /api/folders/{id}

递归删除子树（含全部文件）。204。同时移除子树内全部上传会话。blob 按引用计数物理删除。

## 文件

### PATCH /api/files/{id}

```json
{"name": "新名字", "folderId": "<uuid | \"root\" | 省略=不变>"}
// 200 响应（FileDto 本体，无包裹）
```

`folderId: "root"` 移到根层；省略 = 不移动。目标位置重名 -> 409。

### PUT /api/files/{id}/favorite

```json
{"favorite": true}
// 200 响应（FileDto 本体）
```

设置/清除单文件收藏标记。请求体为 `FavoriteRequest`，返回更新后的 FileDto（`isFavorite` 反映新值）。非属主 -> 403。

### PUT /api/files/{id}/archive

```json
{"archived": true}
// 200 响应（FileDto 本体）
```

归档/取消归档单文件。请求体为 `ArchiveRequest`；归档后该文件从时间线、搜索与各相册默认视图隐藏（仅 `GET /api/album?archived=true` 可见）。返回更新后的 FileDto（`archivedAt` 反映新值）。非属主 -> 403。

### DELETE /api/files/{id}

204。**软删除**：仅置 `files.deleted_at`，行与 blob 保留，文件进入回收站（见下）；时间线/搜索/列表默认过滤掉软删行。30 天后由清理循环物理删除，或由 `DELETE /api/trash/{id}` 立即彻底删除。整棵文件夹树的删除仍为物理删除（不走回收站）。

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

## 文件版本（覆盖历史，需登录）

带 `overwrite` 的上传把被替换掉的内容写入 `file_versions` 表：一行 = 一份完整内容快照（独立的 storage key + 元数据），`revision` 在单个文件内从 1 递增。files 行始终指向最新内容，所以下载、预览、分享链接的语义不变。每个文件最多保留 20 个版本，写入时静默裁掉最旧的行；版本 blob 与文件 blob 共用同一套引用计数（files 与 file_versions 都不再引用的 key 才物理删除），因此同一 sha256 只存一份，也不会被提前回收。回收站里的文件同样可查看与恢复版本（软删除不动历史）。

FileVersionDto：

```json
{"id": "<uuid>", "fileId": "<uuid>", "revision": 2, "size": 2048, "sha256": "<64hex>",
 "mimeType": "text/plain", "takenAt": null, "createdAt": "2026-09-12T10:00:00Z"}
```

### GET /api/files/{id}/versions

按 `revision` 倒序返回该文件的历史版本：`{"versions": [FileVersionDto]}`。文件不存在或非属主 -> 404（不区分两者，避免探测他人文件是否存在）。

### POST /api/files/{id}/versions/{versionId}/restore

把某个历史版本换回当前内容，返回换回后的 FileDto 本体（无包裹）。实现上是一次双向交换：现行内容先成为一条新的最旧版本，被选中的版本从历史中移除并写到 files 行上，故版本总数不增。versionId 不属于该文件 -> 404。

### DELETE /api/files/{id}/versions/{versionId}

丢弃单个历史版本（当前内容不受影响），其 blob 在失去最后一个引用时一并物理回收。204。versionId 不属于该文件 -> 404。

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

### GET /api/public/shares/{token}/contents?folder=<uuid 可选>&limit=<可选>&folderCursor=<可选>&fileCursor=<可选>（公开，仅文件夹分享）

列出分享根（或子树内某文件夹）的一级内容，按 name 小写排序（数据库排序）。`folder` 缺省/`root` = 分享根。请求子树外的文件夹 -> 404。

这是匿名可调的公开端点，所以一页为限：`limit` 缺省 200、上限 1000（超出按上限取，不报错）。`folderCursor`/`fileCursor` 是两份列表各自的位置，形如 `<名字>:<id>`，由上一页响应的同名字段给出；为 `null` 表示该列表已到底。客户端要整份目录就顺着游标连翻。

```json
{"folders": [{"id": "...", "name": "Day1"}],
 "files": [{"id": "...", "name": "a.jpg", "size": 1024, "mimeType": "image/jpeg", "hasThumbnail": true}],
 "folderCursor": "Day1:3f2c...", "fileCursor": null}
```

文件分享调用此端点 -> 404。

### GET /api/public/shares/{token}/files/{fid}/content（公开）

下载分享内文件。文件分享仅放行其所指文件；文件夹分享要求文件的 folder 链可达分享根（服务端子树边界校验，改参数不可越权）。支持 `Range`（206/416，与认证下载一致）。Content-Type 取 mimeType（无效回退 octet-stream）。

### GET /api/public/shares/{token}/files/{fid}/thumbnail（公开）

分享内文件的缩略图封面（JPEG，`ETag` + immutable 缓存头，与认证缩略图一致）。无封面 -> 404。

注：公开分享端点暂无速率限制（与全站一致，1C1G 自托管场景）；外网暴露部署可自行加反代限流。

### GET /api/album?limit=200&before=<cursor>&root=<folderId 可选>&favorite=true&archived=true

相册时间轴：仅返回 `image/*` 文件，按 `updatedAt` 倒序，keyset 分页（`before` 格式 `<epochMillis>:<uuid>`，来自上一页 `nextCursor`）。`limit` 钳制 1-500。`root` 可选：限定扫描指定文件夹的整个子树（客户端用于只显示专用相册文件夹树）。默认排除回收站（`deleted_at`）与已归档（`archived_at`）行；`archived=true` 改为只列已归档，`favorite=true` 在当前作用域内进一步收窄为已收藏子集（收藏视图用）。月份分组由客户端按本地时区完成。响应：

```json
{"files": [FileDto], "nextCursor": "<epochMillis>:<uuid 或 null>"}
```

## 回收站（软删除文件，需登录）

`DELETE /api/files/{id}` 的软删除目标。所有端点按调用者自身行作用域，非属主 -> 403。超过 30 天的行由清理循环自动物理删除，因此回收站不是唯一的出口。

### GET /api/trash

返回当前用户回收站内的一页文件（`TrashResponse`）。

**分页**：`?limit=` 缺省 100、上限 500（`coerceIn`）；`?cursor=` 取上一页的
`nextCursor`。keyset 为 `(deleted_at DESC, id DESC)`，游标串是
`"<deletedAtMillis>:<uuid>"`——id 是必需的兜底：一次批量删除往往让整页行共享
同一个 `deleted_at`，没有它游标会卡在第一页。非法游标从头再来（与
`/api/files/contents`、`/api/files/album` 同口径）。

上限不是形式主义：清空一个两万张的回收站后，旧的「一次返回全部」会把两万行
DTO 一次性序列化进响应体与堆内存。`nextCursor` 为 `null` 表示到底；该字段带
默认值，老客户端多解一个它忽略的字段即可。

```json
{"files": [FileDto]}
```

### POST /api/trash/{id}/restore

把软删文件还原回时间线（清空 `deleted_at`），返回还原后的 FileDto 本体。文件不存在 -> 404；还原后与同级文件重名 -> 409 `NAME_CONFLICT`；对未在回收站的行按原样返回（幂等）。

### DELETE /api/trash/{id}

**彻底删除**单个回收站文件：删除行并按引用计数回收 blob，204。对未软删（不在回收站）的 id -> 400；文件不存在 -> 404。

### DELETE /api/trash

清空回收站：删除该用户全部软删行并回收 blob，204。

## 上传

### POST /api/uploads/init

```json
// 请求
{"folderId": "<uuid 或省略=根层>", "name": "a.bin", "size": 1024,
 "mimeType": "application/octet-stream", "sha256": "<64hex 可选>",
 "chunkSize": 5242880, "overwrite": false}
// 200 响应（正常）
{"uploadId": "<uuid>", "chunkSize": 5242880, "receivedChunks": [0,1]}
// 200 响应（秒传：sha256 命中已有 blob 且名字可用或已选择覆盖）
{"uploadId": "", "chunkSize": 5242880, "receivedChunks": [], "instantUpload": true,
 "file": {FileDto}}
```

`chunkSize` 钳制到 1–20 MiB。断点续传：同用户同目录同名同尺寸存在 open 会话时复用，返回 `receivedChunks`。秒传响应中 `uploadId` 为空串。

`overwrite: true` 表示同名文件（未进回收站）允许被替换：现有内容成为一条可恢复的历史版本（见「文件版本」），files 行主键不变，因此分享链接、收藏与归档标记都延续到覆盖后的文件。字节完全相同时是幂等空操作，不产生版本。会话已存在时再次带 `overwrite: true` 会把该会话升级为覆盖模式（用户先取消、后确认的场景）。同名的是**文件夹**时不可覆盖，仍在 complete 阶段报 409。

### PUT /api/uploads/{id}/chunks/{index}

请求体为原始二进制（octet-stream），大小必须等于 chunkSize（末块为余数）。可选头 `X-Chunk-Sha256: <64hex>`，不符 -> 400 `CHUNK_INVALID`。成功 204。错误：`CHUNK_INVALID`（越界/大小不符）、409 `SESSION_EXPIRED` / `SESSION_COMPLETED`、404 会话不存在或已取消。

### POST /api/uploads/{id}/complete

分块齐全校验 -> 流式合并并计算整体 SHA-256（与 init 提供的 sha256 不符 -> 400 `CHUNK_INVALID`）-> 存 blob -> 建 files 行。缺块 -> 400 `CHUNK_MISSING`。同级重名 -> 409 `NAME_CONFLICT`。响应包裹为新对象（与旧的裸 FileDto 不同）：

```json
{"file": {FileDto}, "replacedVersion": {FileVersionDto} | null}
```

`replacedVersion` 仅在这次上传覆盖了同名文件且内容确有变化时非空（即会话以 `overwrite` 建立）。

### DELETE /api/uploads/{id}

放弃上传：session 置 aborted、清理 tmp。204。

## WebDAV（挂载为网络盘）

`/dav` 把账号的文件树暴露成可挂载的 WebDAV 共享，macOS Finder、Windows 资源管理器、Linux Nautilus / davfs2 / rclone 都能把它当网络盘读写。

与 `/api` 完全分离的两点设计约束：

- **认证方式不同。** 挂载客户端只会说 HTTP Basic —— 没有刷新令牌那个来回可用。RFC 7235 §2.1 规定 HTTP 认证被假定为无状态，认证所需信息必须由请求自身提供，所以凭据必须自包含。这就是「每设备应用密码」存在的协议层理由。
- **不能用主账号密码。** 挂载后每个请求都要鉴权一次，而账号密码是 bcrypt（cost 10，约 100ms/次），浏览一个目录就是几百次。Nextcloud 文档以「significant performance penalty」劝阻用主密码，本项目的数字更难看。应用密码是 32 字节随机、按 SHA-256 **精确查**（不做 bcrypt）：高熵没有离线爆破面，精确匹配不引入任何 KDF 延迟。

### GET /api/webdav/tokens（需登录）

挂载凭据的增删查，是普通的 Bearer 端点（挂载自身无法管理凭据 —— 它没有提示输入新密码的办法）。无头部署也可以直接用 curl 调这里铸一个。

```json
// POST /api/webdav/tokens  {"label":"公司 iMac","readOnly":false}
{"token": {"id":"<uuid>","label":"公司 iMac","readOnly":false,
           "createdAt":"2026-10-01T07:00:00Z","lastUsedAt":0},
 "plaintext":"<64位hex>"}
```

`plaintext` **只在这一条响应里出现一次**，之后不可再取（服务端只存 SHA-256）。`GET` 返回 `{"tokens":[...]}`，明文不在其中；`DELETE /api/webdav/tokens/{id}` 吊销单个，204。

每台设备一个凭据：WebDAV 客户端会把密码明文存进配置或系统钥匙串，唯一能兜住泄露的方式是能只掐断一台设备而不动其他。`readOnly` 为 true 的挂载点所有写方法返 403。令牌对 `/api/**` 完全无效（不能登录 Web UI、不能调管理端点），与 Nextcloud 一致。

### 方法一览

| 方法 | 说明 |
|---|---|
| `OPTIONS` | 200 + `DAV: 1` + `Allow` + `MS-Author-Via: DAV` |
| `PROPFIND` | Depth 0/1，207 Multi-Status |
| `GET` / `HEAD` | 支持 Range / 206 / 416 / 条件请求 |
| `PUT` | 新建 201，覆盖 204 |
| `MKCOL` | 新建集合 201 |
| `MOVE` / `COPY` | 新建 201，覆盖**文件** 204，无变化 204；目标是已存在的**集合** 409 |
| `DELETE` | 204。**文件**进回收站可恢复；**集合**是硬删除整棵子树 |
| `LOCK` / `UNLOCK` | **501，且不进 `Allow`** |

### 五条必须知道的限制

1. **没有锁。** `LOCK`/`UNLOCK` 返 501，并且**刻意不**在 `Allow` 与 `DAV:` 里宣告。宣告了会让客户端以为锁可用从而改变行为：资源管理器拒绝打开它锁不了的���件，Office 拒绝保存。**实际后果：用 Office 打开网盘上的文档，保存可能失败**，因为 Office 保存前要加锁。
2. **必须 HTTPS。** HTTP Basic 以 base64 明文传应用密码，且 Windows 客户端在明文 HTTP 上默认禁用 Basic。服务端**不拒绝**明文请求 —— compose 部署里 Caddy 与本进程之间本就是明文，在这一层拒绝会把受支持的部署一起打死；只在启动时打一条 WARN。
3. **没有秒传。** 协议没有让客户端「先报哈希」的钩子，所以 WebDAV PUT 永远走完整上传。桌面客户端拖文件基本都带 `Content-Length`；不带时（chunked 传输）服务端先落盘测大小再切块。
4. **覆盖不产生版本。** 挂载覆盖走的是 WebDAV 专用语义，不进 `file_versions`。被替换掉的 blob 会进入宽限期删除队列（与回收站同一套），所以不会留下无主孤儿，但它确实不在版本历史里 —— 需要历史请用 Web UI 或 `/api`。
5. **未经真实客户端验证。** 本功能只到「协议层测试全绿 + 真实 HTTP 冒烟脚本全绿」（`scripts/dav_smoke.sh`）。开发环境没有可用的 Windows / macOS 客户端，所以「Explorer 与 Finder 实际挂得上」这句话没有被验证过。

### 资源模型与 href 编码

`/dav/` 是该用户自己的根。文件夹 ↔ 集合，文件（`deleted_at = 0`）↔ 非集合，回收站不可见。相册树按普通文件夹原样暴露（`相册/我的手机/相机/…`）—— 隐藏它会让手机自动备份的文件在网盘里凭空消失，比显示目录结构更反直觉。

路径逐段解析（每段一次索引查询），不使用前缀匹配全表扫。名字里不允许 `/`，所以每个名字恰好对应一个路径段。

**href 与 displayname 走两套不同的编码**，这是经典 bug 源，实现与测试都分别钉住：

- `href`：RFC 3986 百分号编码，逐段编码后用 `/` 连接。客户端靠它解析路径，`#` 会截断 href、`?` 会开启查询串。
- `displayname`：只做 XML 转义，**绝不百分号编码** —— 客户端要的是原名，编码过会让用户在文件浏览器里看到 `a%20b.txt`。

返回的属性：`resourcetype`、`displayname`、`getcontentlength`、`getlastmodified`、`getetag`、`creationdate`。

- `getlastmodified` 取 `updated_at` 而非 `taken_at`。客户端拿它决定要不要重取，用拍摄时间会让一张 2021 年的照片看起来「自 2021 年起从未修改」。
- `getetag` 是 blob key 的**末段 sha256**（内容寻址，天然强标签），带引号。不用整个 storage key —— 它形如 `blobs/ab/cd/<sha>`，塞进 ETag 等于把内部存储布局发给客户端。集合没有内容，故**不返回** `getetag`。
- 请求里点名了但不存在的属性，按 RFC 在 multistatus 内联返 404。`propname`（只要名字）与空 `<D:prop/>`（等同 allprop）都支持。

### DELETE 的不对称：文件可恢复，集合不可

`DELETE` 打到文件上是软删除，进回收站、可恢复 —— 与 Web UI 的删除一致。

`DELETE` 打到集合上是**硬删除整棵子树，不可恢复**。这不是省事，是数据模型决定的：`folders` 表没有 `deleted_at` 列，而 `files.folder_id` 对 `folders.id` 有外键，软删除的文件仍然指着它所属的文件夹。所以只要回收站里还有一个文件指向某个文件夹，那一行就不能删 —— 「整个文件夹进回收站」在这个模型下无法表达。

这与应用里既有的 `DELETE /api/folders/{id}` 行为一致，所以从 WebDAV 删掉的文件夹与从 Web 删掉的文件夹后果相同：回收站里不会留下任何东西。

**建议**：想保险就先移动而不是删除；WebDAV 客户端的删除通常没有确认对话框。

### MOVE / COPY 的目标语义

「覆盖」只对**文件**成立。目标是已存在的**文件**且 `Overwrite: T` → 覆盖后 204，被替换的那一行进回收站（与 Web UI 的删除同一个承诺，因此可撤销，也不会留下失去引用的字节）。

目标是一个已存在的**集合** → **409**（`dav-destination-is-collection`），即使 `Overwrite: T`。清空一个集合只能走应用自己的文件夹删除：`folders` 没有 `deleted_at`，整棵子树连文件行一起消失，回收站里不留任何东西；而「清除目标」与「写入新目标」是两个事务，写入失败会留下目标已销毁、字节既无引用也未入队的中间状态。RFC 4918 没有规定这一情形，主流服务端一律答冲突。真的不要那棵树，就显式 `DELETE` 它。

目标是**文件**而源是**集合**（或反过来）→ 405：两种资源类型之间的替换没有定义，204 会销毁子树。

源与目标重合的四种形状一律 403（`dav-into-self`）：目标父路径在源集合之内、被占用的目标是源的祖先、以及镜像的文件情形。唯一例外是 `MOVE` 打在自己的 URL 上 —— 那是合法的无操作，答 204；`COPY` 打在自己的 URL 上不是（RFC 4918 §9.8.3），它会先把唯一一份字节扔进回收站再去读它。

### 三处对 RFC 的有意偏离

**`Overwrite: F` 撞上已存在的目标返回 204 而非 412。** RFC 4918 §10.3/§9.9.4 规定 412。选 204 是因为「跳过、不改动」正是客户端要的结果，而 412 在多数客户端上表现为一个需要用户重试的失败。`Overwrite` 头值无法识别时返 400，**绝不**静默当成「是，去销毁」。


**缺 `Depth` 头按 1 处理。** RFC 4918 §9.1 说缺 Depth 应视同 infinity。但本服务对 infinity 的回答是 403，于是「缺头 → infinity → 403」等于用拒绝来回应「只是想列个目录」的客户端 —— 而最常见的列目录方式恰恰是不带这个头。带 `Depth: infinity` 仍然返 403，并在响应体里给出 `propfind-finite-depth`，客户端据此退回逐集合 Depth 1。

**`PROPFIND` 打在非集合上且 `Depth: 1` 返 400**（RFC 4918 §9.1 规定 403）。400 是更诚实的回答：这个请求与资源自身的类型矛盾，而不是在问调用方没有权限的东西；而且遍历挂载的客户端对这两种状态码都无法恢复。

### 大目录

`PROPFIND` 必须返回**整个目录**（客户端要缓存完整列表），所以不能用既有的 `/api/folders/{id}/contents`：那个接口对文件夹完全没有游标，且在无 limit 时整集 `map { toFileDto() }` —— 10 万文件的目录会一次性物化 10 万个 DTO。WebDAV 侧有专用的 keyset 游标列举器，只读 6 个轻量列，按 `(lowerCase(name), id)` 分批取，207 响应边取边写（不整体缓冲）。

### 错误映射

命名冲突 409、超出大小上限 **507 Insufficient Storage**（响应体 `<D:error xmlns:D="DAV:"><D:quota-not-exceeded/></D:error>`，不是 413）、不可满足的 Range 416、条件请求失败 412、鉴权 401 + `WWW-Authenticate: Basic realm="BareZen Drive"`、方法不支持 405、infinity 403、只读挂载点写操作 403、限流 429。

限流走 `/dav` **自己的**预算，与登录额度彻底分开：挂载的每个请求都带凭据，密码过期的客户端重试循环是日常而非攻击，共用额度会让挂载把自己的属主锁在盘外（RFC 7617 附录专门警告过隐式重试的这个问题）。失败计数同理走独立的 `dav-auth` 桶：**每个客户端地址 5 分钟内 20 次失败凭据**，第 21 次直接 429（`RATE_LIMITED`），不再查库。桶只在凭据**错误**时消耗，验过的成功请求会把自己那一格退回，所以正常挂载永远碰不到这个上限；被掐的是地址，同一地址上连正确凭据也一并拒绝 —— 这是「让计数有意义」的代价，只针对失败的猜测，不针对流量。

### 未鉴权时的行为

未鉴权请求任何方法都返 401 并带 `WWW-Authenticate`，而不是 404 —— 否则客户端拿不到 challenge，无从完成认证。`/dav` 之外的任何路径都不会被 WebDAV 的挑战答复，也不会落到 SPA 兜底（那会让挂载客户端把一个 404 报成「服务器损坏」）。

## Web 静态托管

`GET /` 与任意非保留路径返回 Web 客户端（SPA 回退 index.html）；真实 wasm/js/css 资源在 `/` 下按名服务；`/api/**` 与 `/health` 优先于静态路由，未知 `/api/*` 子路径返回 404。

## 服务器管理（owner）

以下端点要求 Bearer 且属主为**实例 owner**（`settings.owner_id`，见「认证」一节）。非 owner 一律
403 `FORBIDDEN`；这些端点从鉴权块内取 `call.userId`，块外拿不到 principal。

### GET /api/server/stats（owner）

宿主机画像：CPU、内存总量/可用、磁盘总量/剩余、实时网速、uptime。取不到的项返回 `-1`，由客户端
显示为「—」。磁盘读数取 `STORAGE_DIR` 所在卷。

**owner-only 是本轮才加的**：此前它只要求「已登录」，于是任何注册用户都能读到宿主容量与运行时长，
而注册当时又默认开放——自助注册即可拿到。现在与「访客不得感知管理信息」的 OwnerGuard 模型一致。

### PUT /api/me/avatar（owner）

`multipart/form-data` 或原始字节均可，服务端解码后缩放到 512×512 JPEG 存盘。整段（读体 → 解码 →
缩放）包在 `Dispatchers.IO` 上：原来它在 event-loop 里同步跑，单请求堆峰值约 60MB（12MP 光栅
48MB + 4MB 原图 + 缓冲区扩容二次拷贝），并发线性叠加且占住 1C 的 event-loop。现在按
`Content-Length` 预分配缓冲、去掉二次拷贝，并用 `setSourceSubsampling` 抽样解码——**大图不再被拒**，
上限从「解码后 12MP」放宽为「输入 4MiB」。

**本轮顺带修一个从未工作过的端点**：它注册在 `authenticate("auth-jwt")` 块**外**却读
`call.userId`，Ktor 3.5 在块外拿不到 principal，于是请求恒为 401。已包进鉴权块（GET 仍公开，
分享页要免登录显示头像）。

## 更新代理

### GET /api/updates/download/{tag}/{file}（公开）

无认证地从 GitHub release 拉取资产并全量回传，`followRedirects(NORMAL)` 且透传 Range。

限流：`30 次 / 5 分钟`，按客户端地址，挂在 handler **第一行**（路径校验之前），所以非法路径不会
成为免费探测器。额度取得比分享正文路由小得多——这里被消耗的是带宽而不是数据库连接。

## S3 兼容接口

把网盘的一个顶层文件夹当成一个桶，服务端子集与 AWS S3 协议兼容到 rclone / restic /
mc / AWS CLI 能直接用。**同一个进程、同一个端口**，根路径 path-style：

```
https://<你的域名>/<bucket>/<key>
```

路由的判定顺序是：先 `/api/**`、再 `/dav/**`、再判定是否像一个 S3 请求（签名头或路径首段
是合法桶名），否则交回静态站点。`/health` 与既有的公开链接（分享页、静态托管的站点）
都不受影响。

### 凭证

复用 WebDAV 的应用密码，不新增一套账号：

| S3 参数 | 取自 |
|---|---|
| `accessKeyId` | WebDAV 令牌的 `id` |
| `secretAccessKey` | 创建令牌时返回的那一次明文 |

令牌明文只在创建时可见（库里存的是 SHA-256 精确匹配用的哈希，明文事后取不回），所以
`secretAccessKey` 无法事后取回。为了支持 S3 签名，令牌额外存了一份 **AES-256-GCM
可解密副本**（`secret_enc`，密钥由 `JWT_SECRET` 经 HKDF-SHA256 派生，AAD 绑定令牌 id，
所以一行密文不能挪用到另一行）。这副本只用于逐请求验签，不落日志、不回传。

**本版本之前签发的令牌没有这份副本**，用于 S3 会 403 `AccessDenied`（WebDAV 不受影响），
重新签发一个即可。

只读令牌（`readOnly=true`）对所有写方法返 403 `AccessDenied`：`PUT`/`DELETE`/`POST`、
CopyObject、分片的 Create/UploadPart/Complete/Abort、批量删除。

### 方法表

| 请求 | 行为 |
|---|---|
| `GET /` | ListBuckets：该用户的合法顶层文件夹（`Name` + `CreationDate` + `Owner`）。空列表也返 200 |
| `PUT /<bucket>` | CreateBucket：建顶层文件夹，200 + `Location`。已存在 → 409 `BucketAlreadyOwnedByYou`（rclone 视为成功）；同名是文件 → 409 `BucketAlreadyExists`。请求体里的 region 被忽略，恒为 `us-east-1` |
| `HEAD /<bucket>` | 200 + `x-amz-bucket-region: us-east-1`；不存在 404 |
| `DELETE /<bucket>` | 空桶 204。桶里还有任何后代文件夹、任何状态的文件（含回收站里的）或未完成的上传会话 → 409 `BucketNotEmpty` |
| `GET /<bucket>?location` | 空 `LocationConstraint`（等价 `us-east-1`） |
| `GET /<bucket>?versioning` | 空 `VersioningConfiguration`；`PUT ?versioning` → 501 |
| `GET /<bucket>?uploads` | 空 `ListMultipartUploadsResult`（客户端会探测） |
| `POST /<bucket>?delete` | DeleteObjects，见下 |
| `GET /<bucket>?list-type=2` | ListObjectsV2，见下 |
| `GET /<bucket>`（不带 `list-type`） | ListObjects V1，见下 |
| `GET /<bucket>/<key>` | 200 流式。`ETag: "<sha256>"`、`Last-Modified`（RFC 1123，取 `updated_at`）、`Content-Length`、`Content-Type`、`Accept-Ranges: bytes`、`x-amz-meta-*`。Range 走 `PartialContent`（206/416），条件头 `If-Match` / `If-None-Match` / `If-Modified-Since` / `If-Unmodified-Since` |
| `HEAD /<bucket>/<key>` | 同上但无 body；不存在 → 404 **空体**（S3 的 HEAD 不带错误 XML） |
| `PUT /<bucket>/<key>` | PutObject。200 + `ETag` + 回显校验和 |
| `PUT /<bucket>/<key>`（带 `x-amz-copy-source`） | CopyObject |
| `DELETE /<bucket>/<key>` | 硬删除。不管在不在都 204，指向文件夹的 key 也 204；随后剪掉变空的祖先目录（桶自己永不剪） |
| `POST /<bucket>/<key>?uploads` | CreateMultipartUpload → `InitiateMultipartUploadResult{UploadId}` |
| `PUT /<bucket>/<key>?partNumber=N&uploadId=…` | UploadPart → 200 + part 的 `ETag` |
| `POST /<bucket>/<key>?uploadId=…` | CompleteMultipartUpload → `CompleteMultipartUploadResult` |
| `DELETE /<bucket>/<key>?uploadId=…` | AbortMultipartUpload → 204；未知或已过期 → 404 `NoSuchUpload` |

### PutObject 的三种载荷

`x-amz-content-sha256` 决定怎么读 body：

1. **64 位 hex**：客户端预先知道全文哈希。服务端边收边算，先比对它，再比对
   `x-amz-checksum-*` 与 `Content-MD5`。哈希与 blob 键相同，所以能秒传（服务端已有该
   blob 时不重传字节）。**覆盖已有对象时不走秒传**——那时校验和必须在合并阶段、
   publish 之前核对，否则一个校验失败的请求会先把旧内容换掉。
2. **`UNSIGNED-PAYLOAD`**：不核对载荷哈希，其余校验和照旧。
3. **`STREAMING-AWS4-HMAC-SHA256-PAYLOAD`**（aws-chunked 分帧）：真实长度取
   `x-amz-decoded-content-length`，`Content-Length` 是分帧后的长度。每块尾部的
   `chunk-signature` 逐个核对；`x-amz-trailer` 声明的校验和在读完 body 之后核对并回显。
   `STREAMING-UNSIGNED-PAYLOAD-TRAILER` 也支持（不验块签名，只解帧）。

任何签名、长度、校验和不符都在 **publish 之前**失败，返 400
`XAmzContentSHA256Mismatch`（载荷哈希不符）或 `BadDigest`（其它校验和不符），不会留下
半个对象。`STREAMING-AWS4-ECDSA-PAYLOAD-*`（SigV4A）返 501。

### ListObjectsV2 与 V1

两条走同一套遍历，差别只在分页参数与响应字段。rclone 在 `provider = Other` 时按
auto 发 **V1**（`GET /<bucket>`，无 `list-type`），所以两条都必须实现。

参数：`prefix`、`delimiter`（只接受 `""` 和 `"/"`，其它值 501）、`max-keys`（默认
1000，>1000 截断到 1000，0 合法并返回空页，负数 400）、`encoding-type=url`，外加：

- V2：`continuation-token`（不透明游标，优先）、`start-after`
- V1：`marker`（按名字位置跳过，可以是一个不存在的名字）

响应含 `Name`、`Prefix`、`MaxKeys`、`IsTruncated`、`Delimiter`、
`Contents{Key, LastModified, ETag, Size, StorageClass=STANDARD}`、
`CommonPrefixes{Prefix}`，外加各自的版本字段：

- V2：`KeyCount`（`Contents` 与 `CommonPrefixes` 之和）、`ContinuationToken`、
  `NextContinuationToken`、`StartAfter`
- V1：`Marker`（回显请求值）、`NextMarker`（截断时给出，值是本页最后一个键或折叠前缀）

`list-type` 写了但不是 `2` → 400 `InvalidArgument`；不写即 V1。

排序用的是 App 自己的折叠名序，不是 S3 的字节序 —— 见「已知差异」。

### CopyObject

`x-amz-copy-source: /<srcBucket>/<srcKey>`，允许 URL 编码，允许同用户跨桶。

- 源必须是文件。源 key 指向文件夹（只是个前缀）→ 404 `NoSuchKey`。
- 目标已存在 → 覆盖（对象存储语义，不产生版本行）；目标同名是文件夹 → 409 `InvalidRequest`。
- 一次事务内完成「删旧 + 插新」，提交后才把孤儿键交给删除队列，所以目标不会被删一半。
- `x-amz-metadata-directive: COPY | REPLACE`（默认 COPY）。其它值 400 `InvalidArgument`。
- 源与目标同路径且 `COPY` → 400 `InvalidRequest`；`REPLACE` 允许，用于原地改元数据。
- 条件头 `x-amz-copy-source-if-{match,none-match,modified-since,unmodified-since}`，
  不满足 → 412 `PreconditionFailed`。
- 带 `?versionId` 的复制源 → 501（不支持版本控制）。
- 不递归：目录复制请客户端逐个文件调用。

### DeleteObjects

`POST /<bucket>?delete`：

- **必须带 `Content-MD5`**，且必须等于 body 的 MD5。缺失 → 400 `InvalidRequest`，
  不符 → 400 `InvalidDigest`。两者都在任何删除发生之前判定。
- body ≤ 1 MiB，XML 用与 WebDAV 同款硬化解析（禁 DOCTYPE、不展开外部实体），解析失败
  一律 400 `MalformedXML`。
- 一次最多 1000 个 `Object`，超过 → 400 `MalformedXML`。
- 每个 key：文件 → 硬删并计入孤儿键；不存在、或解析到文件夹 → 当作已删除（S3 的幂等
  语义）。响应把**每个被请求的键**都回成 `<Deleted><Key>`，`Quiet=true` 时只回错误项。
- 删除后剪掉变空的祖先目录；一次请求产生的孤儿键合并后一次性交给删除队列。

### 分片上传

| 步骤 | 行为 |
|---|---|
| Create | 校验桶与 key，先建隐式父目录，返回 `UploadId`。24 小时有效 |
| UploadPart | `partNumber` ∈ [1, 10000]；单片 ≤ 5 GiB，且累计 ≤ `MAX_FILE_SIZE`；边写边算 SHA-256 与请求带的校验和，核对通过才把暂存文件原子改名落账；每个 UploadPart 都把有效期续到 `now + 24h` |
| Complete | part 必须严格升序且无重复（`InvalidPartOrder`）、每个 ETag 与服务端记录一致（`InvalidPart`）、除最后一片外每片 ≥ 5 MiB（`EntityTooSmall`）、总量 ≤ `MAX_FILE_SIZE`。然后合并、核对整文件哈希、发布。未列出的 part 直接丢弃 |
| Abort | 删会话与暂存文件，204；未知或已过期 → 404 `NoSuchUpload` |

**ETag 语义与 AWS 不同**：S3 的分片 ETag 是 `"md5-of-md5s-N"`，我们返回**整文件的
sha256**，与单发 PUT 一致。这样 `rclone check` 有可比较的内容哈希，代价是任何依赖
`-N` 后缀来识别分片上传的工具会认不出来。

`x-amz-checksum-*` 在 Complete 上按 S3 的复合规则核对（各 part 的二进制摘要串起来再取
一次摘要，base64 后加 `-N`）；只要有任一 part 没记录该算法，这个头就被忽略而不是报错。

`ListParts`（`GET ?uploadId=`）返 501。

### 错误模型

错误 body 是 XML，不走 `/api` 的 JSON 信封：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Error><Code>NoSuchKey</Code><Message>…</Message>
<Resource>/bucket/key</Resource><RequestId>…</RequestId></Error>
```

每个 S3 响应（成功也是）都带 `x-amz-request-id`。S3 路由内部自捕获所有异常并渲染成
S3 形状，绝不会漏到 `/api` 的错误处理里去。`Message` 严格 XML 转义——键名是用户输入。

| 场景 | HTTP | Code |
|---|---|---|
| 未签名 / 只读令牌写 / 旧令牌无解密副本 | 403 | `AccessDenied` |
| accessKeyId 不存在 | 403 | `InvalidAccessKeyId` |
| 签名不符 | 403 | `SignatureDoesNotMatch` |
| 时钟偏移超 15 分钟 / 预签名过期 | 403 | `RequestTimeTooSkewed` / `RequestExpired` |
| 桶名非法 / 键非法 / 元数据超限 / 复合校验和不符 | 400 | `InvalidBucketName` / `InvalidArgument` / `MetadataTooLarge` / `BadDigest` |
| 载荷哈希不符 | 400 | `XAmzContentSHA256Mismatch` |
| `Content-MD5` 缺失或不符（DeleteObjects） | 400 | `InvalidRequest` / `InvalidDigest` |
| 缺 Content-Length / 超过 `MAX_FILE_SIZE` | 411 / 400 | `MissingContentLength` / `EntityTooLarge` |
| XML 请求体不合法 | 400 | `MalformedXML` |
| 分片太小 / 顺序不对 / part 不存在 | 400 | `EntityTooSmall` / `InvalidPartOrder` / `InvalidPart` |
| 桶 / 对象 / 上传会话不存在 | 404 | `NoSuchBucket` / `NoSuchKey` / `NoSuchUpload` |
| 桶非空 | 409 | `BucketNotEmpty` |
| 对象与文件夹同名 | 409 | `InvalidRequest` |
| 条件头不成立 | 412 | `PreconditionFailed` |
| 并发同名写（唯一索引冲突） | 503 | `SlowDown`（可重试语义） |
| 限流 | 503 | `SlowDown` |
| 不支持的方法 / 子资源 | 501 | `NotImplemented` |
| 其它 | 500 | `InternalError` |

### 限流与预签名

按客户端地址计两个互相独立的预算：鉴权 `s3-auth` 20 次 / 5 分钟（挡住拿签名当探测器），
业务 `s3` 600 次 / 分钟。超了返 503 `SlowDown`。

预签名 URL 由客户端自己签（SigV4 query 形式），服务端只验不签：`X-Amz-Expires` ∈
[1, 604800]（超出 → 403 `AuthorizationQueryParametersError`），签名时间在未来 → 403
`RequestTimeTooSkewed`，超过 `X-Amz-Date + Expires` → 403 `RequestExpired`。

访问日志里 `X-Amz-Signature` 的值被替换成 `***`——预签名 URL 整串都在查询行里，
默认格式化器会把真签名写进日志。

### 501 清单

`?acl`、`?policy`、`?tagging`、`?cors`、`?lifecycle`、`?website`、`?logging`、
`?notification`、`?replication`、`?encryption`、`?object-lock`、`?legal-hold`、
`?retention`、`?restore`、`?select`、`?torrent`、`?attributes`、`?versionId`、
`PUT ?versioning`、`ListParts`、SigV4A → 501 `NotImplemented`。

刻意用 501 而不是 404：让客户端知道「服务端不支持这件事」，而不是「这个资源不存在」，
后者会让客户端把 404 当缓存未命中继续往下走。

### 已知差异

见 [usage.md 的「当作 S3 对象存储用」](usage.md#九当作-s3-对象存储用rclone--restic--mc--aws-cli)
一节末尾的诚实清单：只有合法桶名的顶层文件夹可见、DELETE 不进回收站、列举顺序是折叠
名序、分片 ETag 是整文件 sha256、明文 HTTP 下签名可被旁路读取。
