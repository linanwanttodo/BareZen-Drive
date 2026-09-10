# 本地开发指南

环境要求：JDK 21（`/usr/lib/jvm` 下查找）、Docker（可选，用于 PG 与部署验证）、Android SDK（构建 Android 目标）。

## 服务器端

### 本地运行

```bash
# 方式一：连接本地 PostgreSQL
export SERVER_PORT=8080
export JDBC_URL="jdbc:postgresql://localhost:5432/barezen"
export DB_USER=barezen
export DB_PASSWORD=你的密码
export JWT_SECRET="至少32字节的随机串"   # openssl rand -base64 48
export STORAGE_DIR=./data/storage
./gradlew :server:run
```

```bash
# 方式二：用 Docker 起一个本地 PG
docker run -d --name barezen-pg -p 5432:5432 \
  -e POSTGRES_DB=barezen -e POSTGRES_USER=barezen -e POSTGRES_PASSWORD=barezen \
  postgres:16-alpine
```

启动后 `curl localhost:8080/health` 应返回 `{"status":"ok"}`。测试库用 H2 PostgreSQL 兼容模式内存库（`MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`），无需本地 PG。

### 测试

```bash
./gradlew :server:test --rerun-tasks --console=plain            # 服务端测试（67 用例）
./gradlew :core:allTests --rerun-tasks --console=plain          # DTO 序列化测试（3 用例）
./gradlew :app:shared:testAndroidHostTest --console=plain       # 共享模块测试（29 用例）
./gradlew :app:shared:compileKotlinWasmJs --console=plain       # wasm 编译门
```

### 一键部署（生产）

```bash
cp .env.example .env    # 填写 JWT_SECRET（>=32 字节）与 POSTGRES_PASSWORD
docker compose up -d --build
curl -s localhost:8080/health
```

构建说明：Dockerfile 三段构建，第一段编译 Web 客户端 wasm 产物并拷入 server 镜像。容器内 gradle 需要联网下载依赖（首次约 15 分钟）；gradle:9.7.1-jdk21 镜像已补装 libatomic1（Node.js 依赖）。

## 客户端

### Android

```bash
./gradlew :app:androidApp:installDebug          # 装到已连接的设备
./gradlew :app:androidApp:assembleDebug         # 仅出包：app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

App 内登录页填服务器地址（如 `http://192.168.1.10:8080`）+ 账号密码。

### Web（wasmJs）

```bash
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun     # 开发运行（自动开浏览器）
./gradlew :app:webApp:wasmJsBrowserDistribution       # 生产产物：app/webApp/build/dist/wasmJs/productionExecutable/
```

### 共享模块测试

```bash
./gradlew :app:shared:testAndroidHostTest --console=plain   # 全部共享模块测试（含 commonTest）
./gradlew :app:shared:compileKotlinWasmJs --console=plain   # wasm 编译门
```

## 构建减重与镜像

- Gradle 依赖走 Aliyun 镜像，wrapper 走腾讯镜像（`gradle/wrapper/gradle-wrapper.properties` 与 `settings.gradle.kts`）。
- KMP target 精简：core = jvm + wasmJs + android；shared = android + wasmJs；webApp 仅 wasmJs；desktopApp/iOS 源码保留但未启用。
- 依赖白名单见 `gradle/libs.versions.toml`，新增依赖需先登记。

## 代码约束

- 代码文件零 emoji、零装饰性 unicode（注释纯 ASCII；中文只出现在 UI 文案与错误消息字符串）。
- UI 无渐变、无 hover 效果，遵循腾讯 UI 设计规范；图标一律 Material Icons 矢量图标。
- 每个 Task 独立 commit；测试不绿不提交。
