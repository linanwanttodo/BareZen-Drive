# Task 10 走查记录（阶段一 · 次要项批次）

日期：2026-09-30 ｜ 范围：本轮 54 条次要项 + 前置的 16 条严重项 ｜ 提交区间 `53bd9ac`..`094c05c`

## 1. 四道质量门（终跑）

| 门 | 结果 |
|---|---|
| `./gradlew :server:test --rerun-tasks` | BUILD SUCCESSFUL，**284 项**（本轮前 167），失败 0 |
| `./gradlew :core:allTests --rerun-tasks` | BUILD SUCCESSFUL |
| `./gradlew :app:shared:testAndroidHostTest --rerun-tasks` | BUILD SUCCESSFUL，**103 项**（本轮前 85），失败 0 |
| `./gradlew :app:shared:compileKotlinWasmJs` | BUILD SUCCESSFUL |

端到端：`scripts/e2e.py` 对全新实例 **39 项全过**；指向复用实例时 exit 2 并打印明确原因（该脚本会改注册开关、留文件，本质上要求全新实例）。

## 2. 截图走查

截图目录 `~/.cache/barezen-ui-shots/tour/`（不入库）。环境：web 构建 + PostgreSQL + `server:run`，
登录态用页面内 fetch 注入 localStorage（Compose Web 把输入框渲染进 canvas，页面无 `<input>`，
键盘事件无处可落）；导航用像素点击。

| 截图 | 内容 | 对应本轮改动 |
|---|---|---|
| `01-home.png` | 首页：服务器状态卡 + 相册条 + 最近 | HomeScreen 错误项加重试、相册条失败不再静默隐藏 |
| `02-files-root.png` | 文件页根目录，64dp 行高、单一 ⋮ 入口 | 行组件 12–14 个 lambda → 单一 `onAction`；行高 64dp |
| `03-files-folder-breadcrumb.png` | 预览页（点到了文件行）+ 底部操作栏 | 底部操作栏规范 |
| `04-album-collections.png` | 相册集合落地页（Web 集合瓦片） | 集合落地页新增错误态 + 重试 |
| `05-album-photo-grid.png` | 集合内照片网格 | — |
| `06-settings.png` | 设置页：账号 / 主题 / 语言 / 主色 / 玻璃栏 / 壁纸 | — |
| `07-files-in-folder-breadcrumb.png` | 文件夹内，面包屑带文件夹名 | 面包屑限制可见段数 |
| `09-breadcrumb-deep-truncated.png` | 四段面包屑 + 空态「此文件夹为空」 | 空态组件 |
| `10-settings-server.png` · `11-settings-server-bottom.png` | 服务器段：开放注册开关、分享管理、已归档、回收站、关于 | 注册开关默认关闭后仍可在此开；owner-only 行按 `isOwner` 显隐 |
| `13-settings-dark.png` · `14-home-dark.png` | 深色主题（计划 §7 的双主题要求） | — |

## 3. 实机验证（非截图，安全与性能项）

| 项 | 验证方式 | 结果 |
|---|---|---|
| 注册默认关闭 | 非回环对端注册 | 403 `REGISTRATION_DISABLED` |
| 同上，带伪造 XFF | 加 `X-Forwarded-For: 8.8.8.8` | 403（转发头存在即判定为经代理，bootstrap 位关闭） |
| 本机首账号 | loopback 注册 | 201，`settings.owner_id` 正确固化，`/api/me` 与**注册响应**都 `isOwner: true` |
| 账号存在后 | loopback 再注册 | 403 |
| 限流域不被伪造头绕过 | 连发 9 次注册、每次换 XFF | 第 6 次即 429 —— 伪造头换不出新桶 |
| 索引收益 | PG 16 + 5 万行 EXPLAIN ANALYZE | 相册 11.21→0.093ms（Sort 消失）、分享列目录 6.03→0.095ms、最近 11.05→0.15ms |
| 封面 fd 泄漏 | `/proc/self/fd` 计数 | 40 次不可解码输入：旧 40 个泄漏 → 0 |
| advisory lock | 一次性测试跑真 PG 后删除 | 同 key 真互斥、`pg_locks` 可见、xact 级自动释放、异 key 不互卡 |

## 4. 拍不到 / 验不到的部分（如实记录）

- **⋮ 弹层内的交互**（版本历史三态、上传失败重试按钮、删除确认）：弹层是无头环境下的「僵尸弹层」——
  语义树被弹层替换、Esc 关不掉，因此三层点击时序与吐司实拍不可行。这几条靠**代码路径 + 单测**
  证明（`UploadManagerTest` 16 项、`FileSaveResultTest`、各 Screen 的编译通过），未实拍。
- **面包屑的截断形态**：截图里最深只到四段，仍在可容纳范围内，看不到省略号。逻辑已改
  （限制可见段数），要看到截断需要 6 段以上的路径。
- **相册集合落地页的错误态 / 首页的重试按钮**：需要断网或让请求失败，脚本里未构造；
  代码已加，改前没有。
- **Android 侧全部项**：无设备，只过编译。缩略图磁盘缓存、保存对话框的取消/失败三态、
  备份失败原因回写，都只有编译与单测证据。
- **并发上传的多行进度**：无头环境无法真实并发上传（需要文件选择器 + 分块时序），
  靠 `UploadManagerTest` 的 4 项并发用例证明（含「取消 A 不会 abort B 的会话」）。
- **`docs/superpowers/**` 保持 untracked**（用户要求设计/计划/清单不上传）。

## 5. 提交

18 个提交，全部在 `master`，**未 push**。工作区仅 `?? docs/superpowers/`。
