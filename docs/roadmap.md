# 路线图

## v0.0.1

- 认证：注册/登录/刷新轮换（JWT 15min + opaque refresh 30d）
- 虚拟文件系统：文件夹 CRUD（递归删除）、文件重命名/移动/删除、blob 引用计数
- 分块上传协议：断点续传、秒传、abort、过期清理（6h）
- 下载：Range 支持（206/416）；Web 客户端静态托管（SPA 回退）
- 客户端：Android + Web（Compose Multiplatform 共享 UI）、整文件流式哈希、单块重试、上传进度
- 部署：Dockerfile 三段构建 + 1G1C 调优 compose

## v0.2（进行中，当前发布 v0.2.1）

- [x] 客户端缩略图（图片+视频封面，上传时生成，服务端只存/发）+ 相册按月时间轴（2026-09-05）
- [x] 在线预览：图片缩放/滑动、视频/音频（Android Media3、Web 新标签页）、文本前 256KB、PDF（Android pdfium、Web 新标签页）（2026-09-05）
- [x] 签名下载 URL（HMAC，为分享链接打地基）（2026-09-05）
- [x] 导航重构：首页（相册板块 + 最近）/ 文件 / 设置（2026-09-05）
- [x] 分享链接（只读，带过期时间，可撤销；文件与文件夹。2026-09-09。可写链接留待后续版本）
- [x] 分享访问/下载计数 + 分享管理页（设置入口，全部链接统计与关闭。2026-09-09）
- [x] 相册固定文件夹：专用「相册」根 + 按设备分子文件夹（Web/Android...），时间轴只扫相册子树，上传直落设备文件夹（2026-09-09）
- [x] 上传分块流式化：服务端分块边收边写盘，内存峰值从 分块大小x并发 降为固定 64KiB 缓冲（2026-09-09）
- [x] 跨端检查更新：服务端 `GET /api/version` 统一查询发布清单 `update.json`，设置页在每个客户端提供入口，Web 检测到服务端升级后提示重新加载，Android 支持清单直链下载（2026-09-10）
- [x] 注册开关：设置页可关闭开放注册，关闭后登录页隐藏注册入口、接口返回 `REGISTRATION_DISABLED`（2026-09-10）
- [x] `install.sh` 一键部署脚本（Docker，env 向导，多架构镜像 amd64/arm64）（2026-09-10）
- [x] 持续集成：单一 Pipeline 工作流（测试门 → web 一次编译供 server/docker 复用 → 多架构镜像 → manifest → Release），产物按 `BareZen-Drive-<版本>-<平台>` 命名（2026-09-10）
- [x] 版本号单一来源：`gradle.properties` 的 `version`，Android versionName/versionCode、服务端、清单全部派生（2026-09-10）
- [x] 文档拆分：README 与 docs 索引各自独立中英文两份（2026-09-10）
- [x] 回收站（删除改为标记，定期清理）（2026-09-12。文件删除只置 `deleted_at`，行与 blob 保留 30 天，超期由清理循环物理回收；收藏/归档标记同期落地，时间线与搜索默认过滤归档与回收站行）
- [x] EXIF 照片时间轴（拍摄时间替代文件时间）（2026-09-12。上传携带 `takenAt`（Android 取 MediaStore `DATE_TAKEN`），时间线按 `COALESCE(taken_at, updated_at)` 排序）
- [x] WorkManager 后台上传 / 自动上传（Android）（2026-09-12。MediaStore ContentObserver 秒级触发 + 6h 周期 + 每日全量比对，SQLite 持久队列按相册勾选、断网早停、重试退避，前台通知（dataSync）保证进程存活）
- [x] 相册自动备份策略完善（2026-09-13。新增「仅充电时同步」开关与电量下限，状态卡写明暂停原因（等待网络／需接通电源／电量不足／未登录）而非只挂「待备份 N 项」；变更触发改用加急任务（`setExpedited`）并做增量扫描（按 `DATE_MODIFIED` 水位回溯 5 分钟重叠窗口），只有每日对账全量剪枝；失败退避由默认指数 30 秒起步改为线性 10 分钟；加急任务只允许网络/存储约束，充电与电量策略因此改由 `SyncWorker` 开工前自查；并发路数按网络类型切换（不计费 2 路 / 计费 1 路）；被排除相册不再计入待备份计数，勾回相册立即重驱队列；首启打开开关时弹出相册勾选引导（只列照片最多的 8 个相册，`albumBucketsReviewed` 保证每设备仅问一次）；「立即同步」收进状态卡，手动上传对话框补「后台运行」；pass 进行中排除的相册即时生效（每个 item 上传前按 bucket 复核，队列行保持 PENDING 便于勾回复用）；`listBuckets()` 不再吞扫描异常，权限被拒与「没有相册」分离呈现，首启引导只在真的列出过相册时才记为已答复；全量选择页补「全部备份」批量恢复；「边读边算边传」评估后放弃（省一次本地热读、赔上整个秒传判定，净亏损，见 architecture.md）；`SyncPolicy` 与 `backupPauseReason` 抽为纯函数并补宿主单测）
- [x] 首页状态仪表盘（延迟/存储/CPU/内存/实时速率）（2026-09-12。`GET /api/server/stats` 需登录，读 `/proc` 与存储卷，取不到的项返回 -1 并由客户端显示为「—」；磁盘读数取自 `STORAGE_DIR` 所在卷）
- [x] 集群多节点存储接口预留（2026-09-12。`StorageRegistry` 按 `backend:key` 解析与跨后端搬运已就位并有测试；请求路径仍只使用单一后端，真正的多节点调度留待后续）
- [x] 文件版本（覆盖上传保留历史）（2026-09-12。`overwrite` 上传把旧内容写进 `file_versions`，每文件上限 20 版、超出静默裁旧；恢复是一次交换；版本 blob 与文件 blob 共用引用计数；客户端在文件菜单提供版本历史查看/恢复/删除）
- [x] S3/MinIO StorageProvider（2026-09-12。`STORAGE_BACKEND=s3` + `S3_*` 环境变量，用 JDK HttpClient 与自实现 SigV4（AWS 官方测试向量校验），支持 path-style；不引 AWS SDK）
- [x] 同级同名的先查后写竞态收敛（2026-09-13。唯一索引由 `(user, folder, name)` 改为 `(user, folder, name, deleted_at)`，回收站行不再占用命名空间；`nameConflict` 的预检窗口由 `UniqueViolation` → 409 `NAME_CONFLICT` 兜底，上传初始化、创建/重命名文件夹与恢复走同一口径，启动时幂等删除遗留索引）
- Flyway 迁移替代 createMissingTablesAndColumns（本轮复核后决定**暂不做**：真正需要它是「改列
  类型 / 删列 / 加约束」那一天；当前只增列的演进方式下它买不到实际可靠性，而代价是一次
  V1__baseline 快照加把全部 DDL 迁进迁移文件。已确认真实缺口只有一处——Exposed 不认识部分索引
  与表达式索引，那两类仍走 `DatabaseFactory` 的启动 DDL）
- iOS 客户端、Desktop 客户端（平台接口与 CI 任务已预留，剩余为目标启用与 actual 实现。依赖无障碍：
  共享 UI 用的 `backdrop`、`shapes` 均已发布 iOS 与 jvm 产物；Apple target 需在 macOS 上编译验证）
- Android release 签名包（条件签名已就位，配置 `ANDROID_KEYSTORE_BASE64` 等 Secrets 即启用）
- Web 端 token 存储评估 HttpOnly cookie（v0.0.1 用 localStorage，存在 XSS 暴露面）
- 上传合并边收边写（消除 tmp+merge 2x 磁盘峰值）——本轮已做一半：合并完成后本地直接改名到 blob
  目标（同卷原子），省掉一遍全量拷贝，读放大约从 2x 降到 1x；「边收边写」本身（不落 merge 中间
  文件）仍未做，峰值写入仍是 2x
- npm `ws` 8.20.1（High，GHSA-96hv-2xvq-fx4p）为 Kotlin/JS 构建工具链（webpack dev server）传递依赖，仅构建期存在、不进生产运行时；KGP 钉版无法通过 yarn 升级，待 Kotlin 插件更新后自然消除（Opsera 扫描 2026-09-09）
- `jackson-databind` 2.22.0（两条 Medium）与 `netty-codec-http` 4.2.16（一条 Medium）均由 ktor 3.5.2 传递引入，仓库未直接声明，只能随 ktor 版本升级收敛；服务端未使用 jackson 的反序列化路径（序列化走 kotlinx.serialization）（Opsera 扫描 2026-09-12）

- [x] WebDAV 挂载为网络盘：每设备应用密码（32 字节随机 + SHA-256 精确查，不用 bcrypt——挂载每个请求都要鉴权一次，bcrypt cost=10 约 100ms/次）、OPTIONS/PROPFIND/GET/HEAD/PUT/MKCOL/MOVE/COPY/DELETE 读写齐全（2026-10-01）
  - 不实现 LOCK/UNLOCK（501 且不宣告），代价是用 Office 打开网盘上的文档保存可能失败；必须 HTTPS（Basic 明文，Windows 在 HTTP 上默认禁用 Basic）
  - 覆盖写不产生版本（WebDAV 专用语义），旧 blob 走宽限期删除队列；无秒传（协议没有先报哈希的钩子）
  - MOVE/COPY 只会覆盖**文件**；目标是已存在的**集合**一律 409，不代为硬删那棵子树（要删就显式 DELETE）
  - 删除不对称：文件进回收站可恢复，**集合是硬删除整棵子树**——`folders` 没有 `deleted_at` 而 `files.folder_id` 有外键，模型上无法表达「文件夹进回收站」
  - 未经真实 Windows/macOS 客户端验证：只到协议层测试与真实 HTTP 冒烟脚本（`scripts/dav_smoke.sh`）全绿
- [x] 服务端 DB 与资源收敛（2026-10-17，以下数字都在一次性 PostgreSQL 16 容器上实测）
  - `/api/search` 加上 `lower(name)` 的 trigram GIN 索引：启动时幂等 `CREATE EXTENSION IF NOT EXISTS pg_trgm` 再建索引（非超级用户也能装，pg_trgm 是 trusted 扩展），装不上就退回原样（只慢不错）。17 万行库上确认该索引能被 `lower(name) LIKE '%q%'` 用作 Index Cond，路由原本的查询形状（`ORDER BY updated_at DESC LIMIT 100`）在命中稀少的关键词上选了它（Bitmap Index Scan）；命中面很宽的关键词仍按选择性走 `(user_id, deleted_at, updated_at)` 那条 btree——要让它稳定走 trigram，得改成按相关度排序，那属于查询语义变更，不在本次修复里做
  - 相册 `?root=<folderId>` 的子树改由 SQL 内递归 CTE 展开（锚点带 `user_id`，别人目录的 id 实测解析为空集），不再每翻一页读一遍整棵目录树，也不再拼无界 `IN (...)`
  - 回收站清空与到期清理改为边取边删（每页 1000 行），`hardDelete` 与删子树的每个 `IN` 都分批：绑定参数不再随行数增长，PostgreSQL 的 65 535 上限撞不到
  - 复制子树由逐行 INSERT 改为分批 `batchInsert`；pgjdbc 默认把攒好的批一条条发出去，故对 `jdbc:postgresql:` 的 URL 自动补 `reWriteBatchedInserts=true`。21 个目录 + 336 个文件的子树复制，实测只有 3 条 folders INSERT 与 3 条 files INSERT（分别 256/64/16 行的多值语句）
  - blob 清理：per-key advisory 锁改在借来的连接上以会话锁形式持有，网络 unlink 期间不再开着事务（S3 客户端单请求超时 10 分钟，5 条连接能被一个慢端点全钉住）。实测 `pg_advisory_lock` / `pg_advisory_unlock` 前后没有 `BEGIN`/`COMMIT`，第二个会话在锁期间拿不到同一 key，宽限期到的键与缩略图被物理删除、队列行随之清空；每轮只取 200 个键，unlink 失败的保留队列行等下一轮
  - 公开分享的列出一页为限（默认 200、上限 1000），排序也交给数据库（`lower(name), id`）；游标是 `<名字>:<id>`，行值比较 `(lower(name), id) > (lower(?), ?)` 在 PostgreSQL 上实测可用，由客户端连续翻，所以整份目录依旧看得全
- [x] 客户端列表、预览与重组的八处修复（2026-10-18）
  - 方格视图补上加载更多页脚，与列表视图共用同一个 `LoadMoreFooter`：超过 200 个文件的目录在方格模式下不再永远只有一页（方格还是图片目录的默认视图）
  - 别处上传结束引发的刷新改为锚定合并（`mergeFirstPage`）：新页供头部，已加载且排在页尾之后的行留在原地，翻到第 3 页时不再被拽回 200 行的窗口。口径抽成 `internal fun`，4 条宿主单测覆盖「页内行更新、页外行保留、页内消失的行真的消失、一页装得下则整份替换」
  - 网页端图片预览设 24MB 缓冲上限：`previewBytes` 把整份原图读进 JS 堆再解码，一张 40MB 手机照片足够让标签页崩溃，翻页每张重来一次；超限改为保留列表封面并提示下载查看。原生端解码惰性且堆外，不设这个限
  - 重试判定只看 `ConnectTimeoutException`，删掉「报文里含 Failed to connect 就算没连上」的文本嗅探：嗅探会把服务端返回体或传输中途的失败误判成没连上，于是建目录、建分享这类 POST 可能被重发一次
  - 瀑布流的日期浮层改用 `derivedStateOf` 读 `firstVisibleItemIndex`，滚动不再连带重跑整个网格的组合
  - 回收站行改用 `itemsIndexed`，去掉每行一次 `indexOfFirst` 全表扫描
  - wasm 预览不再一组合就 `window.open`：改为「在新标签页打开」按钮，由用户手势触发（滑过一排视频/PDF 曾经每次尝试开一个新标签页）
  - `App.kt` 交给四个标签页的回调全部提到 `remember`（`openPreview` / `openFolder` / `jumpToPath` / `selectTab` 等，与既有的 `push` / `pop` 同一口径）：就地写死的 lambda 每次重组都是新实例，参数一变整屏就得重跑
- [x] Android 同步链路的资源收敛（2026-10-18）
  - 六小时周期任务改走增量扫描：它的职责是把队列排空，新照片由观察者触发的那趟 pass 几秒内就补上了。全量重读与裁剪仍然只在每日对账那一趟——只有那趟允许删队列行，增量扫描分不清「已删除」和「没变动」
  - `upsertScanned` 不再重写没变的行：描述列和状态都与库里一致就跳过。五万张没动的照片不再每天四次产生约十万次 SQLite 语句
  - 待传队列改按页取（keyset 游标，每页 256 行），总数用 `COUNT` 问：原先开跑前把整个待传集合 `SELECT *` 读进一个 list，首次备份五万张就是几十兆行数据压满整个 pass（几小时）。不用 OFFSET——处理过的行会退出待传集合，OFFSET 的窗口跟着前移会静默跳过补位的那行；队列顺序因此补上主键 `uri`，只按「照片优先 + 修改时间」时同键两行落在哪一页是随机的
  - 传输记录改成取到哪个文件才建一行，不再开跑时一次推五万行（每行都是一次整表拷贝）
  - 全进程共用一份 `ApiClient` / `FilesRepository`：`ApiClient` 没有 `close()`，每轮同步新建一个就是每轮漏掉一套 dispatcher + 空闲长连接池，外加 `cacheDir/http` 上一个 64MB 缓存的 journal；两个 `Cache` 实例指向同一目录时 OkHttp 只会让其中一个真正缓存，而这块缓存正是为省这份流量买的
  - 视频封面按上限解码首帧：不带尺寸的 `getFrameAtTime` 交回的是全分辨率帧，4K 视频就是约 33MB 的 ARGB 位图，而它落在上传链路里——另一条车道正占着分块缓冲。API 27 起用 `getScaledFrameAtTime`（保比例、不超出给定框），27 以下没有可用的限尺寸接口，仍旧全尺寸解码后再缩

- [x] 构建 / 发布 / 部署面的收敛（2026-10-18）
  - 就绪与存活分家：新增 `GET /readyz`，从池里借一条连接跑 `SELECT 1`，答得上是 `200 {"status":"ready","db":"ok"}`，答不上是 `503 {"status":"unready","db":"down"}`；`/health` 退回只报存活。两个 Dockerfile 的 `HEALTHCHECK` 和 install.sh 的就绪等待都改读 `/readyz`——原来那行注释写的是「/health 的新鲜响应证明进程真在服务」，可 `/health` 是个静态串：PG 在启动之后挂掉，容器照日被 Docker 判成 healthy，任何照 HEALTHCHECK 做自愈的工具继承的是假信号
  - 从源码构建不再可能静默带旧产物：Dockerfile 第二段灌入本次 wasm 产物前先 `rm -rf` 服务端 `resources/web`（`COPY` 只并集、从不删除），`.dockerignore` 同步排除该目录里本地遗留的 16MB `.wasm` / `composeResources` / map。「先删再拷」这条 CI 早就在做，以前只有 CI 有
  - JVM 尺寸改按容器算：去掉写死的 `-Xmx256m`，换成 `-XX:MaxRAMPercentage=50.0 -XX:MaxDirectMemorySize=128m`，compose 给 server 容器 `mem_limit: 768m`，百分比这才有了分母。写死堆上限在大机器上永远只拿到 256MB，在 1G 机器上又拦不住「堆 + 直接缓冲区 + ffmpeg 子进程」一起把宿主机吃到 OOM
  - 每个 CI job 补 `timeout-minutes`（Gradle 30 / 多架构 docker 45 / iOS 25 / 清单与发布 20 / 版本 10）：此前 11 个 job 全用 360 分钟默认值，一次卡死的 wasm 链接就能烧光当天免费额度
  - `:latest` / `:edge` 改到 `webdav` 门后才推（`docker.needs` 加上 `webdav`）：它原先只 `needs: [version, server]`，而 install.sh 默认拉 `latest`，等于把没过协议验收的镜像发给所有一键安装
  - 镜像名显式小写 `ghcr.io/<owner>/barezen-drive`，`update.json` 改写同一个值：Docker 引用语法不接受大写仓库名，GHCR 存时也统一小写，原先 CI 推的、清单公布的、compose 拉的拼写不一致，按 digest 核对时三处对不上
  - HTTPS 安装不再同时留明文端口：生成的 Caddy override 用 `ports: !override` 把 server 的对外映射改成 `127.0.0.1:<端口>`，对外只剩 Caddy 的 443。README 明写 WebDAV Basic 走明文 HTTP 不可接受，而向导装完 `:8080` 照样朝外
  - 安装类型从 web 切到 server 时补齐 `.env`：缺 `JWT_SECRET` / `POSTGRES_PASSWORD` 就地生成并告警，`POSTGRES_DB` / `POSTGRES_USER` 补默认值。以前 compose 是在 `mkdir` 之后才撞上 `${POSTGRES_PASSWORD:?}`，留下一个看着装完、实则起不来的目录
  - 重跑安装不再无声覆盖 compose 文件：发布版先落到 `.new`，与本地那份不同就把本地版本留成 `docker-compose.yml.local` 并打印提示，再启用发布版；操作员自己加的挂载或资源限制还能 diff 回来
  - compose 里 `DB_LEAK_DETECTION_MS` 默认从 `0` 改 `120000`：服务端本身默认开着，compose 显式写 0 等于关掉它；五条连接漏一条就是全站卡死，那条栈日志是唯一的预警
  - 文档对回现状：`docs/development.md` 描述的 `ci.yml` / `docker-publish.yml` 早就不存在（只剩 `pipeline.yml`），CI 段按现状重写、部署自检的 curl 改指 `/readyz`；`docs/api.md` 补 `/readyz`；升级起不来时的回滚顺序写进 `usage.md` / `usage.en.md`
  - 手工验收（install.sh / compose / Dockerfile 没有自动化测试可依托）：把脚本里生成 override、补齐 `.env`、拉取 compose 的三段原样抽出来执行，再用 `docker compose config` 核对渲染结果，30 项断言全过——明文端口朝外与 loopback 收口的对照、`mem_limit` 渲染成 805306368、残缺 `.env` 先被 compose 拒绝再被补齐可用、既有密钥不会被重新生成顶掉、改过的 compose 留下 `.local` 而未改过的不留、`timeout-minutes` 条数与 job 数相等、`docker.needs` 含 `webdav`

- [x] 运维面的小项挑选（2026-10-18，P2 里先做有后果的）
  - 手动指定版本号不再允许从任意分支发布：`version` 里 `release=true` 的前提改成 ref 是 `master` 或
    `refs/tags/*`。以前在功能分支上 dispatch 一个版本号就会 `is_release=true`，`release` 任务给那个
    分支的 HEAD 打标签并建 GitHub Release，版本化镜像也一起推出去。判定逻辑抽出来后用四种组合实跑过：
    master+输入 = 发布、master 无输入 = 不发、功能分支+输入 = 不发、功能分支无输入 = 不发
  - 关停宽限从「1 秒优雅 + 5 秒强制」改成 5 + 8 秒，compose 侧 `stop_grace_period: 15s`：以前默认 10 秒
    刚好卡在一次优雅关停中间，而正在写入的分块或正在流出的下载被 1 秒就掐掉，客户端得整块重传。
    `docker compose config` 确认渲染出 15s
  - README 的技术栈表把 JDBC 驱动写成 42.7.12，实际 `libs.versions.toml` 是 42.7.13：文档跟每次依赖
    升级脱节的具体一处，改文档不改依赖
  - 本轮明确不做的 P2：`/metrics` 与容器内文件日志（新增运维面机制，不是修复）、CI 的重复编译与
    `desktop`/`ios` 探测任务成本（要动作业结构）、基础镜像 digest 固定与 dependabot（发布面策略）、
    `postgres:16` 大版本走向 EOL 的升级路径（需要真实的 dump/restore 演练，不是改一行）、大文件拆分
    R1/R2/R3（与本目标无关，另立条目）

- [x] 视觉令牌层：形状、字阶、描边各一处定义（2026-10-18）
  - `ui/theme/Shapes.kt` 把圆角收成四档语义：媒体 8 / 控件 12 / 卡面 16 / 弹窗与 hero 24，
    注入 `MaterialTheme.shapes`；此前 Material 默认档和 14 处手写半径（4/6/8/20dp）混用，
    同一种卡片能差几个点。预览页浮层看着像图片其实是可点控件，留在 12 档
  - `ui/theme/Typography.kt` 只覆盖真正用到的 11 个角色：辅助行 12→13sp（全 App 56 处，是最小
    却仍要读的一行），字重分三档（标题 600 / 正文 400 / 标签 500），中文零字距，数字请求 `tnum`
    等宽。正文 14sp 不动——它同时是没有显式 style 的 `Text` 的默认值
  - `ui/theme/Hairline.kt` 定 1dp `outlineVariant` 描边作为唯一的分层手段：卡片、面板、网格单元、
    空态底板、头像圆都读它。仓库里本来就没有任何抬升，所以走的是规范「阴影与线二选一」的线那支，
    并把这条写回规范 §3
  - 暗色判定收成一处 `LocalIsDark`（见提交 `fix(ui): 暗色判定收成一处`），优先级规则抽成纯函数
    `resolveDark` 并补宿主单测
  - 首页状态卡五列等分 304dp，每列 56dp，而 GB 档格式化是两位小数带空格，数值比整列还宽只能
    省略号收尾：`formatFileSize` 加 compact 档，吞吐改整数格式化，列间补 6dp
  - 本轮明确不动：底部液态玻璃胶囊栏（用户裁定「就这样」，含选中胶囊 56dp 装不下图标+标签、
    标签被下沿切住这个已知缺陷）；弹窗内边距 20dp（M3 `AlertDialog` 的 24dp 内边距写死在组件里，
    29 个调用点换自定义封装才拿得动，圆角 24 与标题 18/600 已由令牌达成）；设置卡间距（原判断
    「外边距 6 太密」读错了变量——6dp 是单边，卡与卡之间实际是 12dp，已在要求的 8-10 之外）

- [x] 稳定版自动升级（服务端 + Android，2026-10-28）
  - 宿主机 `systemd` 定时器：`install.sh` 装 `barezen-update.timer`（每天 04:15 + 45 分钟随机抖动）
    与 `barezen-update.service`（`Type=oneshot`），脚本落 `/usr/local/bin`，配置落
    `/etc/barezen-update.env`。容器换不了自己的镜像，所以这个动作只能住在宿主机
  - 只跟稳定版：版本来自本机 `/api/version`（默认读 `releases/latest/download/update.json`，
    读不到才回落 GitHub 的 `releases/latest`，缓存 10 分钟），再要求纯 `x.y.z`——清单里的
    `version` 本身不过滤 pre-release，带 `-`/`+` 的 beta 与 build 号靠这一步跳过；
    判定抽成 `stable()` 并有脚本测试
  - 顺序固定：`pg_dump` 到 `<目录>/backups/auto-*` → 改 compose 标签 → `pull server` →
    `up -d server` → 探 `/readyz`。dump 失败什么都不改就退出；pull 失败退回标签；升级后 503 就
    退回旧标签重拉重探一次。退出码 0/1/2/3 区分「无事可做或已升好 / 已回滚 / 回滚也失败 / 动手前中止」
  - 回滚免费的前提是 schema 只增列；哪天要改列类型或加约束，必须先对该版本关掉自动升级。这条同时
    写进 `usage.md` 与脚本注释
  - 它不是看门狗：实例本来就宕着时只会安静跳过，保活仍靠 compose 的 restart 策略与外部监控
  - Android：登录后每天一次 WorkManager 检查（任意网络，`CONNECTED`），有新稳定版就下载并校验
    sha256，然后只发一条通知；点通知才进系统安装页（安装确认是 Android 的，去不掉）。通知发不出去
    （13+ 拒绝权限、应用或通道被关）时连网络都不碰——因此也不会有「下好了却没人知道、第二天又因
    已下过而跳过」的永久丢失。已下好的版本号记在本地，没人点就不每天重下
  - `install.sh` 的安装类型收成两项（1 仅 Web 静态端 / 2 服务端含 Web，原 2/3 输出逐字节相同），
    非交互重跑沿用 `.env` 里的答案，不会把 web-only 实例悄悄改成服务器；缺 `flock`、GNU `sed -i`、
    `sort -V` 或没有 systemd 时强制关闭自动升级并打印缺什么

## v0.3+（远期）

- 多设备同步与冲突解决
- 端到端加密
- 多节点部署
- 团队空间（多用户共享目录）

## 已知的 v0.0.1 技术债（改进项）

- `/readyz` 的探测不额外设超时：等连接靠的是池自己的 `DB_CONNECTION_TIMEOUT_MS`（默认 30s），比 Docker
  探针的 5s 超时慢。不加 `withTimeout` 是有意的——取消打断不了阻塞中的 socket 读，每 30 秒就会留下一个
  被占死的 IO 线程，一台中途断网的机器能在几十分钟内把调度器吃空；宁可让探针超时、由 Docker 判不健康
- 镜像 `HEALTHCHECK` 里的 8080 是写死的：compose 把容器侧 `SERVER_PORT` 钉在 8080，所以只有单独 `docker run`
  并改端口的用户会探错地址
- schema 仍然没有版本表：启动 DDL 失败时能给的只有日志，数据退路是 `install.sh` 的事前 dump 与「把镜像
  标签退回上一版」（只增列不改列，旧版本读得了新库）。是否引入迁移框架已决定暂缓，见 architecture.md
- CI 的重复编译与探测成本没动：`test` / `webdav` / `server` / `web` 各自从零编译同一张 JVM+wasm 图，
  `desktop` / `ios` 两个预留任务每次 push 仍要跑一遍（`continue-on-error`）。本轮只补超时和门禁；远程
  build cache 与「只在 workflow_dispatch 跑预留任务」是另一件事

- 宽限期删除表落地后的两个残留：① 删除后空间最坏要等「宽限期 2h + 清理周期 6h」才真正释放（约 8
  小时），换来的是秒传与物理删除不再可能交错；② 若进程恰好在「丢最后一个引用的事务提交」与「写入队
  列」之间崩溃，该 blob 仍是无主孤儿——需要全量 refcount=0 磁盘扫描才能兜底，代价与收益不成比例，
  本轮不做（`BlobPurgeLock` 的锁只覆盖队列之后的路径）
- Web 端大文件下载/保存的内存峰值（浏览器机制限制）
- Web 端保存文件（saver）整文件缓冲，峰值内存约为文件大小 2-3 倍
- 用户级总配额未做：`MAX_FILE_SIZE`（`FILE_TOO_LARGE`）已按单会话强制（init 的 `size` 超限返回
  413，环境变量 `MAX_FILE_SIZE`，默认 10 GiB），但没有「每用户总占用 / 并发上传数」这一层，
  单个账号仍可把磁盘写满
- 客户端封面生成失败（不支持的编码等）时文件将永久无封面（显示类型图标兜底）——本轮把静默吞掉
  改成了分 gen/put 两段记日志，但仍未做行内「封面失败」标记
- 服务端 `ensureThumbnail` 遇到已存在的封面会直接早退，所以**存量坏封面**仍无法由服务端重新生成；
  本轮放开的是客户端 PUT 覆盖（且封面 GET 的 ETag 已改为按封面字节计算并允许重验证，所以替换
  能真正到达客户端）。仍缺一个「重新生成封面」入口
- 缩略图与头像的并发写入是后写覆盖先写（同 sha256 的每个封面都合法，可接受）；要严格有序需要
  代次计数器，即一次 schema 变更
- 相册 `?root=<folderId>` 的排序仍 seq 扫（1.54ms / 1006 buffers），需要把 `folder_id` 并进相册那条
  排序索引——子树展开本身已改由 SQL 递归 CTE 承担，剩下的只是索引
- `/api/search` 的 trigram 索引用不用得上是优化器按选择性决定的：命中面很宽的关键词仍走
  `(user_id, deleted_at, updated_at)` 那条 btree。要让它稳定走 trigram，得改成按相关度排序，
  那是查询语义变更，不算是本轮该顺手做的事
- wasm 上的连接级重试仍不会触发：JS/wasm 引擎把连接失败包成别的异常类型，具体形状没确认，
  没确认就写判定等于猜。去掉文本嗅探之后保证的是「不会误重发 POST」，代价是 wasm 少一层重试
- 「切标签、切主题时整屏重跑」这条路堵住了（`App.kt` 交给标签页的回调全部记忆化），但「跳过重组」
  要在真机或浏览器里数重组次数才算验证过：仓库内没有 Compose UI 测试框架，本轮不为此新增框架，
  证据只到编译与既有单测通过
- Material 图标待迁移 AutoMirrored（当前 3 个弃用警告）；icons-extended 仅在链接期裁剪，发布前需复核 wasm 产物体积
- 退出登录无确认对话框（面包屑的 push 增长与截断本轮已修）
- Android 同步链路缺自动化覆盖：`SyncDb` 走 ContentProvider 游标、`SyncUploadQueue` 依赖协程取消语义，
  工程里没有 Robolectric，两头都起不了假内容库。所以这几处的修复只有真机验收口径：① 相册里删掉一个已
  排队的照片，等每日对账跑完，队列里那行应消失而不是永远停在待传；② 同步进行中点「停止」，剩余未传的
  项应保持原状态，不该被整批标失败并各烧掉一次重试额度；③ 六小时那趟现在只补扫增量，日志里看它扫出的
  行数应远小于图库总数（新照片仍由每日对账兜底）；④ 队列改按页取后，首次备份几万张时传输中心应在文件
  被取到时才出现该行，进度条到末仍显示总数；⑤ 备份途中关掉屏幕，`Meminfo` 里该进程的 native heap 不该
  再随队列长度线性上涨（每轮新建的 OkHttp 连接池也不会再累积）；⑥ 传一段 4K 视频，封面生成期间的位图
  上限是 1024 边，不再是整帧
- 传输中心仍是一个待传文件一行：按页取用解决了 pass 的内存，但五万行的列表本身还在（每行一次整表拷贝）。
  收口要给它加汇总行，那是显示语义变更，不在本轮
- `install.sh` 的交互向导没有测试可依托（bash 且依赖 docker/网络），改动靠人工跑三条路径验收：首次安装、
  换端口重跑（旧 `.env` 的密钥与端口要原样保留、升级前落一份 dump）、本机已有别的实例时中止不动服务
- `FilesScreen` / `AlbumScreen` / `TransportScreen` 三个大文件仍按屏组织，未拆公共分页与上传状态：本轮
  只把重复的翻页口径合并（文件页与相册页共用同一套游标语义），拆文件本身单列为遗留
