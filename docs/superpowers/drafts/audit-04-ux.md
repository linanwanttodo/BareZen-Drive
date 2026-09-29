# 审计 04 · 客户端体验（app/shared commonMain + androidMain）

只读审查，未修改任何文件。范围：`app/shared/src/commonMain` 与 `app/shared/src/androidMain`。
条目格式：`- [严重/次要/建议] 【④体验】文件名:行号 → 问题 → 影响 → 建议修法 → 改动量(S/M/L)`

## 严重

- [严重] 【④体验】AlbumScreen.kt:414-437 → `LaunchedEffect(revision)` 收到任何一次上传落地就把 `loaded = emptyList()` 再重拉第一页（全量重排），滚动位置按 index 保留会掉回顶部 → 相册备份期间用户正浏览照片流时每隔 400ms 静默期就白屏一次并被拽回顶部，正在浏览的照片全部重建 → 改为增量合并：记录本次 revision 前的 cursor 与首屏可见项，刷新后把新页 append 到既有列表尾部（或只 prepend `before=旧首条cursor` 的新页），并用 `LazyGridState.scrollToItem` 前的记忆 key 保持视口；只有 scope 切换才允许清空 → 改动量(L)
- [严重] 【④体验】AlbumScreen.kt:463-467 → `uploadJob = launch { uploader.upload(...) }` 的 `Result` 被完全丢弃，上传进度对话框（AlbumScreen.kt:1093-1130）只渲染 `p.fileName + p.fraction + speedText`，没有 phase==FAILED 分支、没有错误文案 → 相册上传失败时对话框随 `pendingUploads` 清空而消失，列表刷新后照片也没出现，用户全程无任何失败提示（错误被静默吞掉）→ 对话框读取 `progress.phase`，FAILED 时显示 `p.error` 文案 + “重试/关闭”；同时在 `uploadAll` 同等位置对 album 批次的 `result` 做 `onFailure { snackbar.showSnackbar(...) }` → 改动量(M)
- [严重] 【④体验】FilesScreen.kt:666 / HomeScreen.kt:209 / AlbumScreen.kt:605 / PreviewScreen.kt:125 / ShareScreen.kt:353 + FileSaver.android.kt:39-72 → 下载既没有进度回调，也全仓库没有任何 `TransferKind.DOWNLOAD` 使用（grep 0 命中）：不进传输中心、不发进度通知 → 点“下载”后在文件保存对话框确认完就是黑盒，大文件数分钟零反馈，用户会重复点击并怀疑卡死；传输中心的 DOWNLOAD 枚举形同虚设 → `FileSaver` 回调签名加 `onProgress(done,total)`，下载开始时 `TransferCenter.start(name, TransferKind.DOWNLOAD, size, lane)` 并逐块 `progress()`，结束 `done/fail`；传输中心完成行提供“打开文件位置” → 改动量(L)
- [严重] 【④体验】FilesRepository.kt:51 + FilesScreen.kt:176,407-412 → `contents(folderId)` 无分页参数，一次拉整个文件夹并整表替换 `state`；`LibraryRevision` 每次 bump（400ms 防抖）都 `reload()` 全量重拉 → 上万文件的目录首屏一次性下载+反序列化，滚动中列表数据整体换代，所有 item 重绑定，掉帧明显；上传期间反复整表重排 → contents 请求加 `cursor/limit`，列表尾部放 load-more footer（照抄 AlbumScreen 的分页骨架）；revision 只在差量（新文件/删除）时局部插入或复用 `key={id}` 的 item 级更新 → 改动量(L)
- [严重] 【④体验】AlbumScreen.kt:611-629 → 相册多选的“删除”是顶栏 `BarAction` 直接执行 `repo.deleteFile`，没有确认对话框（FilesScreen.kt:764-817、HomeScreen.kt:311-331、PreviewScreen.kt:253-278、TrashScreen.kt:190-200 全都有 AlertDialog 确认）→ 拖扫多选后误触删除按钮即批量删照片，与其他页面的破坏性操作口径不一致（虽然进回收站，但无撤销提示前用户不知道）→ 复用 `AlertDialog` + `confirmDeleteFiles(n)` 文案，确认按钮标红，与 Home 的批量删除对话框完全一致 → 改动量(S)
- [严重] 【④体验】AlbumScreen.kt:897-905 → 日期视图（viewMode==2）的 `item(key="loading")` 没有 `if (!exhausted)` 包裹，而 uniform（AlbumScreen.kt:951）与瀑布（AlbumScreen.kt:1023）都有；`AlbumLoadMoreFooter` 在无 error 时无条件画 `CircularProgressIndicator` → 相册翻完最后一页后，日期视图底部永远转圈，看起来“永远还在加载” → 补上 `if (!exhausted)`，并给 footer 加 `exhausted` 时渲染“没有更多”文案 → 改动量(S)
- [严重] 【④体验】ThumbnailLoader.kt:75-82 → 缓存只有进程内 LRU（`cache/cacheBytes/totalBytes`），没有任何磁盘层；`clear()` 在每次 token 变化时全清（App.kt:205-210）→ 每次冷启动进入相册/文件网格都要按页重新下载全部缩略图（PAGE_SIZE=200/页），弱网下网格长时间占位图标；内存压力被逐出后同样全量重拉 → 加 key-value 磁盘缓存（filesDir `thumbs/<fileId>`，JPEG 原始字节，按 LRU 大小上限如 64MB），`fetch()` 未命中内存时先读磁盘，`insert()` 同步落盘；登出只清内存不清磁盘（或按 baseUrl 分目录） → 改动量(M)

## 次要

- [次要] 【④体验】FileThumbnail.kt:52-56 → 靠 `repeat(3) { delay(30.seconds) }` 轮询补齐“服务端延迟生成封面”，而负缓存 TTL 是 60s（ThumbnailLoader.kt:206），3 次×30s 刚好卡在边界外 → 每个可见行都挂 3 个定时协程，滚动时反复启停；封面在第 45s 生成时会多等 15s，仍不及时 → 复用已存在的 `ThumbnailHub.markAvailable/put`（上传完成路径已调用），或给 ThumbnailLoader 暴露 `StateFlow<Set<String>>` 的“封面已就绪”事件，FileThumbnail 订阅事件重载，去掉轮询 → 改动量(M)
- [次要] 【④体验】App.kt:127-175,166-168 → 首帧在组合里同步读 SharedPreferences 共 16 项 prefs + 2 项 token（`themeMode/wallpaperEnabled/username/languageMode/accentColor/...`、`TokenStorage.accessToken/refreshToken`），`AppPreferences.get()`（AppPrefs.android.kt:11）每次调用还新建匿名对象、getter 直连 prefs（首访问同步加载整个 XML 文件）→ 首帧主线程被磁盘读占用，低端机/大 prefs 时首帧卡顿，且 `filesViewMode/albumViewMode` 等在各 Screen 组合中反复读盘 → App 启动时一次性把 prefs 读进一个 `remember { AppPreferencesSnapshot(...) }`（或 `by lazy` 单例 AppPrefs 实现），getter 层加内存镜像；写入仍走 `apply()` → 改动量(M)
- [次要] 【④体验】App.kt:205 → `LaunchedEffect(TokenStorage.accessToken)` 用一个“每次重组都去 prefs 读一次”的非观察值当 key → 键变化依赖恰好发生的重组，登录/换账号时若该组合点没重组，`thumbs.clear()` 不执行，上一个账号的缩略图会残留（隐私 + 串号）；同时每次重组都在主线程读 prefs → 把登录态收敛为一个 `mutableStateOf<String?>`（登录/登出处显式写入），effect key 绑该 state；副作用改为 `LaunchedEffect(userId)` → 改动量(S)
- [次要] 【④体验】App.kt:260-261,341-358 → `push`/`pop`/`tabAvatar`/`themeToggle` 每次 `App()` 组合都新建，未 `remember` → 传给 MainShell 与四个 tab 的 lambda 实例永远变化，下层 Screen 无法被 Compose 跳过重组；设置页拖动玻璃透明度滑块（App.kt:433-436，每帧触发 App 重组）时整个可见列表全树重组 → `val push = remember(stack) {...}`、`val pop = remember(stack) {...}`、`tabAvatar = remember(files, currentUserId, username, signedIn) {...}`、`themeToggle = remember(dark) {...}` → 改动量(S)
- [次要] 【④体验】FilesScreen.kt:647-684,953-982 → `LazyColumn/LazyVerticalGrid` 每个 item 直接传入 12-14 个非稳定 lambda（`onOpen={...}/onRename={...}` 等）且行组件参数是这些不稳定引用 → item 永远无法跳过重组；再叠加 `menuFor`/`selectedFiles`/`progress` 任一变化导致外层 `when` 重建全部 lambda，滚动中每个可见行每帧重组 → 行回调改为只传 `file` + 一个 `remember` 化的 `(FilesAction, FileDto) -> Unit` 单一 lambda，或把行内动作收敛成 `onAction: (RowAction) -> Unit`，让 FileRow/FileTile 其余参数成为稳定引用 → 改动量(M)
- [次要] 【④体验】TransferCenterScreen.kt:87-99,155-166 → 每次重组（上传进度每 chunk 推一次 `TransferCenter.items`）都执行两次 `filter` + 一次 `buildList` 重建行列表，`TransferRow` 又接收不稳定 `onStop` lambda → 传输页在有活动传输时持续高频重组，正在滚动的行每帧重建 → `mine/activeRows/finished` 用 `remember(all, lane) {}` 包裹，`onStop` 用 `remember(item.id) {}`，行内进度仅读 `item.bytesDone/bytesTotal` → 改动量(S)
- [次要] 【④体验】HomeScreen.kt:125-128,269-277 → 相册条失败被注释掉静默隐藏（“album strip stays hidden on failure”），recent 的错误分支只有一行红字、没有重试按钮（对比 FilesScreen.kt:605 / TrashScreen.kt:148 都带 Retry）→ 首页断网后用户无法自助恢复，且“相册区整块消失”看起来像没有相册 → recent 错误 item 加 `TextButton(actionRetry)`；相册条失败时渲染一个占位卡片 + 重试，或在最近列表顶部给一次 snackbar 提示 → 改动量(S)
- [次要] 【④体验】ShareScreen.kt:82-87 → `sharedInfo` 的 `onFailure` 一律置 `invalid = true`，网络超时/离线与 token 失效共用 `InvalidShare`（“链接已失效，请向分享者索取新链接”）→ 弱网下打开有效分享链接被判死链，用户去找分享者重新发，实际重试即可 → 按 `ApiFailure.Http(404/410)` 判失效，`ApiFailure.Network` 渲染“无法连接服务器” + 重试按钮 → 改动量(S)
- [次要] 【④体验】App.kt:442-449 → 注册开关 `onRegistrationOpenChange` 失败时 `getOrNull()?.open ?: open` 静默回滚，没有 snackbar/dialog → 用户以为关闭了开放注册实际没关，属安全相关的静默失败 → 回滚时 `scope.launch { snackbar... }` 或在 Settings 页加 SnackbarHost，提示“设置失败，服务器未接受” → 改动量(S)
- [次要] 【④体验】FileSaver.android.kt:46-48 + FilesScreen.kt:356-358 / HomeScreen.kt:110-112 / AlbumScreen.kt:480-482 / PreviewScreen.kt:81-84 / ShareScreen.kt:133-136,346-348 → 用户在系统保存对话框里点“取消”（`uri == null`）与真正写入失败都回调 `onDone(null)`，5 个屏幕统一弹“下载失败” → 取消被误报为失败，用户以为下载坏了 → 合约区分 `onDone(reason)`：`Cancelled` 静默、`Failed` 才弹；或 `onCancel()`/`onError()` 双回调 → 改动量(M)
- [次要] 【④体验】FilesScreen.kt:584-590 与 TransferCenterScreen.kt:239-242 → 上传失败只展示红色错误文本（`Phase.FAILED` 分支），传输中心 FAILED 行也只有一个红字 `item.error`，没有“重试”按钮；断点续传能力（UploadManager.kt:192-196 跳过 `receivedChunks`、chunk 级 3 次退避）必须靠用户重新走一遍选文件流程才能触发 → 失败后无法一键续传，已传 chunk 白白闲置 → 传输中心 FAILED 行加“重试”，复用 `PickedFile` 句柄（同 uri/size/mtime）重新 `uploader.upload(...)`，走 resume-matching 直达 complete；Files 侧 FAILED 进度条同款按钮 → 改动量(M)
- [次要] 【④体验】AlbumScreen.kt:302-314 → collections 落地页封面 `repo.album(1,...).getOrNull()` 失败被吞，`collectionsLoading` 只有开/关两态 → 断网时落地页静默显示纯图标封面，没有错误态也没有重试（时间线有 `error` 分支，落地页没有）→ 记录 `collectionsError`，失败时在网格顶部渲染一行错误 + 重试（同 `resolveFailed` 分支的做法） → 改动量(S)
- [次要] 【④体验】ArchiveScreen.kt:93 → 取消归档成功的 snackbar 文案直接用了按钮文案 `strings.actionUnarchive`（“取消归档”），而 TrashScreen 有 `restored/deletedForever/trashEmptied` 专用成功文案 → 成功吐司读起来像按钮标签，口径与其他页面不一致 → 新增 `unarchived`（“已取消归档”）字符串，I18n 中英各一条 → 改动量(S)
- [次要] 【④体验】FilesScreen.kt:807-810 与 HomeScreen.kt:322 → 文件夹删除成功（`failed==0` 且非 trash 分支）完全无提示；Home 批量删除成功（failed==0）也无提示，只有失败才弹 → 与 Files 单文件 `movedToTrash`、Trash/Album 的成功吐司不一致，用户不确定是否执行 → 统一“成功也给轻提示”：文件夹用 `folderDeleted`，Home 批量用 `deletedCount(n)`；同时 rename/move/createFolder（FilesScreen.kt:706,738,758）成功后也补一条 snackbar → 改动量(S)
- [次要] 【④体验】UsersScreen.kt:99-143 → `list` 为空时直接渲染空 `LazyColumn`（白屏），没有“还没有注册用户”空态；对比 ShareManagerScreen.kt:110-120 有图标+文案 → 管理员首次进入/服务器无用户时看到整页空白，以为没加载出来 → `list!!.isEmpty()` 分支加 `Icons.Default.People` + `noUsersYet` 文案 → 改动量(S)
- [次要] 【④体验】App.kt:216-226 → 注册状态探测失败后固定 5s 间隔 `while(true)` 轮询，只在成功或未登录时 break，没有上限与退避上限 → 服务器是旧版本（无该端点）或长期 4xx 时，整个会话每 5 秒发一次请求，白白耗电耗流量并拖慢其它请求 → 指数退避到 60s 封顶；连续 N 次失败后停止，仅在 `registrationProbe++`（进入设置页）时重试 → 改动量(S)
- [次要] 【④体验】FilesScreen.kt:318-329,879-883 → 版本历史 `fileVersions` 失败只弹一条 snackbar，随后对话框落到 `versions.isEmpty()` 分支显示“暂无版本” → 加载失败被呈现成空态，用户会以为版本历史丢了 → 加 `versionsError` 状态，失败时对话框内渲染错误 + 重试（同 MoveDialog.kt:1570-1574 的三态写法） → 改动量(S)

## 建议

- [建议] 【④体验】AlbumScreen.kt:726-728 → `todayStr/yesterdayStr` 每次组合都调用 `Clock.System.now()` 并做日期减法，未 remember → 相册页每次重组（滚动 footer、进度更新）都白算两次时区换算 → `val todayStr = remember { ... }`（页面驻留期间跨零点再用 `LaunchedEffect` 刷新） → 改动量(S)
- [建议] 【④体验】AlbumScreen.kt:867 → `section.files.chunked(columns)` 写在列表 content lambda 内，列表内容每次重组都对每个 section 重新分块并分配新子列表 → 大相册 + 拖动缩放列数时 CPU 浪费在列表重建上 → `val rows = remember(section.files, columns) { section.files.chunked(columns) }`，或按 section 记忆 → 改动量(S)
- [建议] 【④体验】SearchScreen.kt:74-92 → 300ms 防抖后到响应回来之前没有加载指示，`results` 保持旧值，界面停在上一次结果/空白 → 长延迟时用户不知道在搜索，会反复改词导致请求互相取消 → 加 `searching` 状态：请求发出置 true、结束置 false，在结果区渲染 `CircularProgressIndicator`（或 LinearProgressIndicator 贴在输入框下） → 改动量(S)
- [建议] 【④体验】FilesScreen.kt:266-271 → 手动上传重选同一文件时 `cachedSha256 = null`，只有 NAME_CONFLICT 的覆盖路径（FilesScreen.kt:306-308）复用了 `freshHash`；后台 sync 队列有 `hash_cache`（SyncUploadQueue.kt:128-132）而手动上传没有 → 失败后重新选同一个 500MB 文件会整文件重读重新算 SHA-256 才能续传 → 本地做 `(uri,size,mtime) -> sha256` 的内存/持久缓存，`uploadAll` 命中即传入 `cachedSha256` → 改动量(M)
- [建议] 【④体验】SettingsScreen.kt:512-514 → 账户信息对话框的 `ping()` 走默认客户端超时（ApiClient.kt:121-125 connect 10s / request 20s），弱网下“测量中...”可挂 20 秒且不可取消 → 打开对话框后长时间无结果，用户以为卡死 → 为 ping 单设 2-3s 超时（或 `withTimeout(3000)` 包裹），超时即显示“不可达” → 改动量(S)
- [建议] 【④体验】TransferCenter.kt:44-49,52 → 传输历史只在内存（`MAX_HISTORY = 100`，进程结束即清空），FAILED 行重启后消失 → 用户重启后无法回看“上次哪些照片没传上去”，sync 侧的失败其实已持久化在 `SyncDb`，两处状态不一致 → 至少把 FAILED 行（名称/错误/时间）持久化一份，传输中心启动时从 `SyncDb.failed()` 回填 → 改动量(M)
- [建议] 【④体验】TrashScreen.kt:121 / ArchiveScreen.kt:101 / ShareManagerScreen.kt:76 → 这三个全屏子页的 `SnackbarHost` 没有加 `BottomBarClearance`，而 Files/Home/Album 明确抬高（FilesScreen.kt:416-420 注释写明会被玻璃底栏遮挡）→ 若后续这些页也复用底部浮层，提示条会被遮住；当前页面无底栏但宿主 shell 有的场景（Archive/Trash 从设置 push，shell 底栏已被覆盖，暂未复现）存在隐患 → 统一一个 `rememberSnackbarScaffold` 或给 SnackbarHost 统一 `Modifier.padding(bottom = BottomBarClearance)` 的常量开关 → 改动量(S)
- [建议] 【④体验】SyncAlbumsScreen.kt:117-124 → 顶栏“全部恢复”与“立即同步”两个 `TextButton` 常驻，但 `backupAlbumsReviewAll` 在 `buckets` 为空时只是 disabled，没有解释；“立即同步”在未开自动同步时也始终可点，点了无任何反馈（`MediaSync.syncNow` 静默） → 操作无回执，用户不知道是否已入队 → syncNow 后给一行 snackbar“已加入备份队列”，条件不满足时禁用并加副标题说明 → 改动量(S)

## 页面三态核对表

| 页面 | 加载态 | 空态 | 错误态 | 备注 |
| --- | --- | --- | --- | --- |
| LoginScreen | ✓（busy 按钮文案“请稍候”） | 不适用（表单页） | ✓（表单下方红字） | 完整；注册 tab 探测失败按“开放”处理，可接受 |
| HomeScreen · 最近列表 | ✓（`recent_loading` spinner） | ✓（`noRecentFiles`） | ⚠ 有错误文案但**无重试按钮** | HomeScreen.kt:269-277；首次失败后只能切 tab 或等 revision |
| HomeScreen · 相册条 | ✗（无独立加载指示） | ✓（不显示该区） | ✗ **错误被静默吞掉**（注释承认） | HomeScreen.kt:125-128，断网时相册区整块消失且无提示 |
| HomeScreen · 服务器状态卡 | ✓（小 spinner + “读取服务器状态”） | 不适用 | ✓（“无法连接”） | ServerStatusCard 三态齐全 |
| FilesScreen（文件列表） | ✓（居中 spinner） | ✓（`folderEmpty`） | ✓（错误 + 重试） | 三态齐全；但无分页（见严重条目） |
| FilesScreen · 版本历史对话框 | ✓（行内小 spinner） | ⚠（`noVersions`） | ✗ **失败落进空态** | FilesScreen.kt:318-329，失败仅 snackbar 后显示“暂无版本” |
| FilesScreen · 移动对话框 | ✓ | ✓（`folderEmpty`） | ✓（错误 + 重试） | 三态齐全 |
| FilesScreen · 上传目标对话框 | ✓（文字 loading） | —（至少有根目录） | ✓（错误 + 重试） | 三态齐全 |
| AlbumScreen · collections 落地页 | ✓（spinner） | 不适用（恒含“所有照片”tile） | ✗ **缺错误态**（封面请求 `getOrNull` 静默） | AlbumScreen.kt:302-314 |
| AlbumScreen · 相册文件夹解析 | ✓ | — | ✓（`resolveFailed` + 重试） | AlbumScreen.kt:774-778 |
| AlbumScreen · 时间线（瀑布/均匀/日期） | ✓（首屏居中 spinner、uniform 顶栏 linear） | ✓（`noPhotos` + 返回入口） | ✓（首屏错误 + 重试；页脚错误 + 重试） | 三态齐全；**日期视图 footer 在 exhausted 后仍永久转圈**（严重条目） |
| SearchScreen | ✗ **缺加载态**（300ms 防抖 + 请求期间无指示） | ✓（`searchEmpty`） | ⚠ 有错误文案但无重试 | SearchScreen.kt:137-153 |
| TrashScreen | ✓ | ✓（`trashEmpty` + 清理提示） | ✓（错误 + 重试） | 三态齐全 |
| ArchiveScreen | ✓ | ✓（`archiveEmpty`） | ✓（错误 + 重试） | 三态齐全 |
| TransferCenterScreen | 不适用（内存同步数据） | ✓（`noAlbumTransfers`/`noFileTransfers`） | ⚠ **仅行内红字，无重试/无整体错误态** | TransferCenterScreen.kt:239-242，FAILED 行不能重发 |
| ShareScreen · 链接入口 | ✓（居中 spinner） | 不适用 | ✗ **网络失败被当作“链接失效”** | ShareScreen.kt:82-87，误导性错误反馈 |
| ShareScreen · 文件夹视图 | ✓ | ✓（`folderEmpty`） | ⚠ 有错误文案但无重试 | ShareScreen.kt:303-318 |
| ShareScreen · 图片预览 | ✓ | — | ✓（`imageLoadFailed`，无重试） | SharedImagePreview 三态基本齐全 |
| ShareManagerScreen | ✓ | ✓（`noShareLinks` + 图标） | ✓（错误 + 重试） | 三态齐全 |
| UsersScreen | ✓ | ✗ **缺空态**（空列表渲染白屏） | ✓（错误 + 重试） | UsersScreen.kt:99-143 |
| SyncAlbumsScreen（备份相册） | ✓（居中 spinner） | ✓（`backupAlbumsEmpty`） | ✓（`loadFailed` + 重试，区分权限失败） | 三态齐全 |
| SettingsScreen | 不适用（表单） | 不适用 | 不适用 | 纯设置页 |
| Settings · 检查更新行 | ✓（spinner / 下载百分比） | 不适用 | ✓（结果对话框 + 下载失败重试） | UpdateCheckRow 三态齐全 |
| Settings · 账户信息对话框 | ✓（“测量中...”） | 不适用 | ✓（“不可达”） | ping 超时过长（见建议） |
| PreviewScreen · 图片查看器 | ✓（先缩略图后原图，spinner 兜底） | 不适用（`files` 非空） | ✓（`imageLoadFailed`，无重试） | ZoomableImage 三态齐全 |
| PreviewScreen · 文本查看器 | ✓（文字 loading） | ✓（空文件渲染空文本） | ⚠ 错误当作正文渲染，无重试 | TextViewer.kt:76-87 |
| PreviewScreen · Office 查看器 | ✓（spinner + 文案） | ⚠ 空白提取归入错误态 | ✓（`loadFailed`，无重试） | 另有 `officeTooLargePreview` 分支 ✓ |
| PreviewScreen · 视频/音频/PDF 播放器 | 平台 expect（androidMain 实现未在本轮 commonMain 范围内展开） | — | — | `PlatformPlayers.kt` 为 expect，需在 androidMain 单独核对 |
| NotSignedInPane / OpenSourceScreen | 不适用（静态内容） | 不适用 | 不适用 | 无网络请求 |
| App 顶层 · 注册状态开关 | ⚠（null 期间整行隐藏，无占位） | 不适用 | ✗ 失败靠 5s 轮询，无用户可见状态 | App.kt:216-226,385-394 |
