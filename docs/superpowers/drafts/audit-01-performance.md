# 性能审查清单（server/src/main/kotlin 路由与服务层）

只读审查范围：`server/src/main/kotlin` 全部 Route/Service/存储/作业代码，索引对照 `db/Tables.kt` 的 9 张表（users、refresh_tokens、folders、files、upload_sessions、upload_chunks、file_versions、share_links、settings）。
目标环境：1C/1G 小服务器（Hikari `maximumPoolSize=5`，Netty 默认 event-loop 线程 ≈ 2×核）。
四条已知债务核验结论：孤儿 blob 竞态【部分解决】见 22、MAX_FILE_SIZE 未强制【已解决】见 18、并行上传进度单槽位【客户端仍存在，服务端另有隐患】见 17/30、缩略图 PUT 幂等跳过【仍存在】见 16。

## 严重

- [严重] 【①性能】auth/AuthService.kt:115 → `login()` 的 bcrypt verify（cost=10，约 100ms/次，含 :124 的 dummy 等时校验）与 `register()` 的 hash（:86）都写在 `transaction{}` 内执行 → 每次登录/注册独占一条 Hikari 连接 100–300ms，而池上限固定 5（db/DatabaseFactory.kt:35）；撞库或登录高峰时 5 条连接全被 KDF 占住，其他请求（上传、列目录）在 Hikari 默认 30s connectionTimeout 内排队，1C/1G 机上表现为全站卡死 → KDF 移出事务：短事务内只查用户行并取出 hash → 事务外跑 `PasswordHasher.verify/hash` → 再开事务写 refresh token / 插用户行 → 改动量(M)

- [严重] 【①性能】files/ShareRoutes.kt:52 → 公开分享 4 个端点的 `resolve(call)`（→ ShareService.kt:206 resolveToken 的阻塞 JDBC 事务）在路由协程原地执行，未切 `Dispatchers.IO`；同类还有 `requireOwner`（system/AdminRoutes.kt:41、:73 与 system/SystemRoutes.kt:48，均在 withContext 之外）以及 `deleteStoredBlobs` 的事务+存储 IO（files/TrashRoutes.kt:35、:40，files/FolderRoutes.kt:33，system/AdminRoutes.kt:134）也跑在 event-loop 上 → 无鉴权热路径每个 Range/缩略图请求都会阻塞 Netty event-loop；连接池耗尽时 event-loop 线程可被 Hikari 卡最长 30s，1C 机器 worker 本就只有 1–2 条 → 单点抖动放大为全站请求停摆 → 所有阻塞点统一包 `withContext(Dispatchers.IO)`；单请求的 resolve+sharedInfo+recordView 串行多事务合并为一个事务 → 改动量(M)

- [严重] 【①性能】files/FileService.kt:330 → `orphanBlobKeys` 对每个候选 key 串行发 2 条 count（files + file_versions），且整条删除链路要跑两遍：hardDelete 内一次（:350），事务提交后 `deleteStoredBlobs` 再来一次（files/BlobPurge.kt:31） → emptyTrash / 删文件夹 / 删用户涉及 1000 个 blob 时约 4000 次串行 round trip，长时间占满仅有的 5 条连接，删除接口延迟随 key 数线性放大并饿死在线请求 → 每张表改为一条 `WHERE storage_key IN (...)` 的 GROUP BY/HAVING 聚合；两遍判定合并为一遍（purge 侧只做轻量复核或直接复用 hardDelete 的结论） → 改动量(M)

- [严重] 【①性能】db/Tables.kt:81 → files（全站最大表）与 file_versions（:135）都没有 `storage_key` 索引：`orphanBlobKeys` 按 storage_key 做 count，退化为**每个 key 一次 files 全表扫** → 与上一条叠加 = keys 数 × 全表扫 × 2 轮；回收站批量清理、删用户、每 6h 的清理循环都会周期性把数据库 CPU 打满 → 给 `files(storage_key)`、`file_versions(storage_key)` 建索引；或把判定改写成按 sha256 校验（blobKey 尾段即 sha）以复用已有 `files_sha256_idx` → 改动量(S)

- [严重] 【①性能】files/ThumbnailService.kt:212 → ImageIO 兜底解码整幅无下采样：bounds 预检（:207）放行最高 64MP 源图（:49）后 `ImageIO.read` 仍解码完整光栅（64MP×3~4B ≈ 192–256MB/张），`genPool` 固定 2 线程可并发 2 张（:67），源文件上限还允许 100MB 图片（:44） → 1C/1G 主机 JVM 默认堆约 256MB，一张大图即 OOM 或长时间 Full GC，同进程内的上传、列表、下载一起抖动 → 用 `ImageReadParam.setSourceSubsampling` 按目标边长先降采样解码；或把 ImageIO 路径像素上限降到 ≤16MP（超限交 ffmpeg / 直接放弃），并在启动参数显式设 `-Xmx` → 改动量(M)

- [严重] 【①性能】Application.kt:104 → 只安装了 ContentNegotiation/CallLogging/PartialContent 等，**没有全局请求体上限**；所有 `call.receive<T>()`（auth/AuthRoutes.kt:47、:54、:71，files/FolderRoutes.kt:20、:24、:42、:54、:59，files/ShareRoutes.kt:31，files/UploadRoutes.kt:19，system/SystemRoutes.kt:49）都先把整个 JSON body 读进堆再反序列化 → 注册接口的 5 次/5min 限流不体挡单请求体积极限：未鉴权 POST /api/auth/register 发几百 MB body 即可把 1G 主机 OOM；写端点也无兜底 → 安装 ContentLengthLimit/自定义 BodyLimit（JSON 端点 64KB–1MB，超限 413），与已有 readBounded（api/BoundedRead.kt）口径对齐 → 改动量(S)

## 次要

- [次要] 【①性能】files/ShareService.kt:292 → `insideSubtree` 沿父链每层一次 SELECT（guard 上限 1000），`sharedContents`/`sharedFileMeta` 每次请求都走一遍；且同一请求还要串 resolveToken、sharedInfo、recordView 多个独立事务 → 公开分享热路径 O(深度) 次查询 × 多事务；媒体播放器每个 Range 请求都完整重跑 resolve+meta+子树校验 → DB round trip 成倍于必要值 → 子树校验改反向递归 CTE 一次求解（或按 shareId 缓存子树集合，TTL 数分钟）；单请求多事务合并 → 改动量(S)

- [次要] 【①性能】files/ShareService.kt:123 → `listShares` 把用户名下**全部** share 行读进内存再 `.filter` 掉 revokedAt/expiresAt，fileId/folderId 还按字符串比较（:128–129）；share_links.user_id 无索引（db/Tables.kt:142–160） → 谓词无法下推、`share_target_idx` 用不上，分享数增长后管理页每次打开都是用户级全扫 + 内存排序 → 谓词写进 WHERE（`revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?) AND file_id = ?`），补 `(user_id)` 索引 → 改动量(S)

- [次要] 【①性能】files/FileContent.kt:67 → recent 按 `updated_at` 排序、album（:134–155）按 `COALESCE(taken_at, updated_at)` 排序，而 files 只有 (user,folder,name,deleted_at)、(sha256)、(user,deleted_at) 三个索引（db/Tables.kt:81–85），排序列完全未覆盖 → 首页"最近"与相册每翻一页都要取出该用户全部存活行做全量排序（5 万张照片库 = 每页 5 万行排序），相册滚动时逐页重复全量排序，1C 上直接推高 CPU → recent 补 `(user_id, deleted_at, updated_at)` 部分索引；album 落 generated column `sort_ts` 并建表达式索引（或至少退化为按 updated_at 索引近似） → 改动量(M)

- [次要] 【①性能】db/Tables.kt:81 → `files.folder_id` 没有独立索引（现有四元索引以 user_id 打头）：ShareService.kt:263 公开列目录只按 folder_id 过滤、FileService.kt:151 删文件夹按 `folder IN 子树` 过滤，都用不上索引 → 每次公开文件夹分享列目录、每次删文件夹都全表扫 files（最大表）；folders.parent 的无 user 版查询（ShareService.kt:257）、upload_sessions.folder（db/Tables.kt:92，无任何索引）同样全扫 → 补 `files(folder_id)`（可含 deleted_at 或做部分索引）、`upload_sessions(folder_id)`、`folders(parent_id)` → 改动量(S)

- [次要] 【①性能】files/FileService.kt:163 → 删子树对每个文件夹逐条 `deleteWhere { id eq fid }` 循环；system/AdminRoutes.kt:100–103 对每个 session 又是 2 条 DELETE 循环，且 :91 对每个根目录重复走一遍 deleteFolder → 深树/多会话删除产生 O(N) 次 round trip，且全程占住一条池连接（池仅 5 条） → 按 BFS 层级批量 `WHERE id IN (...)` 删除（父层后删以满足自引用 FK），session/chunk 两条 inList 批删一次完成 → 改动量(M)

- [次要] 【①性能】db/DatabaseFactory.kt:35 → `maximumPoolSize = 5` 硬编码、连接超时用默认 30s、未开 leak detection；Netty（Application.kt:75）未配 worker 数，默认 2×核（1C ≈ 1–2 条 event-loop） → 池被长事务/KDF 占满时所有 IO 协程一起等 30s；与上面两处 event-loop 阻塞叠加，单点抖动放大成全站排队；线程池容量与连接池容量不匹配（Dispatchers.IO 默认 64 线程抢 5 条连接） → 池大小/超时/泄漏检测改为可配置（1C1G 建议 5–8 + 连接获取失败快速返回），Netty eventLoop 按核数显式配置并在启动日志打印 → 改动量(S)

- [次要] 【①性能】system/OwnerGuard.kt:19 → `ownerId()` 每次调用现查 users 表按 createdAt 排序 limit1（createdAt 无索引），且三个调用点（system/AdminRoutes.kt:41、:73，system/SystemRoutes.kt:48）在 `withContext(IO)` 之外执行 → 每个管理/设置请求多 1 条无索引排序查询，并在 event-loop 上阻塞等连接 → 进程内缓存 ownerId（users 表只增不换序），调用点移入 Dispatchers.IO → 改动量(S)

- [次要] 【①性能】system/AvatarRoutes.kt:50 → 头像 PUT 在 event-loop 上同步跑 `scaleTo512`：ImageIO 解码上限 12MP（:60，≈48MB 光栅）+ 4MB 原图 + `readBounded` 的 ByteArrayOutputStream 翻倍扩容后 `toByteArray()` 再拷贝一份（api/BoundedRead.kt:16–32） → 单请求堆峰值约 60MB、并发线性叠加；CPU 解码占住 1C 的 event-loop，同时刻所有请求延迟抖动 → 整段包 `withContext(Dispatchers.IO)`；readBounded 用预分配容量、无二次拷贝的输出；解码改 subsampling → 改动量(S)

- [次要] 【①性能】files/UploadService.kt:302 → complete 先把整文件 merge 落盘（读全部分块 + 全量写一遍），再经 put() 把 merge 文件流全量读一遍拷成最终 blob（storage/LocalStorageProvider.kt:31–44 又一次全量读写） → 写放大约 3×、读 2×，单次 complete 峰值磁盘 ≈3× 文件大小（roadmap.md:42 已列"合并边收边写"）；1C 机器上大文件 merge+copy 长时间占满 IO 线程与磁盘带宽，并发 complete 时翻倍 → local 后端 merge 完成后直接 `Files.move` 到 blobKey 目标（同卷原子改名，省掉整遍拷贝）；彻底方案按 roadmap 边收边写 → 改动量(M)

- [次要] 【①性能】files/ThumbnailRoutes.kt:37 → 【债务"缩略图 PUT 幂等跳过"：**仍存在**】路由先把最多 512KB 整个读进堆（readBounded）再调 put()，而 storage/LocalStorageProvider.kt:27 与 storage/S3StorageProvider.kt:140 对已存在的键直接 `discard()` 跳过 → 客户端每次重传封面都白传 512KB（上传带宽 + 堆分配 + 一次完整请求），且同一 sha256 的坏封面永远无法被客户端覆盖（roadmap.md:65 仍列，服务端异步生成的封面也会锁死客户端的更优封面） → 读体前先 `exists()` 短路返回；thumbs/avatars 这类键允许覆盖写（或比对 ETag 后 REPLACE），并提供重新生成入口 → 改动量(S)

- [次要] 【①性能】files/UploadService.kt:154 → 续传匹配只按 (user, folder, name, size, open) 取 `firstOrNull`：同名同尺寸的两个**并行**上传会被折叠进同一会话，两边按相同 chunk index 写同一个 `$i.part` 槽位（后写覆盖先写） → 并行上传同名文件时进度互相覆盖、合并结果可能是两个文件的混拼（整体 sha 校验只在会话原始 clientSha 与本次一致时兜底）——这是"并行上传进度单槽位"债务在服务端的对应隐患 → 匹配键加入 clientSha256（或客户端上传 nonce），不匹配则新建会话 → 改动量(S)

- [次要] 【①性能】files/UploadService.kt:74 → 【债务"MAX_FILE_SIZE 未强制"：**已解决**】initUpload:77 校验声明大小、putChunk:238 按期望大小硬截断，单会话实际字节 ≤ 声明 ≤ 上限；但限制只按"单会话"计：同一账户可并开任意多个 open 会话，每个都能写到 maxFileSize → 1C/1G 小机磁盘仍可被会话数放大打满（无总量闸门），且每会话都会占 tmp 目录与 24h TTL 内的清理量 → init 时校验"用户级 open 会话数/总字节"配额；同步修正 roadmap.md:61 的过时描述 → 改动量(S)

- [次要] 【①性能】files/FileService.kt:241 → `listTrash` 无分页/上限，返回该用户全部回收站行（toFileDto 每行 12 个字段字符串化，:359） → 批量删除后一次 GET 可能返回数万行 DTO，响应体与堆内存瞬时峰值无界（删除 1 万张照片后打开回收站页即触发） → limit + keyset 游标（默认 100，deleted_at DESC） → 改动量(S)

- [次要] 【①性能】files/UploadService.kt:404 → cleanupExpired 先取全部过期 id，再**逐 session** 开事务执行 2 条 DELETE + 逐目录删文件循环；upload_sessions 表（db/Tables.kt:89–106）除主键外没有任何索引（expires_at、folder_id、user_id 全靠全表扫） → 过期会话多时清理循环产生几十上百次 round trip，每 6h 与在线请求抢那 5 条连接；refresh_tokens.expires_at 同样无索引（:21–28，30 天 TTL 下随刷新频率线性增长） → 批量 inList 删除（2 条语句完成全清理），补 `(expires_at)`、`(folder_id)`、`(user_id)`、`refresh_tokens(expires_at/user_id)` 索引 → 改动量(S)

- [次要] 【①性能】files/ShareService.kt:182 → `recordDownload` 每次正文下载（bytes=0 起）都单独开一个 UPDATE 事务，无任何去重；ShareRoutes.kt:79 在流式发送**之前**同步等待它完成 → 公开分享每命中一次就多 1 次写事务 + WAL 刷盘，热点分享链接的批量下载会把连接池消耗在计数上（view 有 5min 去重 :171，download 完全没有） → 下载计数改内存累加定时 flush，或按 (share, IP, 60s) 复用 `Throttle.firstSince` 去重并把计数移到响应开始发送之后 → 改动量(S)

- [次要] 【①性能】files/BlobPurge.kt:16 → 【债务"孤儿 blob 竞态"：**部分解决**】已加"unlink 前在新事务重计数"守卫（:31），注释自认残留窗口 = 重计数提交到 unlink 之间；反向窗口（秒传 exists() 通过后 blob 恰被删）仍在，与 roadmap.md:56–59 记载一致 → 性能侧代价：每次 purge 多一整轮 2N 查询（叠加第 3 条，删除链路合计 4 轮/key），守卫本身把 O(keys) 查询又跑了一遍 → 按 roadmap 方案落地：宽限期删除表（延迟 N 小时再 unlink）或"秒传插入 vs 计数删除"共用同一把 PG advisory lock，同时把重计数合并进批量查询 → 改动量(L)

- [次要] 【①性能】files/FileService.kt:226 → trashFile、setFavorite（:299）、setArchived（:307）、restore（:250）、updateFile（:187）一律"先查行 → UPDATE → 再查行组装 DTO"，单次标记操作 3 条 round trip，其中两次是按主键重复读同一条行 → 每次收藏/归档/移入回收站都多 2 次无谓读；前端连点收藏时读放大明显 → `UPDATE ... RETURNING` 一条完成，或复用首查行 + update 影响行数 → 改动量(S)

## 建议

- [建议] 【①性能】system/VersionService.kt:87 → 冷缓存/失败冷却的判断在锁内、上游 fetch 在锁外（:95 在 synchronized 块之后） → TTL 到期瞬间并发多路 GET /api/version 会各自发起最长 6s 的阻塞 HTTP：多客户端冷启动时重复上游 IO 并占用 Dispatchers.IO 线程；失败时同样成簇重试（负缓存只挡 60s） → single-flight：fetch 纳入同一锁或 CompletableFuture 单飞，其余请求等待首个结果 → 改动量(S)

- [建议] 【①性能】system/SystemRoutes.kt:94 → 更新代理每次下载都实时回源 GitHub，无缓存、无并发上限、无鉴权 → 多台设备同时升级时同一 APK 被重复拉取 N 遍并全程经 1C 小机转发，占用 Dispatchers.IO 线程数分钟、吃满上行带宽，挤压正常上传下载 → 按 tag/file 落本地缓存（ETag/If-Range 透传），并限制代理并发数（如信号量 2） → 改动量(M)

- [建议] 【①性能】files/FileContent.kt:48 → 搜索用 `lower(name) LIKE '%q%'` 前后通配，`.limit(100)` 只裁结果不裁扫描 → 每次搜索全表扫该用户全部存活文件行，库大后搜索接口从毫秒级退化到百毫秒级并持续占用连接 → 建 pg_trgm GIN 索引，或先按 updated_at 时间窗裁剪再做 like → 改动量(M)

- [建议] 【①性能】files/FileContent.kt:190 → 每次下载 = getFileMeta(DB) + `storage.exists` + `storage.get` 三跳；缩略图（files/ThumbnailRoutes.kt:74–76）与分享路径（files/ShareRoutes.kt:86、:99–101）同样先 HEAD 再 GET，且 /content 从不下发 ETag/条件请求 → 本地盘是每请求 2 次 stat，S3 后端则是每请求 2 次 HTTPS 往返（HEAD+GET，storage/S3StorageProvider.kt:107/116）；重复浏览/播放同一文件无任何 304 短路 → S3 路径去掉前置 exists()（用 GET 404 兜底，`FileNotFoundException` 已有处理），/content 下发 `ETag: sha256` 并处理 If-None-Match → 改动量(S)

- [建议] 【①性能】files/FileService.kt:48 → `contents()` 为 cur 文件夹多发第 4 条独立 SELECT，而父夹存在性在 :34 已查过一次（root 场景为无谓查询） → 每次进入文件夹多 1 条重复 SELECT，目录浏览高频触发 → 首次查询把 ResultRow 留住复用，或把存在性校验与 cur 合并为一条查询 → 改动量(S)

- [建议] 【①性能】files/FileTree.kt:51 → `childrenIndex` 每次调用全量读取用户名下所有文件夹行：album 带 root 参数的每一页（files/FileContent.kt:141）、每次删文件夹（FileService.kt:134）都要重复一次 → 文件夹数上万时每翻一页都全树读取 + 内存建图，相册滚动浏览逐页重复 → 进程内按 userId 短 TTL 缓存目录树（失效点=文件夹增删改），或改递归 CTE 只取目标子树 → 改动量(S)

- [建议] 【①性能】docs/roadmap.md:61 / app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/upload/UploadManager.kt:66 → 四条已知债务核验汇总：① MAX_FILE_SIZE【已解决】——initUpload:77 + putChunk:238 双重强制（残留点见第 18 条），roadmap.md:61 描述已过时；② 并行上传进度单槽位【客户端仍存在】——UploadManager 只有单个 `_progress`/`activeUploadId` 槽位（:66–68），SyncUploadQueue.kt:42 通过"每 lane 一个 manager"局部规避，服务端支持多会话并行但有同名折叠隐患（见第 17 条） → 文档与实现状态不一致会误导后续排查；本轮服务端无需为①②再改代码，客户端槽位问题转 UI 线跟进 → 改动量(S)
