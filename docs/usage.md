# 安装与使用

[English](usage.en.md) | [简体中文](usage.md)

本文讲怎么拿到 BareZen-Drive 的各端产物、怎么安装、怎么用。想先了解它是什么，看
[README.zh-CN.md](../README.zh-CN.md)；接口细节看 [api.md](api.md)。

## 一、你要部署哪一种形态

| 形态 | 适用场景 | 产物 | 拿到方式 |
|---|---|---|---|
| 服务器 + Web | 有一台自己的服务器（VPS / NAS / 树莓派） | Docker 镜像 或 服务端发行包 | GHCR 拉镜像，或 Releases 下 `BareZen-Drive-server.tar.gz` |
| Android 客户端 | 手机上用 | `BareZen-Drive-android-*.apk` | Releases 页下载 |
| Web 客户端 | 浏览器直接用，无需安装 | 由服务端直接托管 | 部署好服务端后打开 `http://<服务器>:8080` |
| 桌面客户端 | Windows / macOS / Linux 桌面 | 预留中，见文末说明 | 暂不可用 |
| iOS 客户端 | iPhone / iPad | 预留中，见文末说明 | 暂不可用 |

Android 和 Web 是同一套 Compose Multiplatform 界面，功能一致。服务端是唯一必须部署的部分：Web
客户端由它托管，Android 客户端连它的地址。

## 二、部署服务器（必须）

服务器提供 API、文件存储和 Web 客户端。两种方式二选一。

### 方式 A：Docker Compose（推荐）

最简单，镜像已在 GitHub Container Registry 上构建好。

1. 准备一台装了 Docker 和 Docker Compose 的机器，1 核 1 GB 内存即可。
2. 下载仓库里的两个文件（或 `git clone` 整个仓库）：

```bash
mkdir barezen && cd barezen
curl -fsSLO https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/docker-compose.yml
curl -fsSLO https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/.env.example
```

3. 生成配置并填写密钥：

```bash
cp .env.example .env
# 生成一个至少 32 字节的 JWT 密钥
openssl rand -base64 48
```

把 `openssl` 的输出填进 `.env` 的 `JWT_SECRET`，并给 `POSTGRES_PASSWORD` 设一个强密码：

```dotenv
JWT_SECRET=上一步生成的长随机串
POSTGRES_DB=barezen
POSTGRES_USER=barezen
POSTGRES_PASSWORD=你自己设的强密码
SERVER_PORT=8080
```

4. 启动：

```bash
docker compose up -d --build
```

如果本机拉不到镜像（大陆网络），把 `docker-compose.yml` 里的 `build: .` 换成预构建镜像：

```yaml
  server:
    image: ghcr.io/linanwanttodo/barezen-drive:latest
```

5. 验证：

```bash
curl -s localhost:8080/health          # {"status":"ok"}
curl -s localhost:8080/api/version     # 版本信息
```

6. 打开浏览器访问 `http://<服务器地址>:8080`，就是 Web 客户端。第一次使用在登录页切到「注册」建账号。

升级服务器：

```bash
docker compose pull          # 或 docker compose build --pull
docker compose up -d
```

升级后，已经在浏览器里打开的页面会提示「重新加载」，点一下即可用上新版本。

### 方式 B：服务端发行包（不用 Docker）

适合不想装 Docker、但机器上有 JDK 21 的场景。前提是自备一个 PostgreSQL 16（或兼容版本）。

1. 从 Releases 页下载 `BareZen-Drive-server.tar.gz` 并解压：

```bash
mkdir -p /opt/barezen && tar -xzf BareZen-Drive-server.tar.gz -C /opt/barezen
```

2. 设置环境变量并启动（服务端发行包已内嵌编译好的 Web 客户端）：

```bash
export SERVER_PORT=8080
export JDBC_URL="jdbc:postgresql://localhost:5432/barezen"
export DB_USER=barezen
export DB_PASSWORD=你的数据库密码
export JWT_SECRET="至少32字节的随机串"
export STORAGE_DIR=/opt/barezen/storage
/opt/barezen/server/bin/server
```

3. 用 systemd 常驻（可选）：新建 `/etc/systemd/system/barezen.service`：

```ini
[Unit]
Description=BareZen-Drive server
After=network.target postgresql.service

[Service]
User=barezen
EnvironmentFile=/opt/barezen/barezen.env
ExecStart=/opt/barezen/server/bin/server
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

把上面的环境变量写进 `/opt/barezen/barezen.env`（每行一个 `KEY=value`），然后：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now barezen
```

### 服务端环境变量总表

| 变量 | 必填 | 说明 |
|---|---|---|
| `JWT_SECRET` | 是 | 签发 JWT 的密钥，至少 32 字节 |
| `JDBC_URL` | 是 | PostgreSQL 连接串 |
| `DB_USER` / `DB_PASSWORD` | 是 | 数据库账号密码 |
| `SERVER_PORT` | 否 | 监听端口，默认 8080 |
| `STORAGE_DIR` | 否 | 文件与缩略图存放目录，默认 `./data/storage` |
| `MAX_FILE_SIZE` | 否 | 单文件上限，默认 10 GiB |
| `UPDATE_REPO_URL` | 否 | 检查更新时查询的仓库，默认本项目 |
| `GITHUB_TOKEN` | 否 | 提高 GitHub 发布查询速率上限 |

数据都在两个地方：PostgreSQL 里的元数据，和 `STORAGE_DIR` 里的文件内容。**备份这两个即可。**

## 三、安装并使用 Android 客户端

1. 在 Releases 页下载 `BareZen-Drive-android-release-unsigned.apk`（签名版）或
   `BareZen-Drive-android-debug.apk`（调试版）。
2. 手机上打开该 APK 安装。系统会提示「未知来源应用」，需要允许安装。
   - 说明：release 包当前是未签名的（`-unsigned`），部分系统可能需要先签名才能安装；调试包可直接
     安装。发布正式签名包需要仓库维护者配置签名密钥。
3. 打开 App，在登录页填服务器地址和账号：
   - 服务器地址填 `http://<服务器IP>:8080`，例如 `http://192.168.1.10:8080`。
   - 第一次用先切到「注册」建账号，之后用同一账号登录。
   - App 默认允许 http 明文流量，便于内网自托管；暴露公网建议在反向代理上加 HTTPS。
4. 用起来的主要位置：
   - 首页：服务器状态面板 + 相册板块 + 最近文件。
   - 文件：浏览文件夹、上传、下载、重命名、移动、删除、新建文件夹。
   - 相册：按月份的照片时间轴，点开可预览；在这里上传的照片会落到「相册/<设备名>」目录。
   - 设置：主题、语言、壁纸、**检查更新**、开源说明、退出登录。

## 四、使用 Web 客户端

不需要安装。服务端起来后，浏览器打开 `http://<服务器地址>:8080` 即可，登录方式和 Android 一样。
Web 端支持上传、下载、预览图片/视频/文本/PDF（视频与 PDF 在新标签页打开）、分享管理。

## 五、检查更新怎么工作

每个客户端的「设置 → 关于 → 检查更新」都会向**你连接的服务器**发一次请求，而不是各自去连
GitHub：

- 服务端查询上游最新发布并缓存，客户端拿服务端版本和自身版本比较。
- 服务器端升级：`docker compose pull && docker compose up -d`；已打开的 Web 页面会提示重新加载。
- Android 端：提示有新版本时点「打开发布页」，到 Releases 下载新 APK 安装。
- 因此处于内网、无法访问 GitHub 的客户端也能正常检查更新。

## 六、各端产物一览

| 产物 | 文件 | 说明 |
|---|---|---|
| Android | `BareZen-Drive-android-release-unsigned.apk` | 未签名 release 包 |
| Android | `BareZen-Drive-android-debug.apk` | 调试包，可直接安装 |
| Web | `BareZen-Drive-web.zip` | 静态产物；解压后把内容拷到服务端 `resources/web`，或直接用服务端内嵌版本 |
| 服务端 | `BareZen-Drive-server.tar.gz` | 含内嵌 Web 客户端的可运行发行包（需 JDK 21） |
| 容器 | `ghcr.io/linanwanttodo/barezen-drive:latest` | Docker 镜像，含服务端 + Web |

## 七、桌面端与 iOS 的现状（如实说明）

这两端在代码里预留了平台接口和 CI 任务，但**当前还不能出可安装的包**：

- **桌面端**：`:app:desktopApp` 模块和入口代码在，但 `settings.gradle.kts` 里未启用该 target；
  启用它需要补齐一整套 `jvm` 平台实现（文件选择、剪贴板、壁纸、媒体播放、PDF 预览、偏好存储等，
  目前只预置了 `AppUpdate.jvm.kt`）。补齐后 CI 的 desktop 任务会自动开始出包。
- **iOS**：Xcode 工程与 `iosMain` 入口在，剩余工作是补齐 iOS 的 `actual` 实现，并在 shared 模块启用
  `iosArm64` / `iosSimulatorArm64` target 与 Darwin 版 ktor 引擎。依赖上没有阻碍——共享 UI 用到的
  `backdrop`、`shapes` 都已发布 iOS 产物。注意 Apple target 无法在 Linux 上编译，必须在 macOS 上
  构建，因此 GitHub 的 macOS runner 是验证这一端的唯一途径。

也就是说：现在能实际部署使用的是**服务端、Web、Android**；桌面端和 iOS 已铺好路但尚未产出安装包。
