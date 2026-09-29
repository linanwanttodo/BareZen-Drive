# 阶段一：全面优化审查 + 全页面 UI 打磨 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 并行审查全仓库产出分级问题清单，修复已确认 bug（壁纸缺失、暗色黑字），并按「OneDrive/百度网盘/夸克」简洁基调完成全页面 UI 打磨。

**Architecture:** 先读后修——4 条审查线只读产出清单 → 用户勾选 → 先修确定性 bug（根容器壁纸 + 主题色），再按页面实施打磨（外壳先行），最后落勾选的审查修复项。壁纸 bug 根因：`WallpaperLayer` 只在 `MainShell.kt` 内绘制，pushed 栈页面（设置/传输等）不可达；修复方案是把壁纸层上提到 `App.kt` 根 Box。

**Tech Stack:** Kotlin Multiplatform + Compose Multiplatform（共享 UI）、Ktor 3 服务端、PostgreSQL、Gradle 9、Web wasmJs 目标作为截图验证载体。

**规格来源:** `docs/superpowers/specs/2026-09-23-audit-and-ui-polish-design.md`

## Global Constraints

- **视觉禁止项**：❌ 悬浮渐变 ❌ 发光描边 ❌ 花哨毛玻璃特效 ❌ 动画堆砌（不新增此类效果；存量 glassBlur 开关不在本阶段移除）
- **字体阶梯（仅 4 级）**：页标题 20sp / 区块标题 16sp 中粗 / 正文 14sp / 辅助 12sp 灰
- **间距**：页边距 16dp、元素间距 8dp、列表行高 56-64dp
- **圆角**：卡片 12dp、按钮 8dp；阴影(1-2dp)与 1px 分割线二选一全局统一
- **色彩**：背景纯色；品牌色仅用于主按钮、选中态、进度条、分类图标
- **交互**：长按多选→底部操作栏；顶栏滚动后显底色；列表加载用骨架屏；空状态=图标+一句话+主按钮；所有可点元素有按下态
- **git**：`docs/superpowers/**` 下所有文件（含本计划）**不 commit、不推送、不加入 .gitignore**；代码改动正常按任务提交
- **质量门（每任务收尾必过）**：`./gradlew :server:test --rerun-tasks --console=plain`、`./gradlew :core:allTests --rerun-tasks --console=plain`、`./gradlew :app:shared:testAndroidHostTest --console=plain`、`./gradlew :app:shared:compileKotlinWasmJs --console=plain`
- **环境**：JDK 21；服务端本地跑需 Docker PG（`docker run -d --name barezen-pg -p 5432:5432 -e POSTGRES_DB=barezen -e POSTGRES_USER=barezen -e POSTGRES_PASSWORD=barezen postgres:16-alpine`）

---

### Task 1: 本地验证环境（服务端 + Web 客户端 + 测试账号）

**Files:**
- 无代码改动；产出：运行中的 `localhost:8080`（API+静态托管）与可截图的 Web UI

**Interfaces:**
- Consumes: 无
- Produces: `BASE=http://localhost:8080`、测试账号 `uitest / uipassword123`（后续所有截图任务与子代理复现依赖它）

- [x] **Step 1: 起本地 PostgreSQL**

```bash
docker run -d --name barezen-pg -p 5432:5432 \
  -e POSTGRES_DB=barezen -e POSTGRES_USER=barezen -e POSTGRES_PASSWORD=barezen \
  postgres:16-alpine
```

- [x] **Step 2: 后台启动服务端**（**计划偏离①：端口 8081——8080 被用户另一项目 BareZen_OA 的 bootRun 占用**）

```bash
export SERVER_PORT=8081
export JDBC_URL="jdbc:postgresql://localhost:5432/barezen"
export DB_USER=barezen
export DB_PASSWORD=barezen
export JWT_SECRET="$(openssl rand -base64 48)"
export STORAGE_DIR=/tmp/opencode/barezen-storage
mkdir -p "$STORAGE_DIR"
./gradlew :server:run   # 后台运行
```

- [x] **Step 3: 健康检查**

Run: `curl -s localhost:8081/health`
Expected: `{"status":"ok"}`

- [x] **Step 4: 注册测试账号**

```bash
curl -s -X POST localhost:8081/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"uitest","password":"uipassword123"}'
```
Expected: 200/201 且返回 token JSON；重复注册返回 409（第二次跑本任务时跳过）

- [x] **Step 5: 构建 Web 产物并同源托管**（**计划偏离②：服务端无 CORS 配置，webpack dev server 跨源会被浏览器拦；改走仓库既有惯例——dist 入 `server/src/main/resources/web/` 由 StaticWeb 同源托管。UI 迭代循环 = 重建 dist → 拷贝 → 重启服务端（StaticWeb 启动时枚举+永久缓存资产，必须重启）**）

```bash
./gradlew :app:webApp:wasmJsBrowserDistribution
rm -rf server/src/main/resources/web
cp -r app/webApp/build/dist/wasmJs/productionExecutable server/src/main/resources/web
# 重启 :server:run 后访问 http://localhost:8081
```
Expected: 浏览器打开 `localhost:8081` 可登录 uitest 账号进入首页

---

### Task 2: 并行派出 4 条审查线（只读，不改代码）

**Files:**
- Create: `docs/superpowers/drafts/audit-01-performance.md`
- Create: `docs/superpowers/drafts/audit-02-techdebt.md`
- Create: `docs/superpowers/drafts/audit-03-security.md`
- Create: `docs/superpowers/drafts/audit-04-ux.md`

**Interfaces:**
- Consumes: Task 1 环境（④ 需要登录走查）
- Produces: 4 份草稿，Task 3 汇总为 `audit-findings.md`

- [x] **Step 1: 同时（并行）派出 4 个 subagent**，每个 prompt 必须包含以下全文指令：

**① 性能线** → 写 `docs/superpowers/drafts/audit-01-performance.md`：
> 只读审查 /home/lin/All_projects/Javaproject/BareZen-Drive/server/src/main/kotlin 全部路由与服务层。逐接口查：N+1 查询、缺索引（对照 db/Tables.kt）、全表扫、大文件与并发路径内存峰值、1C/1G 下线程池与连接池配置、无谓全量读、每请求重复 IO。每条输出格式：`- [严重/次要/建议] 【①性能】文件名:行号 → 问题 → 影响 → 建议修法 → 改动量(S/M/L)`。按严重分组。不修改任何文件。

**② 技术债线** → 写 `docs/superpowers/drafts/audit-02-techdebt.md`：
> 只读审查整个仓库。逐条核实 docs/roadmap.md「已知的 v0.0.1 技术债」与 architecture.md「已知限制」里每条的现状（孤儿 blob 竞态、Flyway 未迁移、MAX_FILE_SIZE 未强制、句柄关闭时机、并行上传进度单槽位、缩略图 PUT 幂等跳过、Material 图标弃用警告、面包屑无截断等），标注每条【仍存在/已解决/部分解决】；另查新问题：吞异常（runCatching 无日志）、资源泄漏、边界条件缺失。输出格式同性能线但前缀【②技术债】。不修改任何文件。

**③ 安全线** → 写 `docs/superpowers/drafts/audit-03-security.md`：
> 只读审查 server/ 与 Web 客户端 token 存储。重点：改文件/文件夹 id 能否读写他人资源（越权，核对 system/OwnerGuard.kt 覆盖范围）、路径穿越（文件名/存储 key）、限流绕过（api/Throttle.kt）、JWT 与 refresh 轮换缺陷（auth/）、签名 URL 时效与撤销即时性（storage/、ShareService）、分享链接越权、上传会话归属校验、Web 端 localStorage token（XSS 暴露面）。输出格式同前缀【③安全】。不修改任何文件。

**④ 客户端体验线** → 写 `docs/superpowers/drafts/audit-04-ux.md`：
> 只读审查 app/shared/src/commonMain + androidMain，可登录 http://localhost:8080（uitest/uipassword123）在 Web 端实际走查。查：启动路径开销（App.kt 首帧做了什么）、列表分页与全量重排、缩略图缓存策略（ThumbnailLoader）、滚动中 recomposition 风险（不稳定 lambda、顶层 remember 缺失）、上传下载错误反馈与重试、加载/空/错误三态完整性（逐页面核对）、对话框与吐司统一性。输出格式同前缀【④体验】。不修改任何文件。

- [x] **Step 2: 等待 4 个子代理全部完成**

Expected: 4 份草稿文件存在且每份 ≥5 条（性能/技术债/安全/体验各线，少于 5 条的线要求该代理补充）

- [x] **Step 3: 交叉查重**

Run: `grep -h '^-' docs/superpowers/drafts/audit-0*.md | sort | uniq -d`（近似查重后人工合并同一问题的重复条目）
Expected: 重复条目已合并进对应主条目

---

### Task 3: 汇总分级审查清单

**Files:**
- Create: `docs/superpowers/specs/2026-09-23-audit-findings.md`

**Interfaces:**
- Consumes: Task 2 的 4 份草稿
- Produces: 用户勾选用的唯一清单；条目 ID 沿用 `【线编号】` 前缀，Task 9 按 ID 实施

- [x] **Step 1: 按严重程度分组合并**

结构：`## 严重` / `## 次要` / `## 建议`，每组内按模块排序；条目格式保持草稿原格式；roadmap 已列债务核实现状后并入并在条目尾部注明 `(roadmap 已列, 现状: …)`

- [x] **Step 2: 自审**

检查：无占位符、无重复、每条都有「建议修法 + 改动量」、总条目数 ≥ 15

- [x] **Step 3: 不提交**

确认 `git status` 中 `docs/superpowers/` 仍为 untracked（`??`），绝不 `git add`

---

### Task 4: UI 走查截图 + 打磨清单

**Files:**
- Create: `/tmp/opencode/ui-shots/` 下各页浅/深双主题截图
- Create: `docs/superpowers/specs/2026-09-23-ui-polish-checklist.md`

**Interfaces:**
- Consumes: Task 1 环境（Web 客户端 + 登录态）
- Produces: 按页面三行式清单（现状/改法/归类），Task 5 用户勾选

- [x] **Step 1: 截图全部页面（浅色 + 深色各一遍）**（**2026-09-26 完成 29 张 → `~/.cache/barezen-ui-shots/`**；浅色 15 + 深色 8 + 壁纸开关对照 4 + 弹层 4。未能覆盖：#8 复现条件、#11 时间线空白页二次复现、#13 视图切换入口、设置页内无「备份相册」入口）

用浏览器工具打开 Web 客户端，注册登录后逐页截图：登录(登出态)、首页、文件（列表+网格+多选态）、文件夹内、相册（时间轴+瀑布流+进入集合）、预览、传输中心（上传/相册两个 lane）、设置、备份相册、回收站、分享管理、对话框（重命名/删除确认）、吐司触发。深色切换：设置页主题切「深色」后再走一遍。
Expected: `/tmp/opencode/ui-shots/` 下每个页面有 `-light.png` 与 `-dark.png`

**计划偏离③：截图载体 = 真浏览器桌面会话（headless 像素管线不可信已弃用）；走查受 Review 面板可见性与 a11y 语义树塌缩干扰。浅色轮主页面（登录/登出/首页/文件交互/相册三态/对话框）已完成，清单主体已先行产出（Step 2），设置栈/回收站/分享/吐司/深色轮补拍后增补清单再勾选。**

**计划偏离④：弹层（行菜单/对话框/吐司）打开后 a11y 语义树会塌缩成「僵尸弹层」——只剩弹层节点、底层页面节点全丢，Escape/JS 事件/等待/真实点击均无法软恢复（实测 4 类手段全失效），唯一确定性恢复 = 重载标签页（约 6s，最坏 90–150s）。因此每轮拍摄顺序固定为「无弹层页面 → 弹层页收尾」，弹层拍摄放最后。**

**计划偏离⑤（2026-09-25）：harness location 重启连带清空 `/tmp/opencode`（`ui-shots/` 全部存档 + `barezen-storage` 种子文件内容）并停掉 PG 容器与服务端；PG 数据卷幸存（2 用户/5 目录/5 文件行全在）。恢复手段 = `docker start barezen-pg` + 服务端改用 `setsid nohup` 脱离 harness shell 生命周期（避免再次被连带 kill）；浏览器 profile 同被清空 → localStorage 令牌失效，需重新登录（uitest）。UI 修复代码一行未动，重拍仍是合法「改前」基线。⚠ Task 9 验收拍「改后」前必须先回填 storage 文件内容，否则缩略图类条目无法验证。**

- [x] **Step 2: 对照 Global Constraints 逐页写三行式条目**（**已产出 28 条**：22 条主体 + 设置栈实测增补 6 条，含回收站/分享管理两条实测修正；深色轮若再发现问题继续增补，不影响先勾选）**

分组页面：登录 · 首页 · 文件 · 相册 · 预览 · 传输中心 · 设置 · 回收站 · 分享管理 · 对话框与吐司。每条：
```
【页名】
- 现状：（截图可见的具体问题，含违反而条编号）
- 改法：（具体到组件/参数）
- 归类：壁纸 bug / 深色模式 / 打磨
```
已确认两条必录：①设置/传输页无壁纸 ②暗色下文件夹黑字。

- [x] **Step 3: 不提交**

同 Task 3 Step 3。**本轮起截图改存 `~/.cache/barezen-ui-shots/`（不入库、不受 `/tmp` 清空影响）**。

**计划偏离⑥：截图载体最终改为无头 Playwright MCP**——桌面浏览器窗口反复不可见（`needs a visible tab`），用户选择切换。实测结论：Compose Web 是 **canvas 渲染 + DOM 语义层**，`browser.*` 的 ref 点击在真浏览器可用，但无头下 `playwright_click` 因语义层 `pointer-events:none` 过不了可点性检查；改用 `playwright_evaluate` **向 canvas 派发 pointerdown/up 事件序列**即可正常驱动全部交互（rail/顶栏/行/对话框/输入框均可）。两个关键坑：① 滚轮必须**广播到 shadow root 内所有元素**（72 个）才能命中可滚动容器，单发到 canvas 无效；② **弹层（行菜单/对话框）打开后语义树只剩弹层节点**，关不掉时只能重载页面恢复。早前「无头像素管线不可信」的判断被推翻——无头 canvas 渲染像素级正常。

---

### Task 5: 【GATE】用户勾选两份清单

**Interfaces:**
- Consumes: Task 3 `audit-findings.md` + Task 4 `ui-polish-checklist.md`
- Produces: `docs/superpowers/specs/2026-09-23-selected-items.md`（勾选项列表，Task 8/9 的输入）

- [x] **Step 1: 把两份清单路径交给用户，等勾选结果**（**2026-09-26 用户勾选：UI 29 条全修 + 审查 严重16+次要54 = 70 条；建议 44 条不修**）
- [x] **Step 2: 写 selected-items.md** → `docs/superpowers/specs/2026-09-23-selected-items.md`（UI 按页面分组 + 审查 70 条紧凑索引 + 9 个批次的执行顺序）
- [x] **Step 3: 未勾条目** → 建议 44 条 + #8（无法复现）+ #22（误判撤销）已记入「暂不修」

---

### Task 6: 修复壁纸不覆盖 pushed 页面（设置/传输等）✅ 已完成（commit `7290672`）

**实施记录（2026-09-26）：**
- 根因三层：① `WallpaperLayer` 画在 `MainShell` 内，pushed 页由 `App.kt` backstack 在 shell 外渲染；② 桌面端 `NavigationSuiteScaffold` 的 `containerColor` 默认不透明，壁纸上提后被它盖住（改前壁纸画在它里面才可见）；③ 6 个 pushed 页用裸 `Scaffold`（默认不透明背景）——传输中心/分享管理/备份相册/开源说明/用户管理/预览
- 改动：壁纸层上提 `App.kt` 根 Box；`MainShell` 去掉 `wallpaperBitmap` 参数与 3 处绘制 + scaffold 设 `containerColor = Transparent`；6 页补 `containerColor = surface.copy(LocalPanelAlpha)`
- **偏离设计稿的「85% 纯色遮罩」**：实测叠加后壁纸在 tab 页被洗白（与改动前观感不一致），而各页面自带的 0.6 面板已提供可读性下限 → 改为不加根遮罩。若后续认为 pushed 页文字对比度不足，再按页补遮罩而不是全局加
- 验证：深色下 tab 页（首页/文件/搜索）与 pushed 页（设置/传输）均透出壁纸；tab 页 PNG 体积与改动前基线一致（171KB vs 168KB）= 无回退；四条质量门全绿

**Files:**
- Modify: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/App.kt`（约 185 行根 Box；362 行 MainShell 调用处）
- Modify: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/shell/MainShell.kt`（113/116/152/231/248 行）
- Reuse: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/shell/WallpaperLayer.kt`（不改）

**Interfaces:**
- Consumes: `WallpaperLayer(imageBitmap: ImageBitmap?)`、`wallpaperEnabled`、`wallpaperBitmap`（App.kt 129-131 行状态）
- Produces: 壁纸层挂到根容器，全栈页面可见；MainShell 不再自绘壁纸

- [x] **Step 1: 写失败验证（截图基线）**

打开设置页与传输页截图存 `/tmp/opencode/ui-shots/settings-wallpaper-before.png`、`transfers-wallpaper-before.png`
Expected: 无壁纸（纯背景色）——即 bug 复现

- [x] **Step 2: 根容器挂壁纸**

App.kt 根 Box 改为背景之上、内容之下绘制壁纸层（在 `Box(Modifier.fillMaxSize().background(scheme.background))` 内第一个子项）：

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .background(scheme.background),
) {
    WallpaperLayer(wallpaperBitmap.takeIf { wallpaperEnabled })
    // ……原有全部子内容不动……
```

- [x] **Step 3: 移除 MainShell 内自绘，避免双层壁纸**

MainShell.kt 删除 152/231/248 行三处 `WallpaperLayer(wallpaperBitmap)` 调用；删除参数 `wallpaperBitmap: ImageBitmap?`（116 行）；App.kt 362 行调用处删除 `wallpaperBitmap = wallpaperBitmap.takeIf { wallpaperEnabled },` 实参。
注意：保留 `WallpaperLayer` 自带的纯色回退（无壁纸时的 themed background）逻辑——若 MainShell 三处是「tab 页背景承担者」，移除后确认 tab 页背景仍由根 Box `scheme.background` + 壁纸层覆盖（WallpaperLayer 无图时画纯色），语义等价。

- [x] **Step 4: 验证 pushed 页面遮罩可读性**

设置页 `containerColor = surface.copy(alpha = LocalPanelAlpha.current)` 已是半透明面板，壁纸应透出；截图对比 settings/transfers 页：Expected 壁纸可见且文字对比度足够（不足处按规格给内容区叠 `scheme.background.copy(alpha = 0.85f)` 遮罩层）

- [x] **Step 5: 双主题回归截图**

首页/文件/相册三页浅深各一张，确认 tab 页壁纸与改动前一致（无双层加深、无丢失）
Run: 浏览器截图人工比对 before/after

- [x] **Step 6: 质量门**

Run: `./gradlew :app:shared:compileKotlinWasmJs --console=plain && ./gradlew :app:shared:testAndroidHostTest --console=plain`
Expected: 全绿

- [x] **Step 7: 提交**

```bash
git add app/shared/src/commonMain/kotlin/com/linan/barezen_drive/App.kt \
        app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/shell/MainShell.kt
git commit -m "fix: 壁纸层上提至根容器，设置/传输等栈页面全量透出"
```

---

### Task 7: 修复暗色模式文件夹文字黑字

**Files:**
- Modify: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/screens/files/FilesScreen.kt`（1311、1858 行两处 `Text(folder.name…)`；若走查发现 768/1067 关联 tile 同病一并修）
- Test: 无独立单测载体（Compose UI），验证走截图 + wasm 编译门

**Interfaces:**
- Consumes: `MaterialTheme.colorScheme.onSurface`
- Produces: 所有文件夹名 Text 在深色下用主题 onSurface 色

- [ ] **Step 1: 复现**

Web 端切深色，进含文件夹的目录与「移动到」文件夹选择弹窗，截图 `/tmp/opencode/ui-shots/dark-folder-text-before.png`
Expected: 文件夹名黑字不可见（bug 复现）；若两处 1311/1858 实际可见，则用浏览器 devtools/继续排查承载容器的 contentColor 来源并在结果中记录真正根因

- [ ] **Step 2: 定位根因**

Run: `grep -n 'Text(folder.name' app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/screens/files/FilesScreen.kt`
并检查这些 Text 所在容器是否在 Surface/Scaffold 的 contentColor 之外（如 Box 自绘背景、AlertDialog 之外的手写容器）

- [ ] **Step 3: 显式主题色修复**

对每个命中的 folder-name Text 显式指定颜色（不猜容器语义，最稳）：

```kotlin
Text(
    folder.name,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.weight(1f),   // 1311 行原有参数保留
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
```
（1858 行处同样补 `color = MaterialTheme.colorScheme.onSurface`，其余参数不动。）

- [ ] **Step 4: 双主题验证**

浅色（黑字应保持近黑 onSurface）与深色（应变浅色）各截图一张，对比 before
Expected: 深色可读、浅色观感不变

- [ ] **Step 5: 质量门**

Run: `./gradlew :app:shared:compileKotlinWasmJs --console=plain`
Expected: 通过

- [ ] **Step 6: 提交**

```bash
git add app/shared/src/commonMain/kotlin/com/linan/barezen_drive/ui/screens/files/FilesScreen.kt
git commit -m "fix: 暗色模式文件夹名改用主题 onSurface 色，修复黑字不可见"
```

---

### Task 8: UI 打磨实施（外壳 → 各页面，输入 = 勾选清单）

**Files:**
- Modify（外壳，先行）: `ui/shell/MainShell.kt`（底部导航、顶栏滚动显底色）、`ui/theme/ColorGen.kt`/`Theme.kt`（字体阶梯/色彩核对，仅在清单要求时）
- Modify（按页面）: 清单每条「改法」点名的文件——登录 `ui/screens/login/`、首页 `HomeScreen.kt`、文件 `FilesScreen.kt`、相册 `AlbumScreen.kt`、预览 `PreviewScreen.kt`、传输 `TransferCenterScreen.kt`、设置 `SettingsScreen.kt`、回收站 `TrashScreen.kt`、分享 `ShareManagerScreen.kt`、跨页面 `ui/components/`（对话框与吐司）

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-09-23-selected-items.md` 的 UI 分组条目（Task 5 产出）
- Produces: 全部勾选 UI 条目落地；每页双主题截图

- [ ] **Step 1: 外壳先改**——按勾选项处理 MainShell（底部导航样式、顶栏滚动显底色、骨架屏容器），构建一次：
Run: `./gradlew :app:shared:compileKotlinWasmJs --console=plain` → Expected 通过

- [ ] **Step 2: 逐页实施**——按清单顺序：首页 → 文件 → 相册 → 预览 → 传输 → 设置 → 回收站 → 分享 → 登录 → 对话框与吐司。每页循环：
  a. 只改该页勾选条目（对照 Global Constraints 的间距/字号/圆角/禁止项）
  b. 双主题截图存 `/tmp/opencode/ui-shots/<page>-light-final.png` / `-dark-final.png`
  c. 该页质量门：`./gradlew :app:shared:compileKotlinWasmJs --console=plain`
  d. `git commit -m "style: <页面名> 打磨（勾选清单）"`

- [ ] **Step 3: 每完成 3 页跑一次全量质量门**

Run: 四条质量门命令（见 Global Constraints）
Expected: 全绿；失败即修，不得带病继续

- [ ] **Step 4: 全页终检截图**

重走 Task 4 Step 1 的完整页面列表（双主题），与 before 截图归档在一起
Expected: 清单勾选项逐条可见地解决；未出现禁止项效果

---

### Task 9: 落地用户勾选的审查修复项（输入 = 勾选清单）

**Files:**
- 由 selected-items.md 中审查条目决定（每条已带「文件:行号 + 建议修法 + 改动量」）
- 服务端改动配套测试写在 `server/src/test/kotlin/`（H2 兼容模式，遵循现有测试风格；越权/路径穿越类必须先写失败测试）

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-09-23-selected-items.md` 审查分组（按条目 ID）
- Produces: 修复提交；服务端测试新增/通过

- [ ] **Step 1: 按「严重 → 次要 → 建议」排序逐条实施**，每条：
  a. 服务端逻辑改动 → **先写失败测试**（TDD）：`server/src/test/...` 新用例 → 跑 `./gradlew :server:test --rerun-tasks --console=plain` 确认 FAIL
  b. 最小实现使测试通过 → 再跑确认 PASS
  c. 客户端纯样式/体验改动 → 以编译门 + 截图代替单测（无法单测的在提交信息说明）
  d. `git commit -m "fix: [条目ID] 一句话"`（或 `perf:`/`refactor:` 按条目性质）

- [ ] **Step 2: 每 3 条跑一次全量质量门**（命令见 Global Constraints）→ Expected 全绿

- [ ] **Step 3: 清单销项**——在 selected-items.md 每条尾部标 `[x]`，全部完成后进入 Task 10

---

### Task 10: 验收走查 + roadmap 更新（不含设计文档提交）

**Files:**
- Modify: `docs/roadmap.md`（记录本次完成项、把「暂不修」条目并入已知问题）
- Deliver: Task 6/7/8 的全套 before/after 截图给用户走查

**Interfaces:**
- Consumes: 全部改动 + 截图归档
- Produces: 用户验收通过；roadmap 反映现状

- [ ] **Step 1: 全量质量门终跑**（四条命令）→ Expected 全绿
- [ ] **Step 2: 提交 roadmap**：

```bash
git add docs/roadmap.md
git commit -m "docs: 记录阶段一优化审查与 UI 打磨完成项"
```
（`docs/superpowers/**` 保持 untracked——用户要求设计/计划/清单文件不上传）

- [ ] **Step 3: 交付截图走查**——把 before/after 全套给用户逐页过；记录修改意见
- [ ] **Step 4: 按意见返修**（回到 Task 8 对应页步骤 b-d，直到用户放行）
- [ ] **Step 5: 宣告阶段一完成，提示进入阶段二 WebDAV 的 brainstorm**

---

## Self-Review 记录

1. **规格覆盖**：规格 §2 四审查线→Task 2/3；§3 视觉规范→Global Constraints+Task 8；§4 两 bug→Task 6/7；§5 清单组织→Task 4；§6 实施顺序与质量门→Task 6/7/8/9 顺序+质量门步骤；§7 双主题验收→Task 8/10；§8 交付物→Task 3/4/10。§9 WebDAV 为阶段二，本计划不含（符合拆分）。无缺口。
2. **占位符扫描**：Task 8/9 的条目内容显式声明来源于 Task 5 产出的勾选清单文件（非 TBD），实施流程含具体命令与文件清单——符合「输入是已产出文档」而非「待补细节」。
3. **类型/命名一致性**：`WallpaperLayer(imageBitmap: ImageBitmap?)`、`wallpaperEnabled`/`wallpaperBitmap`（App.kt 129-131）、MainShell 行号（113/116/152/231/248/362）、FilesScreen 行号（1311/1858）均已在写计划前实测；质量门四命令抄自 docs/development.md 原文。
