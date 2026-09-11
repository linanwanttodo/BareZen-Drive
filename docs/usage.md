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

- 浏览器打开 `http://<服务器地址>:8080` 就是 Web 客户端。
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
| `SERVER_PORT` | 否 | 对外端口，默认 8080 |
| `STORAGE_DIR` | 否 | 容器内文件目录，compose 里固定 `/data/storage` 并挂载到 `./data/storage` |
| `MAX_FILE_SIZE` | 否 | 单文件上限，默认 10 GiB |
| `UPDATE_MANIFEST_URL` | 否 | 更新清单地址，默认指向本仓库最新 Release 的 `update.json` |
| `GITHUB_TOKEN` | 否 | 可选，提高 GitHub 查询速率上限 |

数据备份只需要两样：`./data/storage` 目录（文件内容）和 PostgreSQL 数据卷（元数据）。

### 不用 Docker 的情况

从 Releases 下载 `BareZen-Drive-<版本>-server.tar.gz`（内嵌 Web 客户端，架构无关，需要 JDK 21），
解压后配置上面表中的环境变量，运行 `./bin/server`。数据库自备 PostgreSQL 16。

## 二、安装 Android 客户端

1. 在 [Releases](https://github.com/linanwanttodo/BareZen-Drive/releases) 下载
   `BareZen-Drive-<版本>-android-arm64-v8a.apk`（主流手机；老设备选 armeabi-v7a，模拟器选 x86_64）。
2. 手机上打开安装，允许「未知来源应用」。
3. 打开 App，登录页填 `http://<服务器地址>:8080` 和账号密码。

各端功能一致：首页（服务器状态 + 相册 + 最近）、文件（上传/下载/重命名/移动/删除）、相册
（按月时间轴，上传照片落到「相册/<设备名>」）、分享、设置。

## 三、Web 客户端

无需安装。服务器起来后，浏览器打开 `http://<服务器地址>:8080`，与 Android 是同一套界面。

## 四、检查更新怎么工作

每个客户端「设置 → 关于 → 检查更新」向**你连接的服务器**发请求，不直连 GitHub：

1. 服务端读取发布清单 `update.json`（挂在 Release 上的稳定地址，无限流），缓存 10 分钟。
2. 客户端比较服务端版本与自身版本，有新版本时按平台给出对应动作：
   - **Web**：提示「重新加载」——新产物由同一服务端托管，刷新即完成更新；服务端升级后已打开的
     页面启动时也会收到这个提示。
   - **Android**：提示「下载更新」，直接下载清单里的 APK 直链。
   - 桌面/iOS（预留）：将按平台列出对应安装包。
3. 清单里每个包都带 sha256 与大小；Release 页同时提供 `checksums.txt`。

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

## 五、发新版本怎么操作

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

## 六、桌面端与 iOS 现状（如实说明）

两端预留了平台接口与 CI 任务，但**当前不出安装包**：

- **桌面端**：`:app:desktopApp` 模块在，`settings.gradle.kts` 未启用该 target；需补齐一整套 `jvm`
  平台实现（目前只有 `AppUpdate.jvm.kt`）。JVM 目标在本机 Linux 就能编译验证，是最容易的下一步；
  补齐后 CI 自动出 `.deb`/`.rpm`。
- **iOS**：Xcode 工程与 `iosMain` 入口在，需补齐 iOS `actual`、启用 Apple target 并加 Darwin 版
  ktor 引擎。依赖无阻碍（`backdrop`、`shapes` 均有 iOS 产物）；Apple target 不能在 Linux 编译，
  只能靠 GitHub macOS runner 验证。

当前可实际部署使用的：**服务端、Web、Android**。
