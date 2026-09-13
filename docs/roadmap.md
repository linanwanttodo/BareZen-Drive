# 路线图

## v0.0.1（当前版本）

- 认证：注册/登录/刷新轮换（JWT 15min + opaque refresh 30d）
- 虚拟文件系统：文件夹 CRUD（递归删除）、文件重命名/移动/删除、blob 引用计数
- 分块上传协议：断点续传、秒传、abort、过期清理（6h）
- 下载：Range 支持（206/416）；Web 客户端静态托管（SPA 回退）
- 客户端：Android + Web（Compose Multiplatform 共享 UI）、整文件流式哈希、单块重试、上传进度
- 部署：Dockerfile 三段构建 + 1G1C 调优 compose

## v0.2（进行中）

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
- Flyway 迁移替代 createMissingTablesAndColumns
- iOS 客户端、Desktop 客户端（平台接口与 CI 任务已预留，剩余为目标启用与 actual 实现。依赖无障碍：
  共享 UI 用的 `backdrop`、`shapes` 均已发布 iOS 与 jvm 产物；Apple target 需在 macOS 上编译验证）
- Android release 签名包（条件签名已就位，配置 `ANDROID_KEYSTORE_BASE64` 等 Secrets 即启用）
- Web 端 token 存储评估 HttpOnly cookie（v0.0.1 用 localStorage，存在 XSS 暴露面）
- 上传合并边收边写（消除 tmp+merge 2x 磁盘峰值）
- npm `ws` 8.20.1（High，GHSA-96hv-2xvq-fx4p）为 Kotlin/JS 构建工具链（webpack dev server）传递依赖，仅构建期存在、不进生产运行时；KGP 钉版无法通过 yarn 升级，待 Kotlin 插件更新后自然消除（Opsera 扫描 2026-09-09）
- `jackson-databind` 2.22.0（两条 Medium）与 `netty-codec-http` 4.2.16（一条 Medium）均由 ktor 3.5.2 传递引入，仓库未直接声明，只能随 ktor 版本升级收敛；服务端未使用 jackson 的反序列化路径（序列化走 kotlinx.serialization）（Opsera 扫描 2026-09-12）

## v0.3+（远期）

- 多设备同步与冲突解决
- 端到端加密
- 多节点部署
- WebDAV 接口
- 团队空间（多用户共享目录）

## 已知的 v0.0.1 技术债（改进项）

- 崩溃时事务提交与 blob 物理删除之间的孤儿 blob（清理任务当前仅覆盖上传会话与回收站保留期）；同一
  窗口反向也可利用：孤立计数为 0 之后、`unlink` 之前，另一台设备按相同 sha256 秒传会 `exists()` 通过
  并插入新引用，随后被删掉的就是刚被重新引用的内容。彻底修需要宽限期删除表或在「秒传插入」与「计数
  删除」两侧加同一把 advisory lock（当前测试跑 H2，暂只覆盖 PostgreSQL 路径），本轮记录为已知限制
- Web 端大文件下载/保存的内存峰值（浏览器机制限制）
- `MAX_FILE_SIZE`（`FILE_TOO_LARGE`）服务端未强制，磁盘容量是实际上限
- 删除/416 路径上的文件句柄关闭时机未显式管理
- Web 端保存文件（saver）整文件缓冲，峰值内存约为文件大小 2-3 倍
- 并行上传时进度只展示单个文件（单槽位覆盖）
- 缩略图 PUT 幂等跳过：同一 sha256 的坏封面无法被覆盖（需先删 blob 引用或后续提供覆盖/重生成能力）
- 客户端封面生成失败（不支持的编码等）时文件将永久无封面（显示类型图标兜底）
- Material 图标待迁移 AutoMirrored（当前 3 个弃用警告）；icons-extended 仅在链接期裁剪，发布前需复核 wasm 产物体积
- 退出登录无确认对话框；面包屑按 push 语义增长，无截断策略
