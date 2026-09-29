# 阶段一勾选结果（selected-items）

> GATE 日期：2026-09-26 ｜ 用户勾选：**UI 打磨 29 条全修** + **审查清单 严重 16 + 次要 54 = 70 条**
> 不修：审查清单「建议」44 条（仅存档，见文末）
> 依据：`ui-polish-checklist.md`（29 条，含 3 处修正）、`audit-findings.md`（114 条）
> 销项规则：每条修完在本文末尾「销项进度」打 `[x]`

---

## 一、UI 打磨 29 条（全选）

### 用户点名 · 必修
| # | 页面 | 条目 | 标记 |
|---|---|---|---|
| 5 | 文件 | 面包屑路径条过重（大 chip 独占一行），改百度网盘式紧凑文字链 | 🅰 |
| 9 | 搜索 | 搜索结果图片不显示缩略图（实测 3 条 png 全占位图标） | 🅰 |

### 壁纸 bug（有铁证）
| # | 页面 | 条目 |
|---|---|---|
| 15 | 传输中心 | pushed 栈页无壁纸（根页有壁纸、此页纯黑，同一时刻实测） |
| 17 | 设置 | pushed 栈页无壁纸，开关显示「开」却看不到壁纸；同病栈页含回收站/已归档/分享管理/用户管理 |

### 破坏性操作与提示口径
| # | 页面 | 条目 |
|---|---|---|
| 18 | 对话框 | 对话框背景带蓝调（实测 ≈#E8EBF5），改纯中性 surface |
| 19 | 对话框与吐司 | 删除按钮/菜单项**全都不是红色**（确认框里「删除」也是品牌蓝）→ error 色 |
| 20 | 对话框与吐司 | 多种操作无提示、分享创建链接无吐司、文案口径不一 → 统一 Snackbar |
| 23 | 设置 | 用户管理对非所有者可见入口、进入即报错「仅实例所有者可执行此操作」 |

### 空态与加载态
| # | 页面 | 条目 |
|---|---|---|
| 16 | 传输中心 | 失败行仅红字、无「重试」按钮 |
| 21 | 回收站 | 空态仅两行文字、无图标（修正 ④三态表） |
| 24 | 传输中心 | 空态仅一行「暂无文件传输任务」，缺图标 + 主按钮 |
| 25 | 已归档 | 空态仅一行「没有归档的照片」，无图标无副文案 |
| 27 | 全局 | 加载态用中心小转圈、非骨架屏 |

### 图片显示与其余打磨
| # | 页面 | 条目 |
|---|---|---|
| 4 | 首页 | 相册瓦片全占位图标、无照片封面 |
| 7 | 文件 | 图片文件行显示默认图标、无缩略图 |
| 26 | 文件 | 桌面端多选不可达、底部操作栏触达不到 |
| 28 | 预览 | 图片加载失败仅红字无重试；Web 图片疑整体加载失败 → **先查根因** |
| 29 | 全局 | 壁纸在内容面板与空白区之间有生硬分界线 |
| 1 | 登录 | 同屏三个「登录」，提交按钮层级弱 |
| 2 | 登录 | 「服务器地址」默认展开占位 |
| 3 | 首页 | 状态卡统计环语义不明 |
| 6 | 文件 | 列表行高 68dp 超 56–64dp 上限 |
| 10 | 搜索 | 搜索期间无加载指示 |
| 11 | 相册 | 「全部照片」时间线全空白（无骨架/空态/返回） |
| 12 | 相册 | 集合瓦片 658×686px 占近半屏 |
| 13 | 相册 | 时间线顶栏未见视图切换入口（待复核） |
| 14 | 预览 | 文本查看器把错误渲染成正文 |

### 已撤销（不计待修）
| # | 条目 | 结论 |
|---|---|---|
| 22 | 分享管理「死入口」 | ❌ 自动化点击误判，页面一次点击正常打开 |
| 8 | 深色模式文件夹黑字 | ⚠ **本轮无法复现**（三处代码均为主题色、深色下白字可读）→ 不修，待用户给出复现条件 |

---

## 二、审查清单 70 条（严重 16 + 次要 54）

> 完整描述见 `audit-findings.md`（严重 = 第 13–28 行；次要 = 第 33–88 行）。此处为紧凑索引，改动量见原文。

### 严重 16 条（全部修）
| # | 线 | 位置 | 问题摘要 | 量 |
|---|---|---|---|---|
| S1 | ①性能 | `auth/AuthService.kt:115` | bcrypt 在 `transaction{}` 内 → 独占 Hikari 连接、池上限 5，登录高峰全站卡死 | M |
| S2 | ①性能 | `db/Tables.kt:81` | `files`/`file_versions` 缺 `storage_key` 索引 → `orphanBlobKeys` 每 key 一次全表扫 | S |
| S3 | ②技术债 | `files/BlobPurge.kt:16` | 重计数到 unlink 的竞态窗口 + 无宽限期删除表 → **悬空行、数据丢失级** | L |
| S4 | ③安全 | `files/FileContent.kt:179-193` | Content-Type 全信客户端 + 无 nosniff/CSP → **存储型 XSS 窃 localStorage token** | M |
| S5 | ①性能 | `files/FileService.kt:330` | `orphanBlobKeys` 串行 2 条 count × 两遍链路 → 删 1000 blob ≈ 4000 次往返 | M |
| S6 | ①性能 | `files/ShareRoutes.kt:52` | 公开分享/管理路由阻塞 Netty event-loop（未切 `Dispatchers.IO`） | M |
| S7 | ①性能 | `files/ThumbnailService.kt:212` | ImageIO 无下采样解码 64MP → 单图 192–256MB → OOM | M |
| S8 | ②技术债 | `scripts/install.sh:14` | Usage 推荐 `curl｜bash`，但 `read` 从 stdin 取值会吞脚本行 → 官方用法必然异常 | S |
| S9 | ④体验 | `AlbumScreen.kt:414-437` | 每次上传落地全量重拉第一页 → 浏览中被拽回顶部 | L |
| S10 | ④体验 | `AlbumScreen.kt:463-467` | 上传 `Result` 被丢弃 → 失败无任何提示 | M |
| S11 | ④体验 | `AlbumScreen.kt:611-629` | 相册多选删除无确认框（其他页都有） | S |
| S12 | ④体验 | `AlbumScreen.kt:897-905` | 日期视图底部永远转圈（缺 `if (!exhausted)`） | S |
| S13 | ①性能 | `Application.kt:104` | 无全局请求体上限 → 未鉴权大 body 打爆 1G 主机 | S |
| S14 | ④体验 | `FilesRepository.kt:51` | 文件列表无分页 + 400ms 防抖全量重拉 → 大目录卡顿 | L |
| S15 | ④体验 | 多处 + `FileSaver.android.kt:39` | 下载零进度、不进传输中心（`DOWNLOAD` 枚举 0 引用） | L |
| S16 | ④体验 | `ThumbnailLoader.kt:75-82` | 缩略图仅内存 LRU + token 变化全清 → 冷启动全量重拉 | M |

### 次要 54 条（全修，按清单顺序）
`androidMain/sync/SyncWorkers.kt:138`｜`api/Throttle.kt:123`｜`auth/AuthRoutes.kt:60`｜`data/transfer/TransferCenter.kt:237`｜`data/upload/UploadManager.kt:65`｜`UploadManager.kt:229`｜`db/DatabaseFactory.kt:35`｜`db/DatabaseFactory.kt:40`｜`db/Tables.kt:81`｜`files/FileContent.kt:67`｜`FileContent.kt:190`｜`FileContent.kt:179`｜`files/FileService.kt:163`｜`FileService.kt:241`｜`FileService.kt:226`｜`FileService.kt:55`｜`files/ShareRoutes.kt:49`｜`files/ShareService.kt:292`｜`ShareService.kt:123`｜`ShareService.kt:182`｜`files/ThumbnailRoutes.kt:39`｜`ThumbnailService.kt:199`｜`ThumbnailService.kt:43`｜`files/UploadService.kt:302`｜`UploadService.kt:154`｜`UploadService.kt:404`｜`UploadService.kt:383`｜`UploadService.kt:106`｜`UploadService.kt:77`｜`scripts/e2e.py:41`｜`system/AvatarRoutes.kt:50`｜`system/OwnerGuard.kt:19`｜`system/ServerSettingsService.kt:22`｜`system/SystemRoutes.kt:26`｜`ui/screens/files/FilesScreen.kt:453`｜`AlbumScreen.kt:302`｜`App.kt:127`｜`App.kt:205`｜`App.kt:260`｜`App.kt:442`｜`App.kt:216`｜`Application.kt:98`｜`ArchiveScreen.kt:93`｜`FileSaver.android.kt:46`｜`FileThumbnail.kt:52`｜`FilesScreen.kt:647`｜`FilesScreen.kt:584`｜`FilesScreen.kt:807`｜`FilesScreen.kt:318`｜`HomeScreen.kt:125`｜`ShareScreen.kt:82`｜`TransferCenterScreen.kt:87`｜`UsersScreen.kt:99`｜`install.sh:162`

---

## 三、暂不修（仅存档）

- 审查清单「建议」**44 条**（`audit-findings.md` 第 93–141 行）
- UI 清单 #8 深色文件夹黑字（无法复现）、#22 分享管理死入口（误判撤销）

---

## 四、执行顺序（每批跑一次质量门，见 `docs/development.md`）

| 批次 | 内容 | 理由 |
|---|---|---|
| **A** | 回填 storage 文件内容 → 复核 #28/#7/#9 是否真是加载缺陷 | storage 被 `/tmp` 清空过，缩略图不显示可能只是文件不在盘上——先排除假 bug 再改代码 |
| **B** | #15 + #17 壁纸（Task 6）+ #29 遮罩硬边 | 有铁证、改动集中在一个根容器 |
| **C** | #5 面包屑 + #9 搜索缩略图 | 用户点名 |
| **D** | S4 存储型 XSS + S13 请求体上限 + S8 install.sh | 安全与安装脚本，改动小、收益最高 |
| **E** | #18 #19 #20 #23 破坏性操作与提示口径 | 一组弹层/吐司改动 |
| **F** | #16 #21 #24 #25 #27 空态与加载态 + 骨架屏 | 统一空态组件 |
| **G** | S1 S2 S5 S6 S7 S3 性能与数据安全 | 服务端，改动量大，需 TDD |
| **H** | S9–S12 S14–S16 + 次要 54 条 | 客户端大改 + 长尾 |
| **I** | 其余 UI #1 #2 #3 #4 #6 #7 #10 #11 #12 #13 #14 #26 | 打磨收尾 |

## 销项进度

- [x] **#15 传输中心无壁纸** + **#17 设置页无壁纸**（含同病栈页：回收站/已归档/分享管理/用户管理/备份相册/开源说明/预览）— commit `7290672`，Task 6 完成
  - 证据：`v3-transfer-wallpaper.png`(163KB)、`v2-settings-dark-wallpaper.png`(139KB) 有壁纸；`v3-files-dark.png`(171KB) 与改前基线 168KB 一致（无回退）
  - 附带修正：#29 的硬边**未**因此消除（仍是列表面板自身的背景边界），保持待修
- [x] **#9 搜索结果显示缩略图**（🅰 用户点名）— commit `144bf9b`
  - 根因比预想更直白：`SearchScreen` 根本没接 `FileThumbnail`，直接画类型图标
  - 证据：`search-results-light.png`（改前，3 条全是占位图标）→ `fix9-search-thumbnails.png`（改后，3 条真实缩略图）
- [x] **#5 文件页面包屑改紧凑文字链**（🅰 用户点名）— commit `144bf9b`
  - 证据：面包屑行高 40dp+（TextButton）→ 30dp；`fix5-folder-breadcrumb.png`
- [x] **#18 对话框背景带蓝调** — commit `02d1185`：24 处 `AlertDialog` 显式 `containerColor = surface`
- [x] **#19 删除类操作不标红** — commit `02d1185`：8 处删除文案改 `colorScheme.error`（文件页 4 菜单项 + 删除确认框、首页批量删除、预览删除、回收站清空/永久删除、用户删除）
  - 证据：`dialog-delete-light.png`（改前：蓝底 + 蓝色「删除」）→ `fix18-19-delete-dialog.png`（改后：中性底 + 红色「删除」）
- [x] **#26 桌面端多选不可达** — commit `44962f6`：FileRow 菜单新增「选择」入口（主修复，实测可进入多选，顶部出现「全选 1」+ 操作栏）；另加 `ui/component/Hover.kt`（Enter/Exit 跨平台 hover）显复选框
  - 证据：`fix26-menu-select.png`（9 项菜单含「选择」）、`fix26-multiselect-active.png`（多选态 + 底部操作栏）
  - ⚠ hover 复选框**未实拍**：合成 pointer 事件不触发 Compose 命中测试，真实鼠标无此问题；主修复不依赖它
- [x] **#27 加载态转圈非骨架屏** — commit `44962f6`：新增 `ui/component/Skeleton.kt`（7 行 × 64dp，方块+双条，1.2s 正弦呼吸），接入文件页/回收站/已归档
  - ⚠ 骨架视觉**未实拍**：无头环境无法给 App 的 HTTP 客户端加延迟（Ktor 初始化即捕获传输引用，fetch 与 XHR 挂钩均无效），本地接口 5ms 返回；接线与编译已验证
- [x] **#16 传输中心失败行可重试** — commit `165fdaf`：`TransferItem.retry` 持有 `PickedFile` 句柄（随 StateFlow 原子发布，行清除即释放），`UploadManager.retryFailed` 就地重跑；失败行加 ⚠ 图标 + error 描边重试按钮。新增 2 个单元测试（12 项全过）
  - ⚠ 限制：**重试按钮的视觉未实拍**——无头环境无法触发一次真实上传失败；由单元测试覆盖逻辑
- [x] **#21 回收站空态** / **#24 传输中心空态** / **#25 已归档空态** — commit `165fdaf`：新增共享 `ui/component/EmptyState.kt`（图标+主句+副句+可选主按钮），三屏统一接入；传输中心新增「去上传」主按钮（`onGoUpload` 回调）
  - 证据：`fix21-trash-empty.png` / `fix24-transfer-empty.png` / `fix25-archive-empty.png`
- [x] **#28 缩略图 404** + 连带 **#4 首页瓦片占位** + **#7 文件行无缩略图** — **环境修复，非代码缺陷**
  - 根因：`/tmp` 被清导致 `thumbs/<sha>.jpg` 全丢，而 `has_thumbnail` 仍为 true → GET 路由跳过按需生成、永久 404
  - 修复：回填 5 个文件内容 → 复位 `has_thumbnail=false` 触发生成 → 置回 true
  - 证据：`check-home-album-tiles.png` 首页 3 瓦片 + 最近 3 行全部显示真实缩略图
  - 遗留可选加固：GET 路由改为先 `exists()` 再决定是否生成（避免标志与 blob 不一致时永久 404）
- [x] **#1 登录主按钮与 tab 同名、层级弱** — commit `4e081a7`：TabRow（仅 2dp 下划线）改 `SingleChoiceSegmentedButtonRow`（选中态实底 + 勾选）；按钮 52dp→48dp、圆角 20dp→8dp。清单里"右下角蓝色文字按钮"的描述已过时（代码本就是全宽实心），真问题是 tab
- [x] **#2 「服务器地址」默认展开占位** — 同上提交：默认只显示用户名/密码，地址收进「连接设置（服务器地址）」折叠行（带 chevron），展开态存 `AppPreferences.serverFieldExpanded`（android/wasm 两端）
  - 证据：`ui1-login-collapsed.png` / `ui2-login-expanded.png`
- [x] **#3 状态卡统计环语义不明** — commit `b03688b`：20dp 不完整圆环（无百分比标注，5% 读作"没加载完"）→ 3dp 细进度条。证据：`ui3-home-metric-bars-nowall.png`
- [x] **#6 列表行高 68dp 超上限** — commit `905e1f9`：文件行与文件夹行垂直内边距 10→8dp，实测三行均 h64（`ui6-row-height.png`）
- [x] **#10 搜索期间无加载指示** — 同上提交：输入框下沿 2dp 线性进度条，覆盖防抖+请求两段
- [x] **#11 「全部照片」时间线全空白**（代码层） — commit `b03688b`：加载态转圈 → `SkeletonGrid`；空态改图标+副文案+「从相册上传」主按钮。⚠ 瓦片点击在无头环境无响应，骨架/空态未实拍
- [x] **#12 集合瓦片巨大** — 同上提交：列数按宽度选（2/3/4）+ 网格限宽 1040dp 居中，瓦片 658×686 → 240×240；占位块改扁平品牌色。证据：`ui12-collection-tiles.png`
- [x] **#13 时间线顶栏未见视图切换入口** — **复核：入口存在**，在顶栏下方的 64dp 浏览操作栏（非多选态显示），刻意不放顶栏。机制不改，仅记录
- [x] **#14 文本查看器把错误渲染成正文** — commit `b03688b`：错误文案不再进 SelectionText，改独立错误块（图标+重试），加载态改骨架行
- [x] **#20 成功/失败提示口径不一** — commit `4e081a7`：补文件夹删除成功、归档/取消归档、创建分享链接、Home 批量删除、注册开关静默回滚五处。⚠ 吐司未实拍（三层弹层点击时序不可靠）
- [x] **#23 用户管理非所有者可见入口** — commit `905e1f9`：`UserDto` 加 `isOwner`（服务端 `ownerAccountId()` 解析），设置页「服务器」分组对非所有者整体隐藏；直链进入时 403 改标准错误块且不再给「重试」。证据：`ui23-guest-section-gone.png` + `MeOwnerFlagTest` 3 项
- [x] **#29 壁纸硬边** — commit `b03688b`：横向硬边已由 Task 6（`7290672`）消除（像素实测无接缝）；真正还在的是**纵向**那条——`NavigationSuiteScaffold` 导航栏自带不透明容器，用 `navigationSuiteColors` 换成同色同透明度。像素实测：ΔRGB 从 ≈80 降到 ≤1
