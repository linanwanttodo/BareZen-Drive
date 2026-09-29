# audit-03 安全审查（server/src/main/kotlin + app/shared Web token 存储）

审查范围（只读，未修改任何文件）：

- `server/src/main/kotlin` 全部 45 个 Kotlin 文件（路由、OwnerGuard、Throttle、auth、files、storage、plugins、system、jobs、db、config）
- `app/shared` Web（wasmJs）客户端 token 存储与相关打开/渲染路径
- 辅助证据：`server/src/test`（ServerStatsTest / RateLimitTest / ShareLinkTest）、`core` DTO

条目统计：严重 1 条、次要 5 条、建议 6 条，合计 **12 条**；另附 6 条 ✅ 检查结论。

---

## 严重

- [严重] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/files/FileContent.kt:179-193（配合 files/ShareService.kt 对应的 ShareRoutes.kt:81-90、files/UploadService.kt:133/166、core/Dto.kt:28）→ 文件 Content-Type 完全由客户端声明并原样内联回放：`mimeType` 采信 `UploadInitRequest.mimeType` 入库（UploadService.kt:133 落文件行、:166 落会话行），下载/分享直链用 `ContentType.parse(meta.mime)` 直接 `respond(FileStream(...))`，全仓 grep 无 `Content-Disposition: attachment`、无 `X-Content-Type-Options: nosniffer/nosniff`、无 CSP（Application.kt:104-145 只装了 ContentNegotiation/CallLogging/PartialContent/StatusPages/Authentication）→ 攻击面：注册默认开放（见次要第 6 条），攻击者注册后上传 `mimeType="text/html"`（或 `image/svg+xml`、`application/xhtml+xml`）含脚本的文件，用自己账号调 `GET /api/files/{id}/link`（FileContent.kt:84-91，设计用途就是「浏览器标签页/播放器」，且 App 自身在新标签打开该 URL：app/shared/src/wasmJsMain/.../PlatformPlayers.wasm.kt:56/81）或直接发送公开分享直链 `/api/public/shares/{token}/files/{fid}/content`，诱导受害者顶层导航 → 脚本在网盘同源执行 → 读取 localStorage 中的 `bz_access_token`/`bz_refresh_token`（TokenStorage.wasm.kt:6-26）→ 影响：存储型 XSS，任意打开链接的用户（含实例 owner）会话令牌被窃、账号接管 → 建议修法：① 对非白名单类型（仅 image/*、video/*、application/pdf 等）一律回 `application/octet-stream` 并强制 `Content-Disposition: attachment; filename*=UTF-8''...`；② 全局安装 `X-Content-Type-Options: nosniff` 与严格 CSP（`default-src 'self'`）；③ 上传时按扩展名/魔数在服务端重算可信 Content-Type，不采信客户端字符串 → 改动量(M)

---

## 次要

- [次要] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/system/SystemRoutes.kt:26-30（挂载点 Application.kt:173）→ `/api/server/stats` 只在 `authenticate("auth-jwt")` 块内、缺 `requireOwner(call.userId)`，返回宿主机 CPU/内存/磁盘总量与剩余/网速/uptime（SystemStatsService.kt:22-42 直读 /proc）→ 任意注册用户可读主机画像；而注册默认开启（ServerSettingsService.kt:22，注册开关本身才是 requireOwner 的），攻击者自助注册即可拿到 → 影响：越权信息泄露（主机容量、运行时长、负载指纹），与本实例「访客不得感知管理信息」的 OwnerGuard 模型不一致（ServerStatsTest.kt:29-34 仅断言"要登录"，未断言"仅 owner"）→ 建议修法：路由内首行 `requireOwner(call.userId)`，或对非 owner 裁剪字段 → 改动量(S)

- [次要] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/api/Throttle.kt:123-131（挂载点 auth/AuthRoutes.kt:38/51/68）→ `TRUST_PROXY=true` 时 `clientIp()` 采信 `X-Forwarded-For` 最右非空项，却不校验请求对端是否为受信代理；只要进程端口可被直连（Docker 端口映射、旁路可达），伪造 `X-Forwarded-For: <随机IP>` 每请求换一个键即可 → 影响：`reg`(5/5min)、`login`(10/min/IP)、`refresh`(30/min) 三个限流域整体绕过，在线暴力破解/注册洪水零成本；同时可灌满/操控 50k 键上限的桶（Throttle.kt:80-89）→ 建议修法：仅当 `remoteHost ∈ 配置的受信代理网段` 才解析 XFF（多级时取最右「非受信段」），否则回退 socket 地址；或强制服务只监听回环并由代理剥除入站 XFF → 改动量(S/M)

- [次要] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/auth/AuthRoutes.kt:60-64（配合 api/Throttle.kt:57-69）→ 账户级锁定是 `peek()` 先读、失败后 `record()` 后写的非原子「检查-再扣」：并发发起的 N 个失败登录都能在计数落库前通过 `peek >= 20` 判定 → 影响：配合多源 IP（绕开 IP 轴 10/min），一次并发爆发可显著突破 20 次/15min 的单账户猜测上限，锁在突发段失效 → 建议修法：改成原子扣减——`Throttle.allow("login-account:…", 20, 900_000)` 先占额度再验密（失败不退、成功不计可再包一层），或用 DB/原子计数器 → 改动量(S)

- [次要] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/files/ShareRoutes.kt:49-115 与 system/SystemRoutes.kt:77-141（全仓 Throttle 挂载仅 AuthRoutes.kt:38/51/68；ShareService.kt:171 的 `firstSince` 只做浏览数去重、不是访问限流）→ 两条高成本公开路由完全无限流：① `/api/public/shares/{token}/…` 每次请求都做一次 tokenHash DB 查询（ShareService.kt:205-214）；② `/api/updates/download/{tag}/{file}` 无认证、无限流地替调用者从 github.com 拉取资产并全量回传（SystemRoutes.kt:94-135，`followRedirects(NORMAL)` 且透传 Range）→ 影响：匿名 DB 查询洪水与带宽放大（可反复拉 GB 级 release 文件），分享 token 试错也无次数限制（256-bit 猜解本身不可行，但查询洪水可行）→ 建议修法：为 public shares 与 updates/download 各挂 `Throttle.allow("…:"+clientIp, N, window)`，代理侧加并发上限与总出网配额 → 改动量(S/M)

- [次要] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/system/ServerSettingsService.kt:22 + system/OwnerGuard.kt:19-24 + auth/AuthService.kt:81-89 → 注册默认开放（`registrationOpen()` 缺省 true），而 owner 判定为「最早 createdAt、同毫秒按 id 升序」的首个账号；新装实例若未设置 `BOOTSTRAP_ADMIN_*`/安装向导，公网抢先注册者直接成为实例 owner（可列全部用户、删除任意账号、关闭注册）；同毫秒并发注册时由随机 UUID 兜底决定归属 → 影响：新装暴露实例被抢占接管（依赖部署时序，故列次要）→ 建议修法：出厂默认 `registration_open=false`，强制走安装向导/环境变量创建首个账号；首用户创建时用原子 CAS 把 owner id 固化进 settings 表，`requireOwner` 优先读固化值 → 改动量(M)

---

## 建议

- [建议] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/auth/JwtService.kt:10,18-21（配合 system/AdminRoutes.kt:121、auth/AuthRoutes.kt:33-90）→ access token 15 分钟 TTL、HS256、无 jti/黑名单，服务端没有 logout 路由；删号只删 refresh_tokens，不触碰已签发 JWT → 影响：登出/封禁/删号后 access token 最长 15 分钟内仍有效（多数接口按 user 过滤、`/api/me` 会校验行存在，实际影响被压到「窗口期残留写权限」）→ 建议修法：登出/删号时写入 jti 吊销表（Authentication 里校验），或缩短 TTL + 敏感写操作复核用户存在 → 改动量(M)

- [建议] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/auth/AuthService.kt:150-197 → refresh 轮换为一次性 + 120s 宽限（`REFRESH_REUSE_GRACE_MS`）+ `graceReplays` CAS 上界 1，实现正确；但「过宽限期重放」的复用检测命中时只拒绝该 token 本身，不吊销同用户 token 家族 → 影响：检测到被盗用后无法止损，攻击者持当前有效 token 的链路继续 30 天滚动 → 建议修法：复用检测命中即撤销该用户全部 refresh token 并强制重新登录（token family revocation）→ 改动量(S)

- [建议] 【③安全】app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/data/local/TokenStorage.wasm.kt:6-26 → access/refresh token 明文存 localStorage（`bz_access_token`/`bz_refresh_token`），同源任何脚本可读；服务端又无 CSP（见严重条目），`clear()`/logout 只清 token、保留 `bz_base_url`（本身合理，但 baseUrl 同样可被同源 JS 改写为钓鱼地址）→ 影响：一旦出现任何注入即整套会话令牌失窃，XSS 被放大为完整账号接管 → 建议修法：叠加 CSP 作为纵深；或将会话改由 HttpOnly+SameSite Cookie 承载、access token 只留内存，refresh 仅在需要时落盘；至少加登录设备/token 轮换可见性 → 改动量(M/L)

- [建议] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/auth/LinkService.kt:23-37（签发口 files/FileContent.kt:84-91、消费口 files/FileContent.kt:212-217）→ 签名直链只绑定 `content:fileId:exp`，TTL 可到 3600s，无任何撤销表：文件移入回收站会因 `getFileMetaUnscoped` 过滤 `deletedAt` 而 404（FileService.kt:176-179，止损存在），但「活文件」已发出的签名 URL 在到期前无法提前作废，泄露后最长 1 小时持续可读 → 影响：签名 URL 无提前撤销能力，且签名未绑定 user/资源版本 → 建议修法：签入 `file.updatedAt`（改名/移动/重传即失效）或引入签名 jti 撤销表 + revoke 端点；默认 TTL 进一步压缩 → 改动量(M)

- [建议] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/api/Throttle.kt:80-89（配合 auth/AuthRoutes.kt:60 的锁定判据）→ 所有桶共享 `MAX_KEYS=50_000` 的「最旧 windowStart 淘汰」策略，而 `login-account:<用户名>` 的键由攻击者任意铸造； >50k 个不同用户名的失败登录即可在下一次 prune 把目标账户最早写入的锁定桶挤出 map → 影响：账户锁定计数可被键位洪泛清零（需 5 万次 bcrypt 级请求，成本高，故列建议）→ 建议修法：锁定计数迁到独立/持久化结构并对「已锁定」键做保护（独立上限与淘汰策略）→ 改动量(S/M)

- [建议] 【③安全】server/src/main/kotlin/com/linan/barezen_drive/files/UploadService.kt:88-147 → 秒传路径只看 `storage.exists(blobKey(sha))`，不区分 blob 归属：请求者给出任意内容的 sha256，即可从响应 `instantUpload=true`（:145）判断「库内任意用户」是否上传过该内容 → 影响：跨用户内容存在性 oracle（对已知哈希的目标文件做「这份资料是否已被某人上传到该网盘」的隐私侧信道）→ 建议修法：秒传仅当同用户已引用该 blob 时启用，跨用户命中降级为普通分块上传 → 改动量(S)

### ✅ 检查结论（本轮未发现问题的检查项）

- ✅ 越权（IDOR）：逐路由核对全部 30+ 条路由——folders(contents/create/rename/delete)、files(patch/delete/favorite/archive/link/content/thumbnail PUT+GET/search/recent/album)、trash(4)、versions(3)、uploads(init/chunk/complete/abort)、shares(owner 3 + public 4)、admin/users(2)、settings PATCH、avatar、me——均经 `FileService.getFileMeta/fileRowOf/contents` 的 `(id) and (user eq userId)`、`openSession` 的 `(id) and (user)` 或 `requireOwner` 收口（唯一例外 `/api/server/stats` 见次要第 2 条；`/api/users/{id}/avatar` 为按设计公开）。
- ✅ 路径穿越：逐点核对 LocalStorageProvider.resolvePath（normalize+startsWith root，LocalStorageProvider.kt:65-69）、blobKey/thumbKey（sha 由 `^[0-9a-f]{64}$` 校验或服务端计算，UploadService.kt:85）、分块 staging 目录（sessionId 为 UUID，UploadService.kt:218-224）、AvatarRoutes avatarKey（UUID，AvatarRoutes.kt:24）、StaticWeb 逐段解码拦截 `.`/`..`/分隔符/冒号/`\0`（StaticWeb.kt:58-64）、更新代理 tag/file 白名单正则（SystemRoutes.kt:81-88）、BlobPurge thumbKey（文件名→sha，BlobPurge.kt:35）、文件名仅入库不落盘（FileService.nameOk 拒 `/`），未发现可利用点。
- ✅ 上传会话归属：init 续传匹配强制 `user eq userId`（UploadService.kt:154-159），putChunk/complete/abort 全走 `openSession(id, user)`（UploadService.kt:60-72），别人的 session id 请求一律 404，未发现跨用户续传/完成。
- ✅ 分享链接猜解/枚举/过期边界：token 为 32 字节 SecureRandom（ShareService.kt:48-52）、仅存 SHA-256、`TOKEN_RE` 先行拒绝非 64 位十六进制（ShareRoutes.kt:20,111-114）、未知/吊销/过期统一 404 无状态预言（ShareService.kt:205-214）、过期判定 `exp <= now` 严格、revoke 即删行、文件入回收站即时 revokedAt（FileService.kt:234-235）、folder share 有 `insideSubtree` 服务端边界（ShareService.kt:244-288），未发现可利用点（限流缺失另见次要第 5 条）。
- ✅ 文件名回显 / HTML 拼接注入面：Web 客户端为 Compose Multiplatform（wasm），全仓 grep 无 `innerHTML`/`insertAdjacentHTML`/`document.write`/`outerHTML`，也无 `<html>/<script/<div` 字符串拼接；文件名只经 Compose `Text` 渲染，服务端搜索 LIKE 已转义 `% _ \`（FileContent.kt:41-48）、原始 SQL 仅为静态语句（AdminRoutes.kt:50），未发现注入点。
- ✅ 限流实现本体与 JWT/refresh 基础面：`Throttle.allow` 桶内 synchronized 计数原子、无并发绕过；JWT 使用 HS256、`JWT_SECRET` 强制 ≥32 字节（AppConfig.kt:51）、15 分钟过期、`verifier` 校验 issuer+exp；refresh 32 字节随机、SHA-256 存储、一次性轮换 + 条件 revoke + 宽限重放上界（AuthService.kt:166-197）；CallLogging 对 `sig=` 打码（Application.kt:109-115）。剩余缺口（XFF 伪造、peek/record 竞态、键位淘汰、无吊销、无 family revocation）已分别列为次要第 3/4 条与建议第 1/2/5 条。
