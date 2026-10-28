# 安装与使用

[English](usage.en.md) | [简体中文](usage.md)

本文讲怎么拿到、安装、使用 BareZen-Drive 的各端。想先了解它是什么，看
[README.zh-CN.md](../README.md)；接口细节看 [api.md](api.md)。

## 一、一键部署服务器（推荐）

服务器是唯一必须部署的部分：它提供 API、文件存储，并直接托管 Web 客户端。一条命令完成部署：

```bash
curl -fsSL https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/install.sh | bash
```

脚本会：

1. 检查 Docker 与 Docker Compose。
2. 在 `/opt/barezen` 生成 `.env`——交互式询问端口，数据库密码与 JWT 密钥留空则自动生成（也可用
   `--dir`、`--port`、`--version` 参数跳过交互）。
3. 拉取 `ghcr.io/linanwanttodo/barezen-drive` 镜像（多架构：amd64 与 arm64 均可）。
4. 启动 PostgreSQL 16 与服务端两个容器，并等健康检查通过后输出访问地址。

安装完成后：

- 浏览器打开 `http://<服务器地址>:8080` 就是 Web 客户端。选了「域名 + HTTPS」的安装请打开
  `https://<域名>`：那种安装会把 `SERVER_PORT` 只绑到 `127.0.0.1`，明文端口不再对外，
  WebDAV 也不会跑在没有 TLS 的链路上。
- **第一件事：注册你的账号，然后到 设置 → 服务器 → 关闭「开放注册」**。私人网盘不该让所有人
  都能注册；关闭后登录页的注册入口自动消失，接口也会拒绝新的注册（错误码
  `REGISTRATION_DISABLED`）。想再加人再打开即可。

常用命令（都在安装目录 `/opt/barezen` 执行）：

```bash
docker compose pull && docker compose up -d   # 升级到最新镜像
docker compose logs -f server                 # 看日志
docker compose down                           # 停止（数据保留）
```

### .env 需要填什么

| 变量 | 必填 | 说明 |
|---|---|---|
| `JWT_SECRET` | 是 | 签发 JWT 的密钥，至少 32 字节。`openssl rand -base64 48` 生成，脚本可自动生成 |
| `POSTGRES_PASSWORD` | 是 | PostgreSQL 密码，脚本可自动生成 |
| `POSTGRES_DB` / `POSTGRES_USER` | 否 | 默认 `barezen` / `barezen` |
| `SERVER_PORT` | 否 | 对外端口，默认 8080；HTTPS 安装下只绑 127.0.0.1，对外由 Caddy 走 443 |
| `STORAGE_DIR` | 否 | 容器内文件目录，compose 里固定 `/data/storage` 并挂载到 `./data/storage` |
| `MAX_FILE_SIZE` | 否 | 单文件上限，默认 10 GiB |
| `UPDATE_MANIFEST_URL` | 否 | 更新清单地址，默认指向本仓库最新 Release 的 `update.json` |
| `GITHUB_TOKEN` | 否 | 可选，提高 GitHub 查询速率上限 |
| `STORAGE_BACKEND` | 否 | `local`（默认，文件存 `STORAGE_DIR`）或 `s3`（对象存储） |
| `S3_BUCKET` / `S3_REGION` | s3 时必填/否 | 桶名与区域，区域默认 `us-east-1` |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | s3 时必填 | 桶凭据（只放 `.env`，不要写进 compose 提交） |
| `S3_ENDPOINT` | 否 | 自建 MinIO 等网关地址，留空则用 AWS 区域默认端点 |
| `S3_PATH_STYLE` | 否 | `true` 表示桶名走 URL 路径（MinIO 需要），默认 `false` |

切到对象存储后 `STORAGE_DIR` 仍然必须可写：分块暂存与合并都在本地磁盘完成，只有成品 blob 进桶，所以
`./data/storage` 里只剩 `tmp/`，备份责任转移到桶本身（需要就开启桶版本控制与跨区域复制）。切换不会替你
搬数据：先把现有的 `blobs/` 与 `thumbs/` 两个前缀按同样的 key 复制进桶（`s3 sync`、`mc mirror` 都可以），
否则一换成 s3 后端，已上传的文件立刻取不到内容。

### 备份与恢复

一套备份要同时拿走两样东西，缺一不可：文件内容是 `./data/storage`（`blobs/` 成品、`thumbs/` 缩略图、
`tmp/` 分块暂存），元数据是 PostgreSQL（目录树、版本、收藏、回收站、分享、账号）。只备份数据库，
文件全丢；只备份目录，页面上什么都看不到。

数据库走 `pg_dump`（容器内自带客户端，不必在宿主机装）：

```bash
cd <安装目录>
docker compose exec -T db pg_dump -U barezen barezen | gzip > barezen-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

`-U` 和库名取自 `.env` 的 `POSTGRES_USER` / `POSTGRES_DB`，默认都是 `barezen`。升级前 `install.sh`
已会自动做一次同样的 dump，落到 `<安装目录>/backups/`。

升级后 `server` 起不来（启动 DDL 报错，`restart: unless-stopped` 会把它反复重启）按这个顺序处理：先用
`docker compose logs --tail 100 server` 找到那条 DDL 报错，再把镜像标签退回升级前的版本。schema 演进只
增列不改列，旧版本能直接读加过列的库，所以回滚不用动数据：

```bash
bash install.sh --dir <安装目录> --version <旧版本号>   # 只换标签，其余答案直接回车沿用 .env
```

真要回到升级前的数据状态，就用 `<安装目录>/backups/` 里那份 dump，按下面的恢复步骤灌回去。

文件目录用 `tar`，不必停服（正在进行中的分块上传在 `tmp/` 里，恢复时丢弃即可，不影响已完成的文件）：

```bash
tar -czf barezen-storage-$(date -u +%Y%m%d-%H%M%S).tar.gz -C <安装目录> data/storage
```

恢复到一台空机器：先把 `docker-compose.yml`、`.env` 与 `data/storage` 放好，数据库必须在服务器起来
**之前**灌回去，因为启动时会在空库上重建管理员账号：

```bash
docker compose up -d --wait db   # 只起数据库，等它就绪
gunzip -c barezen-xxx.sql.gz | docker compose exec -T db psql -U barezen -d barezen
docker compose up -d             # 再起服务器
```

`--wait` 不能省：空卷首次启动时 postgres 会先跑一遍 initdb、临时起一个实例做初始化再重启，不等就绪
直接灌 dump 会撞上「the database system is shutting down」。

目标库由 `docker compose up -d db` 按 `.env` 的 `POSTGRES_DB` 建好，恢复要落到一个空卷上：旧的 `pgdata`
卷留着不动，先把新卷名指向别处或 `docker compose down` 后删掉它，否则 dump 里的建表语句会和已有对象撞车。
备份本身要放到另一台机器或另一个桶：跟数据同盘的备份不算备份。每天一点钟定时：

```
0 1 * * * cd <安装目录> && docker compose exec -T db pg_dump -U barezen barezen | gzip > /backup/barezen.sql.gz
```

切到 `STORAGE_BACKEND=s3` 后，`./data/storage` 只剩 `tmp/`，备份责任转移到桶自身（开版本控制），
数据库那份照旧。

### 自动升级（可选，默认开）

`install.sh` 会在宿主机装一个每天 04:15（带 45 分钟随机抖动，避免一堆实例同时拉镜像）跑一次的
systemd 定时器：

```bash
systemctl status barezen-update.timer            # 装没装、下次什么时候跑
journalctl -u barezen-update.service -n 50       # 上次做了什么
sudo systemctl disable --now barezen-update.timer   # 停掉
```

它只跟**稳定版**：问本机 `/api/version`。服务器默认读 `releases/latest/download/update.json`
（`UPDATE_MANIFEST_URL` 可覆盖；只有读不到这份清单时才回落 GitHub 的 `releases/latest` API，
结果缓存 10 分钟）。清单里的 `version` 本身不过滤 pre-release，真正挡住测试版的是下一句：
版本号必须是纯 `x.y.z`，带 `-` 或 `+` 后缀的一律跳过。
判定为可升级后按固定顺序走：先把库 dump 到 `<安装目录>/backups/auto-<版本>-<时间>.sql.gz` → 改 compose
标签 → `docker compose pull server` → `up -d server` → 探 `/readyz`。HTTPS 安装的 compose 命令带
`-f docker-compose.caddy.yml`（把明文端口收回 loopback），这条也一起记在 `/etc/barezen-update.env`
里，升级重放同一条命令，不会把 8080 重新暴露到所有网卡。

三种情况它自己收场：dump 失败就**什么都不改**直接退出；`pull` 失败把标签退回去（连标签都退不回去时按
exit 2 处理，因为 compose 会指着一个没人运行的版本）；升级后 `/readyz`
一直 503 就把标签改回原版本再拉一次、再探一次，日志里写明回滚结果（脚本退出码：0 无事可做或已升好，
1 已回滚，2 回滚也失败需要人工，3 动手前就中止）。回滚只换镜像标签、不动数据——schema 只增列，
旧镜像读得懂新库。**哪天某个版本要改列类型或加约束，必须先对该版本关掉自动升级**，那时回滚不再免费。

回滚的前提是 compose 里钉着一个**具体版本**：`install.sh` 现在会把 `latest` 解析成当前发布版本再写进
compose（`--version` 显式指定时照旧）。旧的 `latest` 钉法没有回滚目标——改回去还是那个可变标签，等于
把刚失败的版本再拉一遍——所以遇到这种钉法脚本会记一行 `no rollback target` 并跳过当天的升级；重跑一次
`install.sh` 重新钉好即可。自动 dump 只保留最近 7 份（`backups/auto-*.sql.gz`），免得一台总是升级失败的
机器把数据盘塞满；`backups/barezen-*.sql.gz` 是 `install.sh` 手工升级时留的，不在此列。

**它是升级器，不是看门狗。** 实例本来就停着（宕机、镜像坏了）时，它连 `/api/version` 都问不到，
只会安静退出并记一条 `skipped (exit 0)`，第二天再来一次——不会把服务拉起来。要保活靠 compose 的
`restart: unless-stopped` 与你自己部署的监控。

宿主机上的那一对 systemd 单元与 `/etc/barezen-update.env` 是全局的：自动升级只按**一台**实例设计，
同一台机器上用第二个 `--dir` 装第二套服务端会接管前一套的定时器（拆除方向有归属检查，安装方向没有）。
需要多套实例时请把它们放在不同机器上。

重跑 `install.sh` 时那一问答「n」即可彻底拆掉单元文件（连脚本与 env 文件一起）；`.env` 里的
`AUTO_UPDATE` 是它的答案，非交互重跑会沿用旧值，不会悄悄改。拆除的前提是 `/etc/barezen-update.env`
还在、且里面记的 `BAREZEN_DIR` 仍是本安装目录——那份文件就是归属证据，被手工删掉时脚本不会替别的
实例拆单元（宁可留一个僵尸定时器，也不能关掉别人那台）。

手机端（Android）另有一条：登录后每天检查一次，遇到新的稳定版就在当前网络下把 APK 下好，按清单里的
sha256 与大小校验（清单没带哈希时这两项就只能省掉），然后发一条通知，点通知才进系统安装页。安装确认
是 Android 自己的，跳不过去。通知发不出去
时（Android 13+ 拒绝通知权限、或系统设置里关掉了本应用/本通道的通知）它**连下载都不做**：否则下好的
包没人知道，而第二天又会因为「已经下过」跳过这个版本，升级提示就永久丢了。把通知打开，第二天照常。
点了通知又把系统安装框取消的话，这次「已下载」的记录会一起清掉：下一次每日检查会重新下载并再提示一次
（设置页的手动「检查更新」也可以立刻重来）。

### 不用 Docker 的情况

从 Releases 下载 `BareZen-Drive-<版本>-server.tar.gz`（内嵌 Web 客户端，架构无关，需要 JDK 21），
解压后配置上面表中的环境变量，运行 `./bin/server`。数据库自备 PostgreSQL 16。

## 二、安装 Android 客户端

1. 在 [Releases](https://github.com/linanwanttodo/BareZen-Drive/releases) 下载
   `BareZen-Drive-<版本>-android-arm64-v8a.apk`（主流手机）。
2. 手机上打开安装，允许「未知来源应用」。
3. 打开 App，登录页填 `http://<服务器地址>:8080` 和账号密码。

各端功能一致：首页（服务器状态 + 相册 + 最近）、文件（上传/下载/重命名/移动/删除）、相册
（按月时间轴，上传照片落到「相册/<设备名>」）、分享、设置。

## 三、Web 客户端

无需安装。服务器起来后，浏览器打开 `http://<服务器地址>:8080`，与 Android 是同一套界面。

## 四、媒体库：自动备份、收藏、归档、版本与回收站

**相册自动备份（Android）**：在 传输中心 顶部打开「自动备份」开关，App 会把手机里新增、修改的照片
视频补传到服务器的「相册 / <设备名> / 分类」目录。拍照后几秒内就会入队（监听系统相册变更），六小时
一次的周期任务与每日全量对账只作兜底。可选「仅 Wi-Fi」避免走流量，可选「仅充电时同步」把备份限制
在接通电源时；电量过低时系统本来就不会执行。等待期间状态卡会写明具体原因（等待网络／需接通电源／
电量不足／未登录），不会让「待备份 N 项」干挂着不解释。随时点「立即同步」手动补
一次（手动触发会忽略上面两条限制，这是你本人的一次性覆盖）；「按相册选择」可以逐个勾掉不想备份的
相册（如截图、应用缓存相册），被排除的相册不计入「待备份」计数，勾回来会立刻重新排一次队；排除是
即时生效的，哪怕这次同步已经在跑——正在排队的每一项都会在上传前再核对一次排除状态，不会照着旧队列
把刚被你排除的相册继续传完。
实现方式是系统后台任务 + 前台进度通知，因此需要允许「照片与视频」读取权限和通知权限；进程被系统
回收后队列不丢，下次自动续传，单个文件失败最多重试 5 次（线性退避，每次 10 分钟），断网时早停以免
把重试次数烧光；不计费的网络（Wi-Fi）同时传 2 个文件，走流量时收窄到 1 个。

打开开关的那一刻会先弹出「选择要备份的相册」引导：只列出照片最多的几个相册（微信、Telegram 之类
动辄几个 G 的缓存相册不会在你不注意时整包上传），取消勾选的相册立即被排除，点「全部备份」则把列表里
的相册全部恢复为备份。这一步每个设备只问一次，之后想改可以随时进「按相册选择」完整列表调整（列表右上角有「全部备份」，一键
把所有相册恢复为备份）；若是读取
相册的权限没给，弹窗里不会列出任何相册，此时它不算答复过，之后打开传输中心的同步页还会再问一次。

**收藏**：在相册里点心形图标只看收藏；给照片收藏的入口是相册长按多选后的「收藏」、文件列表的
三点菜单，以及图片预览页底部的心形按钮。

**归档**：归档不等于移动位置，只是一个开关——归档后照片从时间轴、搜索和文件列表默认视图里消失，
但链接与分享照常。取消归档在 设置 → 媒体库 → 归档。入口同样是相册多选栏、文件三点菜单与预览页。

**回收站**：删除单个文件是软删除，文件进入回收站并保留 30 天，期间可「还原」，也可「彻底删除」或
「清空回收站」立刻释放空间；超过 30 天的条目由服务器清理任务自动物理删除。整棵文件夹的删除仍然是
不可撤销的物理删除。回收站入口在 设置 → 媒体库 → 回收站。被删文件的分享链接会同时被撤销，还原
文件不会自动重新发布链接，需要重新创建。

**文件版本**：同一目录里已有同名文件时，上传会先问你「覆盖」还是「跳过」。选择覆盖不会丢旧内容：
服务器把原来的内容存成一个历史版本，文件名、分享链接、收藏与归档标记都延续到新内容。入口在文件
三点菜单的「版本历史」，可以逐条恢复或丢弃；恢复是一次交换，被换下的当前内容同样进历史。每个文件
最多留 20 个版本，更老的会自动裁掉并回收其占用的空间。自动备份不做覆盖——同名不同内容的照片会以
失败记录进传输中心，等你确认，不会静默替换手机之外的任何东西。

## 五、检查更新怎么工作

每个客户端「设置 → 关于 → 检查更新」向**你连接的服务器**发请求，不直连 GitHub：

1. 服务端读取发布清单 `update.json`（挂在 Release 上的稳定地址，无限流），缓存 10 分钟。
2. 客户端比较服务端版本与自身版本，有新版本时按平台给出对应动作：
   - **Web**：提示「重新加载」——新产物由同一服务端托管，刷新即完成更新；服务端升级后已打开的
     页面启动时也会收到这个提示。
   - **Android**：提示「下载更新」，直接下载清单里的 APK 直链。
   - 桌面/iOS（预留）：将按平台列出对应安装包。
3. 清单里每个包都带 sha256 与大小；Release 页同时提供 `checksums.txt`。

这条手动检查始终可用；服务器端与 Android 的自动升级见第一节的「自动升级」小节。手机通知被关掉时，
自动那条什么都不做，手动检查就是唯一的入口。

`update.json` 结构（桌面与 iOS 启用后直接追加条目，无需改结构）：

```json
{
  "name": "BareZen-Drive",
  "version": "0.0.2",
  "apiVersion": 1,
  "releaseNotesUrl": "https://github.com/linanwanttodo/BareZen-Drive/releases/tag/v0.0.2",
  "assets": [
    {"platform": "android", "arch": "any", "kind": "apk", "url": "...", "sha256": "...", "size": 0},
    {"platform": "web", "arch": "any", "kind": "zip", "url": "...", "sha256": "...", "size": 0},
    {"platform": "server", "arch": "any", "kind": "tar.gz", "url": "...", "sha256": "...", "size": 0}
  ],
  "docker": {"image": "ghcr.io/linanwanttodo/barezen-drive", "tags": ["0.0.2", "latest"], "platforms": ["linux/amd64", "linux/arm64"]}
}
```

## 六、发新版本怎么操作

版本号只在 `gradle.properties` 的 `version=` 一处定义（Android `versionName`/`versionCode`、服务端、
更新清单全部由它派生）。发版三步：

```bash
# 1. 修改 gradle.properties：version=0.0.2
# 2. 提交并打标签
git tag v0.0.2 && git push origin master v0.0.2
# 3. 等 GitHub Actions 的 Pipeline 跑完：自动构建、推镜像、发 Release
```

Pipeline 会自动：跑测试 → 构建 Web/APK/服务端包 → 推送 amd64+arm64 镜像 → 生成
`update.json` 与 `checksums.txt` → 发布 GitHub Release。单个 job 失败用 "Re-run failed jobs"
只重跑失败部分，已构建的产物直接复用。

发**测试版**时在 GitHub 上把那一条 Release 勾成 "pre-release"：`releases/latest` 与它下面的
`update.json` 只指向正式版，自动升级便不会取到；版本号带 `-beta` 之类的后缀也会被前文的纯 `x.y.z`
规则再挡一次。正式发版则不要勾选。

## 七、桌面端与 iOS 现状（如实说明）

两端预留了平台接口与 CI 任务，但**当前不出安装包**：

- **桌面端**：`:app:desktopApp` 模块在，`settings.gradle.kts` 未启用该 target；需补齐一整套 `jvm`
  平台实现（目前只有 `AppUpdate.jvm.kt`）。JVM 目标在本机 Linux 就能编译验证，是最容易的下一步；
  补齐后 CI 自动出 `.deb`/`.rpm`。
- **iOS**：Xcode 工程与 `iosMain` 入口在，需补齐 iOS `actual`、启用 Apple target 并加 Darwin 版
  ktor 引擎。依赖无阻碍（`backdrop`、`shapes` 均有 iOS 产物）；Apple target 不能在 Linux 编译，
  只能靠 GitHub macOS runner 验证。

当前可实际部署使用的：**服务端、Web、Android**。桌面端功能需求可以用下一节的 WebDAV 挂载先行满足。

## 八、挂载为网络盘（WebDAV）

没有桌面客户端也能用。把 `/dav` 挂成网络盘，Finder / 资源管理器 / Nautilus 就能像本地目录一样读写，不必等 `:app:desktopApp` 补齐。

**操作**：Web 或 Android 客户端 -> 设置 -> 挂载与 WebDAV -> 新建挂载，填一个能认出这台设备的名字（如「公司 iMac」），得到一个 64 位应用密码。

**为什么是应用密码而不是账号密码**：挂载后**每个请求**都要鉴权一次，而账号密码是 bcrypt（cost 10，约 100ms/次），浏览一个目录就是几百次。应用密码是 32 字节随机、按 SHA-256 精确查（不做 bcrypt）—— 高熵没有离线爆破面，所以精确匹配是安全的。同时应用密码**不能**登录 Web UI、不能调任何 `/api/**` 接口，泄露的影响面被限制在网盘这一面。

**每台设备一个**。WebDAV 客户端会把密码明文存进配置文件或系统钥匙串，唯一能兜住泄露的方式是能只掐断一台设备而不动其他。可以在设置里吊销单个挂载，也可以把某个挂载设成只读（所有写方法返 403，防止挂载点误删整个盘）。

**挂载**（用户名是账号名，密码是刚生成的应用密码）：

- **macOS Finder**：前往 -> 连接服务器 -> `https://<你的域名>/dav`
- **Windows 资源管理器**：此电脑 -> 映射网络驱动器 -> `https://<你的域名>/dav`
- **Linux**：`sudo mount -t davfs https://<你的域名>/dav /mnt/xxx`

**必须用 HTTPS。** HTTP Basic 以 base64 明文传密码，且 Windows 客户端在明文 HTTP 上默认禁用 Basic。`install.sh` 的 Caddy 模式已经带 HTTPS。

### 四条限制（如实说明）

1. **没有锁。** `LOCK`/`UNLOCK` 返 501，而且刻意不在 `Allow` 里宣告（宣告了会让客户端以为锁可用，资源管理器拒绝打开它锁不了的文件、Office 拒绝保存）。**实际后果：用 Office 打开网盘上的文档，保存可能失败**，因为 Office 保存前要加锁。
2. **覆盖写不产生版本历史。** 挂载覆盖走 WebDAV 专用语义，不进版本历史表。被替换掉的 blob 会进回收站的宽限期队列（不会变成无主垃圾），但确实没有历史可查 —— 需要历史请用 Web UI。
3. **没有秒传。** 协议没有让客户端「先报哈希」的钩子，所以 WebDAV 上传永远走完整上传。大文件会比 Web 端慢。
4. **删除文件夹不可恢复。** 删除**文件**进回收站可恢复；删除**文件夹**是硬删除整棵子树，因为数据模型里文件夹没有软删除列（详见 api.md）。想保险请先移动而不是删除 —— WebDAV 客户端的删除通常没有确认对话框。
5. **未经真实客户端验证。** 本功能只验证到「协议层测试全绿」与「真实 HTTP 冒烟脚本全绿」（`scripts/dav_smoke.sh`）。开发环境没有可用的 Windows / macOS 客户端，所以「资源管理器与 Finder 实际挂得上」这句话没有被验证过。首次使用请先在测试目录里试一遍。

技术细节（方法表、属性集、错误码、两处对 RFC 的有意偏离）见 [api.md 的 WebDAV 章节](api.md#webdav挂载为网络盘)。

