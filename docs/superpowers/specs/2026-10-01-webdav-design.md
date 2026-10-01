# 阶段二设计：WebDAV（网络盘挂载）

日期：2026-10-01 ｜ 状态：待实施 ｜ 前置：阶段一审查与 UI 打磨已完成（`53bd9ac`..`094c05c`，v0.1.1）

## 1. 目标与非目标

**目标**：把 BareZen-Drive 暴露成一个可挂载的 WebDAV 共享，让 macOS Finder / Windows 资源管理器 / Linux Nautilus 能把它当网络盘用，读写齐全。

**非目标（v1 明确不做）**：

- **不做 LOCK/UNLOCK**。返 501、不进 `Allow`。已核实 wsgidav 关掉 lock manager 就是这个行为且 Windows 照常挂载；代价是不显示「文件被占用」、同文件双窗口编辑无互斥。文档必须写明「用 Office 打开网盘上的文档可能保存失败，因为 Office 保存前要加锁」。
- 不做 PROPPATCH（只读属性存储）、不做 dead property、不做 `Depth: infinity`。
- 不做跨账号共享的应用密码限定（见 §2.3）。
- 不引入任何新依赖（与项目现状一致：SigV4、限流都是自研）。

## 2. 鉴权

### 2.1 每设备应用密码 + HTTP Basic

结论来自调研：三大家族（Nextcloud / Synology / QNAP）都拒绝用主账号凭据挂载。Synology 与 QNAP 的机制是「建专用账号」，**本项目不可行** —— 没有跨账号文件访问（团队空间在 v0.3+），第二个账号挂上来是空盘。所以取 Nextcloud 的应用密码。

协议层依据：**RFC 7235 §2.1** 规定 HTTP 认证被假定为无状态，认证所需信息必须由请求自身提供 —— 挂载客户端没有 refresh 那个来回，凭据必须自包含。应用密码正是这个自包含凭据。

### 2.2 存储：照抄 refresh token，不照抄密码

**应用密码是高熵随机串，不是用户选的密码**，所以按哈希精确查（带唯一索引），**不做 bcrypt**。这不是省事，是关键：账号密码是 bcrypt cost=10 ≈ 100ms/次，挂载后每个 PROPFIND、每个 GET/HEAD 都付一次（Nextcloud 文档明确以「significant performance penalty」劝阻用主密码）。高熵 + 精确匹配没有离线爆破面，所以精确查找是安全的。

新表 `webdav_tokens`：

| 列 | 说明 |
|---|---|
| `id` UUID 主键 | 令牌的可枚举前缀（展示前 8 位，失败时不泄露全值） |
| `user_id` | 属主 |
| `token_hash` varchar(64) 唯一索引 | SHA-256，精确匹配 |
| `label` varchar(64) | 用户自述（「公司 iMac」） |
| `read_only` bool 默认 false | 见 §2.3 |
| `last_used_at` long 默认 0 | 供 UI 提示久未使用 |
| `created_at` long | |

明文只在创建时返回一次。**不可登录 Web UI、不可调管理端点**（与 Nextcloud 一致）。

无头部署（没有浏览器）也能用：`POST /api/webdav/tokens` 是普通 Bearer 端点，curl 即可。

### 2.3 `read_only` 令牌

一个布尔列 + 一个检查，换来「挂载点误删整个盘」这个灾难级后果的兜底。写入路径只需在入口判一次。列为 YAGNI 边缘但成本极低、失败后果极重，纳入。

**不做限定到子目录的令牌**：限定范围只有在「把令牌给别人」时才有价值，而本项目没有跨账号访问，别人拿到也是空盘。等真有了共享再说。

### 2.4 失败计数与登录额度彻底分离

RFC 7617 附录明确警告：处理隐式重试要小心，某些子系统会把反复登录失败判定为撞库攻击。挂载点的每个请求都带凭据，错误密码下的客户端重试循环若走登录额度，会同时（a）烧光 10 次/分钟预算、（b）触发 20 次/15min 的账户锁定，把挂载自己锁死。

因此 `webdav` 路径有**自己**的失败计数（独立键、独立窗口），且与应用密码查询同一个索引。

### 2.5 HTTPS 是硬要求，但不在服务端拦截

RFC 7617：Basic 以 base64 明文传密码；且 Windows 客户端在明文 HTTP 上**默认禁用 Basic**、默认走 Digest。所以 WebDAV 必须配 HTTPS，`install.sh` 的 Caddy 模式满足。

**但服务端不得因此拒绝明文请求** —— compose 部署里 server 与 Caddy 之间本来就是明文，在 server 层拒绝会把正常部署一起拒掉。因此只做两件事：启动时若 `/dav` 暴露在明文上打 WARN；文档写明。

> ⚠️ 这条要写进代码注释。后来者很容易「顺手修」成拒绝明文，那会把 compose 部署打死。

## 3. 资源模型

`/dav/` = 该用户自己的根。`folders` 行 ↔ 集合，`files` 行（`deleted_at = 0`）↔ 非集合。回收站不可见。

**相册树按普通文件夹原样暴露**（`相册/我的手机/相机/…`）。隐藏它会让手机自动备份的文件在网盘里凭空消失，比显示目录结构更反直觉。

### 3.1 路径 ↔ 资源

名称即路径段，逐段解析后按 `folders.parent` 逐级查（每段一次索引查询，深度 = 路径段数）。不用前缀匹配全表扫。

**必须先硬化名称校验**（见 §6.1）：拒绝 `.`、`..` 与 C0 控制字符。

### 3.2 href 编码

名字允许 `#`、`?`、`%`、空格、非 ASCII，但 `/` 已被禁止 —— 所以**每个名字恰好对应一个路径段**，分段编码安全。

- `href`：RFC 3986 百分号编码，逐段编码后用 `/` 连接。`UTF-8` 字节逐字节编码。
- `displayname`：只做 XML 转义，**绝不百分号编码**（客户端要的是原名）。

这两个处理不同，是经典 bug 源，测试必须分别钉住。

## 4. 方法

| 方法 | 行为 |
|---|---|
| `OPTIONS` | 200 + `DAV: 1` + `Allow` + `MS-Author-Via: DAV`。**不宣告** `X-MSDAVEXT: 1`，Windows 的 `Translate: f` 即被忽略 |
| `PROPFIND` | Depth 0/1；infinity 拒绝（403 + `propfind-finite-depth`）。**缺 Depth 头按 1 处理**（见 §6.3）。207 流式输出 |
| `GET` / `HEAD` | 复用阶段一抽出的 `StoredFileContent`，Range / 206 / 416 / 安全内容策略全部继承；支持 `If-None-Match` / `If-Modified-Since` → 304 |
| `PUT` | §5 |
| `MKCOL` | 建文件夹；body 非空按 RFC 返 415 |
| `MOVE` / `COPY` | 解析 `Destination`；集合递归。**COPY 递归是新增服务层逻辑**（§6.4） |
| `DELETE` | 映射既有软删除（进回收站）；集合走既有子树批删 |
| `LOCK` / `UNLOCK` | 501，不进 `Allow` |

方法层面的边界（不写清楚实现时必然现编，而现编就会不一致）：

- `PROPFIND` 打在**非集合**上且 `Depth: 1` → 400（RFC：非集合只接受 0 或 infinity）
- `PUT` / `DELETE` 的**父路径不存在** → 409 Conflict
- `PUT` 打在**已存在的集合**上 → 405
- `MKCOL` 打在已存在的名字上 → 405
- `MOVE`/`COPY` 缺 `Destination`、或 Destination 不可解析为同源绝对路径 → 400
- `MOVE`/`COPY` 跨用户 → 403（不得泄露目标是否存在）
- `read_only` 令牌上任何写方法（PUT/MKCOL/MOVE/COPY/DELETE/PROPPATCH）→ 403，**OPTIONS 与 PROPPATCH 不在写集内**

### 4.1 属性集

`resourcetype`、`displayname`、`getcontentlength`、`getlastmodified`、`getetag`、`creationdate`。

- **`getlastmodified` 用 `updated_at`，不用 `taken_at`**。客户端拿它做缓存与同步判定；用拍摄时间会让一张 2021 年的照片看起来「自 2021 年起从未修改」，同步全错。
- `getetag` = blob key 的**末段 sha256**（内容寻址，天然强 ETag），顺带白拿 `If-Match` 的乐观并发。**不要用整个 storage key** —— 它形如 `blobs/ab/cd/<sha>`，塞进 ETag 等于把内部存储布局发给客户端。集合没有内容，故**不返回 `getetag`**（而不是返回空值）。
- 请求里点名了但不存在的属性，按 RFC 在 multistatus 内返 404 —— 所以**必须解析请求体**（§6.2）。

## 5. 写入路径

复用既有管线，**流式切块、不二次落盘**：PUT 的 body 按 `chunkSize` 边界直接流进 session 的 `$i.part`（边写边算整文件 SHA-256），然后调既有 `complete()`。parts 写 1 遍、merge 改名 1 遍，与原生分块路径同量级；不是「先 spool 再读出切块」的 3 遍。

- 会话以 `chunkSize = MAX_CHUNK`（20 MiB）建，`size` 取 `Content-Length`，`expectedChunks` 自动对上。
- **无 `Content-Length`（chunked TE）**：先 spool 测大小再切块。桌面客户端拖文件基本都带长度，这条是兜底。
- **无秒传**：协议没有「先报哈希」的钩子，秒传靠客户端本地先算。这是诚实的协议限制，写进文档。
- 流式过程中要按 `size` 硬停（照 `putChunk` 的 `written > expectedSize` 做法），否则无长度的 PUT 能把磁盘写满。
- **中途失败必须清理 session 的 parts**（客户端断连没有 abort 动词，路由自己调既有 abort/清理）。

### 5.1 覆盖不产生版本（已定）

`complete()` 增加 `snapshotOnOverwrite: Boolean = true`；WebDAV 传 `false`，跳过 `VersionService.snapshotAndReplace`。

**旧 blob 不能漏**：不产生版本意味着旧内容失去最后一个引用，必须把它的 storage key 收集进 `orphans` → `deleteStoredBlobs` → 阶段一的宽限期删除队列。漏了就是无主孤儿（阶段一 A3 刚处理完的那类泄漏）。

命名冲突：有同名则覆盖（`overwrite = true`），与既有 `complete()` 行为一致；并发的两个客户端写同一路径由 `(user, folder, name, deleted_at)` 唯一索引兜底 → 409，客户端会自己改名重试。

## 6. 实施中必须硬化/新增的东西

这一节是本设计相对「照搬现有代码」多出来的部分，每条都有理由。

### 6.1 名称校验硬化（现有代码的真问题）

`FileService.nameOk` 只挡了空、超 255、含 `/`。因此**现在允许 `.`、`..` 与控制字符作为文件名**。

- `.` / `..`：WebDAV 里 `/dav/a/../b` 语义上必须等于 `/b`；真有叫 `..` 的目录则客户端意图歧义，而按文本解析 `..` 的实现会**逃出用户根目录**。API 层面这也是病态名字。
- 控制字符：XML 1.0 不允许裸控制字符，一个带 `\x01` 的名字会让 207 响应成为**客户端无法解析的非法 XML** —— 不是显示难看，是整个目录列不出来。

改在 `nameOk`（共享校验层），收益不限于 WebDAV。

### 6.2 PROPFIND 需要一个专用游标列举器

`FileService.contents()` **不能**用于 PROPFIND：文件夹**完全没有游标**，且文件在 `limit == null` 时整集 `map { it.toFileDto() }`。10 万文件的目录会一次性物化 10 万个 DTO —— 正是阶段一审查批过 listTrash 无界的那类问题。

新增 `DavListing`：按 `(lowerCase(name), id)` keyset 分批取**轻量行**（name / isCollection / size / mtime / etag），不构造 DTO；207 响应边取边写（`respondTextWriter`）。文件侧可复用既有 `parseFileCursor` 的形状，文件夹侧需新增同样的游标。

### 6.3 缺 Depth 头按 1 处理（对 RFC 的有意偏离）

RFC 4918 说「缺 Depth 头应视同 infinity」。但我们的 infinity 答案是 403，于是「缺头 → infinity → 403」等于对「只是想列个目录」的客户端直接拒绝。改为按 1 处理，并在代码注释与文档里写明这是有意偏离及理由。

### 6.4 COPY 递归是新增逻辑，不是复用

既有代码有递归删除（`deleteFolder`）与子树遍历（`FileTree.descendants`），但**没有「复制子树」的服务方法**。COPY 到集合要新写：遍历子树、逐个插行、命名冲突按 WebDAV 惯例跳过（`Overwrite: F`）或覆盖（`T`）。

**决定：实现，不砍。** 挂载点被当作网盘用，把一个文件夹拖进去是基本动作，对集合返 403 是个显眼的失败。这块抽一个「按行复制子树」的服务方法，日后若有别的批量操作（如分享整棵子树）可以直接复用。

诚实标注：这是 v1 范围内**唯一一块实质的新领域逻辑**。

### 6.5 请求体解析用 JDK XML，禁用外部实体

服务端是纯 JVM，直接用 `DocumentBuilderFactory`，**不自己写 XML 解析器**（那是坑）。必须关闭外部实体/外部 DTD（XXE）并限制请求体大小。响应侧手写生成，但转义必须严格 —— 文件名是用户输入，这里是注入面。

### 6.6 限流与并发

`/dav` 独立预算（挂载浏览天然高频），按地址计数并给出并发上限；**绝不共用 `reg`/`login` 的桶**。应用密码查询走索引（§2.2），所以鉴权本身不构成 CPU 压力。

## 7. 错误映射

命名冲突 409、超限 507（`DAV:quota-not-exceeded`）、不可满足 Range 416、条件失败 412、鉴权 401 + `WWW-Authenticate: Basic realm="BareZen Drive"`、方法不支持 405、infinity 403 + `propfind-finite-depth`。

## 8. 测试与验证

**TDD**：先写失败测试、确认红，再实现。

- 仓库内构造真实 HTTP 请求：PROPFIND 的 207 multistatus 逐条断言（href 百分号编码 vs displayname 仅转义、C0 名字不会出现、点名未知属性返 404 内联）、Depth 0/1/infinity/缺头、Range 206/416、条件 GET 304、If-Match 412、MOVE/COPY 跨集合、DELETE 进回收站、PUT 覆盖不产生版本且旧 blob 进删除队列、XXE 请求被拒、`read_only` 令牌写入被拒、流式超长被硬停。
- 可选手动互操作脚本（curl 驱动），供日后在真实客户端上验。
- **明确声明：本阶段无真实客户端验证环境**（这台机器没有可用的 Windows/macOS 客户端），结论只到「协议层测试全绿」。

## 9. 实施顺序建议

1. 名称校验硬化（独立、可立即验证、收益不限于 WebDAV）
2. `webdav_tokens` 表 + 应用密码 API（CRUD + Basic 鉴权插件）+ 独立失败计数
3. `DavListing` 游标列举器 + PROPFIND / OPTIONS
4. GET / HEAD（复用 `StoredFileContent`）
5. PUT 流式切块 + `complete(snapshotOnOverwrite)` + 覆盖不产生版本
6. MKCOL / MOVE / COPY / DELETE
7. 客户端：设置页的令牌管理（生成/列表/吊销/`read_only`）
8. 文档：api.md 的 `/dav` 章节 + roadmap 勾掉 WebDAV