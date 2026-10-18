# 自动升级（服务端 + 手机端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让自托管实例每天自动跟到稳定版并在升级失败时自己退回原版本，让 Android 端自动把新 APK 下好并只留一次系统安装确认；同时把 `install.sh` 里两个产出完全一样的安装类型选项收成一个。

**Architecture:** 服务端自动升级跑在**宿主机**上而不是容器里——容器换不了自己的镜像。`scripts/barezen-update.sh` 是唯一决策体（问本机 `/api/version` → 判稳定版 → dump → 改 compose 标签 → `up -d` → 探 `/readyz` → 不健康就退回原标签），由 `install.sh` 装成 systemd `oneshot` service + 每日 timer。Android 端把「下载并校验」与「拉起系统安装页」拆成两个平台函数，WorkManager 周期任务只做前者并发通知，用户点通知才走后者。

**Tech Stack:** bash + systemd + docker compose（宿主机侧）；Kotlin Multiplatform / Compose Multiplatform、WorkManager、Ktor client（Android 侧）；无新增依赖、无新增测试框架。

## 关于本计划的拆分

三部分跨了三个子系统，且互不依赖，可以独立上线、独立回滚：

- **A. 宿主机侧**（Task 1-3）：install.sh 选项收敛 + 自动升级脚本 + timer。
- **B. 手机端**（Task 4-5）：平台层拆分 + 周期下载 + 通知。
- **C. 文档与验收**（Task 6-7）。

建议按 A → C → B 的顺序落地：A 上线后就能在你那台实例上真跑一次，B 需要真机才能验，不必互相等。

## Global Constraints

以下每一条对**所有** Task 隐含生效，值是规格原文，不要改写。

- 提交信息用简明中文，沿用 `fix(scope): 中文描述` / `feat(scope): ...` / `perf(db): ...` / `docs(...)` 风格；一次提交只做一件事。注释与提交信息里不得出现任何 AI、模型、代理、审查会话的痕迹（不写 Co-authored-by、不写 "Generated with"、不引用审查报告或日期）。
- 代码注释保持英文、零 emoji、零装饰性 unicode。
- 用户可见文案只能经 i18n 三件套：`app/shared/src/commonMain/kotlin/com/linan/barezen_drive/i18n/Strings.kt`（接口声明）+ `StringsZh.kt` + `StringsEn.kt`。Composable 与 Worker 里不得硬编码文案。
- 严禁 `git add -A` / `git add .`：每次提交前 `git status` 逐个确认，绝不把 `.env`、本地凭据、临时报告、AI 工作目录加进版本库。
- 四条门禁（Gradle 必须带沙箱 init 脚本，输出重定向到日志文件再读，不要管道接 tail）：
  ```
  ./gradlew --init-script /home/lin/tmp/barezen-init.gradle :server:test --rerun-tasks --console=plain
  ./gradlew --init-script /home/lin/tmp/barezen-init.gradle :core:allTests --rerun-tasks --console=plain
  ./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:testAndroidHostTest --console=plain
  ./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:compileKotlinWasmJs --console=plain
  ```
  `testAndroidHostTest` 是 shared 模块 Android 单测的正确任务名（`testAndroidHostDebugUnitTest` 不存在）。
- 不引入新依赖、不引入测试框架、不引入迁移框架。数据库演进仍是「只增列 + `DatabaseFactory` 启动 DDL」。**自动回滚之所以安全，前提就是这条**：旧镜像读得懂加了列的库。
- 不碰：`ui/glass/LiquidBottomTabs.kt`（玻璃底栏整块按裁定保留）、通知图标位图方案、iOS/Desktop target（保持未启用）。
- 本轮不改版本号、不打 tag、不发布 Release。
- beta 隔离口径：**不改 `update.json` 结构**，靠 GitHub `releases/latest` 天然排除 prerelease；代码里再加一道「只接受纯 `x.y.z`」的显式闸门作为第二道防线。
- 生产实例：`158.101.10.158:8080`，安装目录 `/opt/barezen`，compose 项目名 `barezen`，当前已是 `0.2.1`。SSH 用 `/home/lin/桌面/amd2.key`，用户 `ubuntu`，端口 22。非交互升级的正确姿势是 `bash install.sh --dir /opt/barezen --version <标签> </dev/null`（无 TTY 时脚本回落到既有 `.env` 答案）。

## 文件结构

**新建**

| 路径 | 责任 |
|---|---|
| `scripts/barezen-update.sh` | 唯一的自动升级决策体：查版本、判稳定、dump、改标签、探活、回滚。所有外部依赖走环境变量注入，便于桩测 |
| `scripts/tests/barezen-update-test.sh` | 用桩 `docker`/`curl` 跑上面那个脚本，断言调用序列与 compose 标签终态 |
| `scripts/tests/install-type-test.sh` | 抽出 `install.sh` 的 `install_type_of()` 纯函数跑断言 |
| `scripts/systemd/barezen-update.service` | oneshot，跑 `/usr/local/bin/barezen-update.sh`，`EnvironmentFile=-/etc/barezen-update.env` |
| `scripts/systemd/barezen-update.timer` | 每日 04:15 + 45 分钟随机抖动，`Persistent=true` |
| `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/update/UpdateAutoStage.kt` | 纯判定：这次检查该不该自动下包（不碰平台、不碰网络，可单测） |
| `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/update/UpdateAuto.kt` | WorkManager 登记入口 + 周期 Worker |
| `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/update/UpdateNotifier.kt` | 更新渠道、下载完成通知、点按意图 |
| `core/src/commonTest/kotlin/com/linan/barezen_drive/core/BuildInfoStableTest.kt` | `BuildInfo.isStable` 单测 |
| `app/shared/src/commonTest/kotlin/com/linan/barezen_drive/UpdateAutoStageTest.kt` | `shouldAutoStage` 单测 |

**修改**

| 路径 | 改什么 |
|---|---|
| `install.sh` | 新增 `install_type_of()`；第 308-317 行的安装类型问答收两项；中英文案 `type_web`/`type_server` 改写、`type_both` 删除；新增自动升级问答与 timer 装/拆；`.env` 增加 `AUTO_UPDATE` |
| `core/src/commonMain/kotlin/com/linan/barezen_drive/core/BuildInfo.kt` | 新增 `isStable(version: String): Boolean` |
| `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.kt` | 新增 `stageUpdateApk` / `installStagedUpdate` 两个 expect |
| `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.android.kt` | `downloadOne` 拆出可复用的 stage 路径；`installApk` 接到 `installStagedUpdate` |
| `app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.wasm.kt` | 两个新 expect 的 Web 实现（Web 走 reload，不存在装包） |
| `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/local/AppPrefs.kt`（+ android/wasm 两份 actual） | 新增 `stagedUpdateVersion: String` |
| `app/androidApp/src/main/kotlin/com/linan/barezen_drive/BareZenApp.kt` | 启动时登记自动更新任务 |
| `app/androidApp/src/main/kotlin/com/linan/barezen_drive/MainActivity.kt` | 处理通知点按带来的「装这个包」意图 |
| `docs/usage.md` / `docs/usage.en.md` | 自动升级章节（开关、日志、回滚条件、手动升级仍可用） |
| `docs/development.md` | `scripts/tests/` 两个新测试怎么跑 |
| `docs/architecture.md` | 部署段补一句宿主机 timer |
| `docs/roadmap.md` | v0.2 条目 |

---

## Task 1: install.sh 安装类型收成两项

`INSTALL_TYPE` 在脚本里只有 `= web` / `!= web` 两种判断（第 321、356、459、507、526、735、753、771 行），服务端与客户端代码完全不读它，所以旧选项 2「仅服务器」与选项 3「服务器 + Web」产出的是同一套东西。顺带修一个真 bug：旧默认值是 3，非交互重跑会把一个 web-only 安装悄悄变成带服务器的安装。

**Files:**
- Modify: `install.sh`（在 `env_get` 之后插入函数；替换第 305-317 行问答；改第 128-131、178-181 行文案）
- Test: `scripts/tests/install-type-test.sh`（新建）

**Interfaces:**
- Consumes: 无
- Produces: `install_type_of <choice> <existing>` → stdout `web` 或 `server`；`INSTALL_TYPE` 取值集合变成 `{web, server}`，历史值 `both` 仍被正确当作「服务端」处理

- [ ] **Step 1: 写失败的测试**

新建 `scripts/tests/install-type-test.sh`：

```bash
#!/usr/bin/env bash
# install.sh has no test harness and cannot be sourced (sourcing runs it), so
# the one decision worth pinning down - answer -> INSTALL_TYPE - is extracted as
# a self-contained function and evaluated here.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INSTALL_SH="$HERE/../../install.sh"

eval "$(sed -n '/^install_type_of()/,/^}/p' "$INSTALL_SH")"
# The extraction must actually have found the function, or every assertion below
# would pass by calling nothing.
type install_type_of >/dev/null 2>&1 || { echo "FAIL: install_type_of not found in install.sh" >&2; exit 1; }

fail() { echo "FAIL: $1" >&2; exit 1; }
check() { # check <answer> <existing> <expected> <label>
  got="$(install_type_of "$1" "$2")"
  [ "$got" = "$3" ] || fail "$label: answer='$1' existing='$2' gave '$got', want '$3'"
  echo "ok - $label"
}

check 1 ""    web    "1 on a fresh install is web"
check 2 ""    server "2 on a fresh install is server"
check ""  ""  server "blank on a fresh install is server (the recommended one)"
check ""  web web    "blank on an existing web install stays web"
check ""  both server "legacy 'both' reads as server, not as a third thing"
check 9 ""    server "an out-of-range answer falls back to server"

# Only two options are offered now; the third one must be gone from both languages.
if grep -q 'type_both)' "$INSTALL_SH"; then fail "type_both is still in the string tables"; fi
if grep -q 'type_choice:-3' "$INSTALL_SH"; then fail "the default answer is still the removed option 3"; fi
echo "all install-type assertions passed"
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `bash scripts/tests/install-type-test.sh`
Expected: FAIL，报 `install_type_of not found in install.sh`

- [ ] **Step 3: 加纯函数**

在 `install.sh` 的 `env_get()` 定义之后插入：

```bash
# install_type_of <answer> <existing> - the wizard answer mapped to INSTALL_TYPE.
#
# Only `web` is a different deployment: it ships a static bundle that talks to
# somebody else's server. The old `server` / `both` split produced byte-identical
# output (every branch in this script tests `= web` or `!= web`, and neither the
# server nor the client reads the value), so it is gone.
#
# A blank answer keeps whatever the instance already runs. That is what makes a
# non-interactive re-run safe: the previous default was `both`, so piping this
# script without a terminal silently moved a web-only install onto a server.
install_type_of() {
  case "$1" in
    1) printf 'web' ;;
    2) printf 'server' ;;
    *) if [ "$2" = "web" ]; then printf 'web'; else printf 'server'; fi ;;
  esac
}
```

- [ ] **Step 4: 换掉问答**

把第 305-317 行（`# ---- step 2: install type ----` 到该段的 `echo`）替换为：

```bash
# ---- step 2: install type ----
echo "$(t type)"
t type_web; t type_server
ask "> " type_choice || type_choice=""
INSTALL_TYPE="$(install_type_of "${type_choice:-}" "$(env_get INSTALL_TYPE "$PRE_ENV")")"
echo
```

`PRE_ENV` 在本段之前已经赋好（语言步骤末尾），所以这里直接读得到。

- [ ] **Step 5: 改两份文案**

中文串表（原第 128-131 行）：

```bash
      type)          echo "选择安装类型:" ;;
      type_web)      echo "  1) 仅 Web 静态端（连一台远程服务器，本机不存数据）" ;;
      type_server)   echo "  2) 服务端（含 Web 端与本机数据库，推荐）" ;;
```

英文串表（原第 178-181 行）：

```bash
      type)          echo "Choose the install type:" ;;
      type_web)      echo "  1) Web static only (talks to a remote server, stores nothing here)" ;;
      type_server)   echo "  2) Server (includes the web client and a local database, recommended)" ;;
```

两处都删掉 `type_both)` 那一行。

- [ ] **Step 6: 跑测试，确认通过**

Run: `bash scripts/tests/install-type-test.sh`
Expected: `all install-type assertions passed`

- [ ] **Step 7: 语法与既有行为自查**

```bash
bash -n install.sh
grep -n 'INSTALL_TYPE' install.sh
```
Expected: `bash -n` 无输出；剩下的 `INSTALL_TYPE` 判断只出现 `= web` / `!= web` 两种形态。

- [ ] **Step 8: 提交**

```bash
git add install.sh scripts/tests/install-type-test.sh
git status --short
git commit -m "$(cat <<'EOF'
fix(install): 安装类型收成两项，非交互重跑不再改安装形态
脚本里所有分支只判断 = web 或 != web，服务端与客户端也都不读 INSTALL_TYPE，所以
「仅服务器」和「服务器 + Web」产出的是同一套东西，选项 3 只是把 2 又写了一遍。
收两项。顺带修一个真问题：默认值原本硬写 3，没有 TTY 时（curl | bash、CI）读入
即空，会把一台 web-only 安装悄悄变成带服务器的安装；现在空答案沿用 .env 里已有的
形态。历史值 both 仍按服务端处理。
EOF
)"
```

---

## Task 2: 宿主机自动升级脚本

**Files:**
- Create: `scripts/barezen-update.sh`
- Test: `scripts/tests/barezen-update-test.sh`

**Interfaces:**
- Consumes: 本机 `GET /api/version`（无需鉴权，返回 `serverVersion` / `latestVersion`）、`$DIR/docker-compose.yml`、`$DIR/.env` 的 `POSTGRES_USER` / `POSTGRES_DB`
- Produces: 退出码 `0` = 无事发生或升级成功；`1` = 已回滚（或回滚也失败）。stdout 每行一条带 UTC 时间戳的日志（进 journal）。读的环境变量：`BAREZEN_DIR`、`BAREZEN_COMPOSE`、`BAREZEN_PROBE_URL`、`BAREZEN_STATE_DIR`、`BAREZEN_HEALTH_TRIES`、`BAREZEN_HEALTH_DELAY`

- [ ] **Step 1: 写失败的测试**

新建 `scripts/tests/barezen-update-test.sh`：

```bash
#!/usr/bin/env bash
# The rollback path of an unattended updater cannot be exercised by waiting for
# a real upgrade to go wrong, so docker and curl are stubbed on PATH and the
# assertions run against the recorded call sequence and the final compose pin.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
UPDATER="$HERE/../barezen-update.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
has() { grep -qF -- "$2" "$1/calls.log"; }
hasnt() { ! has "$@"; }

new_env() { # new_env <pinned-tag> -> prints the fake install dir
  d="$(mktemp -d)"
  mkdir -p "$d/bin" "$d/state"
  cat > "$d/docker-compose.yml" <<EOF
services:
  server:
    image: ghcr.io/linanwanttodo/barezen-drive:$1
  db:
    image: postgres:16-alpine
EOF
  printf 'POSTGRES_USER=barezen\nPOSTGRES_DB=barezen\n' > "$d/.env"
  cat > "$d/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$CALLS"
case "$*" in
  *"exec -T db pg_dump"*)
    [ -n "$FAIL_DUMP" ] && { echo 'pg_dump: boom' >&2; exit 1; }
    printf -- '-- dump\n' ;;
esac
exit 0
EOF
  cat > "$d/bin/curl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"/api/version"*) [ -n "$VERSION_JSON" ] && printf '%s' "$VERSION_JSON"; exit 0 ;;
  *"/readyz"*)
    # Only the version named in FAIL_READY_VER is unhealthy, so a rollback can be
    # observed going bad and the restored pin observed going good.
    if [ -n "$FAIL_READY_VER" ] && grep -qF "barezen-drive:$FAIL_READY_VER" "$COMPOSE_FILE"; then
      exit 22
    fi
    printf 'ok' ;;
esac
exit 0
EOF
  chmod +x "$d/bin/docker" "$d/bin/curl"
  printf '%s' "$d"
}

run_case() { # run_case <dir> <version-json> [extra env assignments...]
  d="$1"; json="$2"; shift 2
  PATH="$d/bin:$PATH" \
  CALLS="$d/calls.log" \
  COMPOSE_FILE="$d/docker-compose.yml" \
  VERSION_JSON="$json" \
  BAREZEN_DIR="$d" BAREZEN_COMPOSE="docker" BAREZEN_PROBE_URL="http://stub" \
  BAREZEN_STATE_DIR="$d/state" BAREZEN_HEALTH_TRIES=2 BAREZEN_HEALTH_DELAY=0 \
  "$@" bash "$UPDATER" >"$d/out.log" 2>&1
}

pin_of() { sed -n 's#.*barezen-drive:##p' "$1/docker-compose.yml" | head -1; }

# 1. Nothing to do when the server already runs the newest stable release.
d="$(new_env 0.2.1)"
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.2.1"}'
hasnt "$d" "up -d" || fail "no-op case still recreated the container"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "no-op case moved the pin"
echo "ok - up to date does nothing"

# 2. A newer stable release is applied, with a dump first.
d="$(new_env 0.2.1)"
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}'
has "$d" "pull server"   || fail "no pull"
has "$d" "up -d server"  || fail "no recreate"
[ "$(pin_of "$d")" = "0.3.0" ] || fail "pin ended at $(pin_of "$d"), want 0.3.0"
ls "$d"/backups/auto-0.3.0-*.sql.gz >/dev/null 2>&1 || fail "no pre-upgrade dump"
grep -q "upgrading 0.2.1 -> 0.3.0" "$d/out.log" || fail "missing the upgrade line"
echo "ok - newer stable upgrades"

# 3. A pre-release is never applied unattended, even if a manifest names it.
d="$(new_env 0.2.1)"
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0-beta.1"}'
hasnt "$d" "up -d" || fail "a beta got applied"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "beta case moved the pin"
echo "ok - pre-release is skipped"

# 4. An upgrade that leaves /readyz answering 503 goes back to the old pin.
d="$(new_env 0.2.1)"
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_READY_VER=0.3.0 && fail "an unhealthy upgrade must exit non-zero"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "no rollback: pin is $(pin_of "$d")"
[ "$(grep -c 'up -d server' "$d/calls.log")" = "2" ] || fail "expected a second recreate for the rollback"
grep -q "rolling back to 0.2.1" "$d/out.log" || fail "missing the rollback line"
echo "ok - unhealthy upgrade rolls back"

# 5. No database dump, no upgrade: the point of no return was not reached.
d="$(new_env 0.2.1)"
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_DUMP=1 && fail "a failed dump must abort the upgrade"
hasnt "$d" "up -d" || fail "it upgraded without a backup"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "a failed dump still moved the pin"
echo "ok - failed dump aborts before touching anything"

# 6. An unreachable version endpoint is a quiet no-op, not a timer failure.
d="$(new_env 0.2.1)"
run_case "$d" ""
hasnt "$d" "up -d" || fail "it upgraded with nothing to read"
echo "ok - unreachable endpoint is skipped"

echo "all barezen-update assertions passed"
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `bash scripts/tests/barezen-update-test.sh`
Expected: FAIL（`bash: .../scripts/barezen-update.sh: No such file or directory`）

- [ ] **Step 3: 写脚本**

新建 `scripts/barezen-update.sh`：

```bash
#!/usr/bin/env bash
# Unattended stable-channel upgrade for one BareZen-Drive install.
#
# This runs on the host, not in the container: a container cannot replace the
# image it is running from, so anything that recreates the service has to live
# outside it. install.sh installs it as a systemd oneshot behind a daily timer,
# and it is also safe to run by hand.
#
# Rollback is only cheap because schema evolution here adds columns and never
# changes or drops one: a previous image can read a database the newer one
# migrated forward. That invariant is what this script's safety rests on, so if
# the day comes that a release renames a column or adds a constraint, unattended
# upgrade has to stop being automatic for that release.
#
# Every external touch point is an environment override, and
# scripts/tests/barezen-update-test.sh uses them to drive the rollback path with
# stubbed docker and curl. An updater whose only failure branch has to be waited
# for in production is not a tested updater.
set -euo pipefail

DIR="${BAREZEN_DIR:-/opt/barezen}"
COMPOSE="${BAREZEN_COMPOSE:-docker compose}"
PROBE="${BAREZEN_PROBE_URL:-http://127.0.0.1:8080}"
STATE="${BAREZEN_STATE_DIR:-$DIR/.auto-update}"
HEALTH_TRIES="${BAREZEN_HEALTH_TRIES:-30}"
HEALTH_DELAY="${BAREZEN_HEALTH_DELAY:-2}"
IMAGE_REPO="ghcr.io/linanwanttodo/barezen-drive"

log() { printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }

# current_tag <file> - the image tag this compose file pins.
current_tag() { sed -n "s#.*${IMAGE_REPO}:##p" "$1" | head -1 | tr -d '[:space:]'; }

# retag <file> <from> <to> - move the pin, keeping the line's indentation. Fails
# when <from> is not what the file says, so a concurrent edit cannot be steamrolled.
retag() {
  grep -qF "${IMAGE_REPO}:$2" "$1" || return 1
  sed -i "s#\(.*${IMAGE_REPO}:\)$2#\1$3#" "$1"
}

# stable <version> - accept a plain x.y.z only. GitHub's releases/latest already
# excludes pre-releases, so this is the second gate rather than the only one: a
# manifest pointed at somewhere else could still name 0.3.0-beta.1.
stable() {
  local v="${1#v}"
  case "$v" in *[0-9]) ;; *) return 1 ;; esac
  case "$v" in *-*|*+*) return 1 ;; esac
  case "$v" in *[!0-9.]*) return 1 ;; esac
  [ "$(printf '%s' "$v" | tr -cd '.' | wc -c)" = "2" ]
}

# newer <a> <b> - true when a sorts above b under version sort.
newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }

# healthy - /readyz answers. /health would answer 200 with the database down,
# which is precisely the state an upgrade must not be left in.
healthy() { curl -fsS "${PROBE}/readyz" >/dev/null 2>&1; }

mkdir -p "$STATE"
exec 9>"$STATE/lock"
flock -n 9 || { log "skipped: another update is already running"; exit 0; }

cd "$DIR"
[ -f docker-compose.yml ] || { log "fatal: no docker-compose.yml in $DIR"; exit 1; }

json="$(curl -fsS "$PROBE/api/version" 2>/dev/null || true)"
if [ -z "$json" ]; then log "skipped: $PROBE/api/version is unreachable"; exit 0; fi

running="$(printf '%s' "$json" | sed -n 's/.*"serverVersion":"\([^"]*\)".*/\1/p')"
latest="$(printf '%s' "$json" | sed -n 's/.*"latestVersion":"\([^"]*\)".*/\1/p')"
pinned="$(current_tag docker-compose.yml)"

if [ -z "$running" ]; then log "skipped: the response carried no serverVersion"; exit 0; fi
if [ -z "$latest" ]; then log "skipped: the server could not resolve any release"; exit 0; fi
if ! stable "$latest"; then log "skipped: $latest is not a stable release"; exit 0; fi
if ! newer "$latest" "$running"; then log "up to date: running $running, newest stable $latest"; exit 0; fi
if [ -z "$pinned" ]; then log "fatal: cannot read the pinned tag out of docker-compose.yml"; exit 1; fi

log "upgrading $running -> $latest (compose pin $pinned)"

DB_USER="$(sed -n 's/^POSTGRES_USER=//p' .env | tail -1)"; DB_USER="${DB_USER:-barezen}"
DB_NAME="$(sed -n 's/^POSTGRES_DB=//p' .env | tail -1)"; DB_NAME="${DB_NAME:-barezen}"

# Dump while the old version is still up and its schema is still the one the dump
# describes. Unattended, a missing backup is a reason to stop: the whole point of
# running this at 04:15 with nobody watching is that nothing can be undone by hand.
mkdir -p "$DIR/backups"
dump="$DIR/backups/auto-$latest-$(date -u +%Y%m%d-%H%M%S).sql.gz"
if ! $COMPOSE exec -T db pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$dump"; then
  rm -f "$dump"
  log "aborted: the database dump failed, so nothing was changed"
  exit 1
fi
log "database dumped to $dump"

retag docker-compose.yml "$pinned" "$latest" || { log "fatal: retag failed"; exit 1; }
if ! $COMPOSE pull server; then
  log "pull failed; restoring the previous pin"
  retag docker-compose.yml "$latest" "$pinned"
  exit 1
fi
$COMPOSE up -d server

ok=""
for _ in $(seq 1 "$HEALTH_TRIES"); do
  if healthy; then ok=1; break; fi
  sleep "$HEALTH_DELAY"
done
if [ -n "$ok" ]; then
  log "upgraded to $latest and /readyz is answering"
  exit 0
fi

log "not healthy after upgrading to $latest; rolling back to $pinned"
retag docker-compose.yml "$latest" "$pinned"
$COMPOSE pull server || true
$COMPOSE up -d server
for _ in $(seq 1 "$HEALTH_TRIES"); do
  if healthy; then log "rolled back to $pinned and healthy; the new release needs a look"; exit 1; fi
  sleep "$HEALTH_DELAY"
done
log "FATAL: the rollback to $pinned is not healthy either. Manual recovery: $dump holds the pre-upgrade database."
exit 1
```

赋可执行位：`chmod +x scripts/barezen-update.sh scripts/tests/barezen-update-test.sh`

- [ ] **Step 4: 跑测试，确认通过**

Run: `bash scripts/tests/barezen-update-test.sh`
Expected: `all barezen-update assertions passed`（六条 `ok - ...`）

- [ ] **Step 5: shell 语法自查**

Run: `bash -n scripts/barezen-update.sh && bash -n scripts/tests/barezen-update-test.sh`
Expected: 无输出

- [ ] **Step 6: 提交**

```bash
git add scripts/barezen-update.sh scripts/tests/barezen-update-test.sh
git status --short
git commit -m "$(cat <<'EOF'
feat(deploy): 宿主机自动升级脚本，失败自己退回原标签
容器换不了自己正在跑的镜像，所以自动升级只能住在宿主机上。scripts/barezen-update.sh
一条决策链：问本机 /api/version、只认纯 x.y.z（GitHub 的 releases/latest 本来就排除
prerelease，这里是第二道闸）、先 dump 再动、改 compose 标签、拉、up -d、探 /readyz，
不健康就把标签退回去再探一次。探 /readyz 而不是 /health，因为库断了 /health 照样 200。
dump 失败就直接放弃：凌晨四点没人盯着，没有备份的升级不该继续。
docker 与 curl 全走 PATH 上的桩跑测试，回滚这条分支不靠把真服务器搞坏来验证。
EOF
)"
```

---

## Task 3: systemd timer 与 install.sh 接线

**Files:**
- Create: `scripts/systemd/barezen-update.service`, `scripts/systemd/barezen-update.timer`
- Modify: `install.sh`（HTTPS 问答之后加一问；部署段之后加装/拆 timer；`.env` 写 `AUTO_UPDATE`；收尾提示加一行）
- Test: `scripts/tests/barezen-update-test.sh`（追加 timer 接线断言）

**Interfaces:**
- Consumes: `scripts/barezen-update.sh`（Task 2）、`$SUDO`、`$INSTALL_DIR`、`$COMPOSE`
- Produces: `/etc/systemd/system/barezen-update.{service,timer}`、`/usr/local/bin/barezen-update.sh`、`/etc/barezen-update.env`、`.env` 里的 `AUTO_UPDATE=yes|no`

- [ ] **Step 1: 写两个单元（静态，不含实例路径）**

`scripts/systemd/barezen-update.service`：

```ini
[Unit]
Description=BareZen-Drive stable-channel self update
Documentation=https://github.com/linanwanttodo/BareZen-Drive
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
# The instance directory and the compose command come from here, written by
# install.sh, so this unit stays correct for any install location without being
# templated at install time.
EnvironmentFile=-/etc/barezen-update.env
ExecStart=/usr/local/bin/barezen-update.sh
# A pull plus two health windows on a 1-core box: generous enough that the unit
# is not killed mid-upgrade, bounded enough that a hung pull cannot hold the lock
# forever.
TimeoutStartSec=1800
```

`scripts/systemd/barezen-update.timer`：

```ini
[Unit]
Description=Daily BareZen-Drive update check

[Timer]
OnCalendar=*-*-* 04:15:00
# A fixed minute would have every instance on the network pull the manifest at
# the same second; the jitter is the whole point of the delay.
RandomizedDelaySec=45m
Persistent=true

[Install]
WantedBy=timers.target
```

`Persistent=true` 的取舍要写进注释吗——不：它本身就是"错过的运行在下次开机补一次"，unit 里那行注释已经说清。

- [ ] **Step 2: install.sh 加问答**

在 HTTPS 那段结束（第 411 行 `fi` 之后、`HOST_IP=` 之前）插入：

```bash
# ---- step 6b: unattended upgrades ----
# Default to the setting already on file, so a non-interactive re-run does not
# quietly turn a hand-driven install into an auto-updating one (or the reverse).
PRE_AUTO="$(env_get AUTO_UPDATE "$PRE_ENV")"
echo "$(t auto_update)"
ask "$(t auto_update_q) [$([ "${PRE_AUTO:-yes}" = no ] && echo n || echo Y)]: " auto_choice || auto_choice=""
case "${auto_choice:-${PRE_AUTO:-yes}}" in
  n|N|no|NO|false) AUTO_UPDATE="no" ;;
  *) AUTO_UPDATE="yes" ;;
esac
echo
```

两份串表各加两条（中文块放在 `cert_skip` 附近，英文块同位置）：

```bash
      auto_update)   echo "自动升级：宿主机每天检查一次稳定版，自己升级并在不健康时自动回滚。" ;;
      auto_update_q) echo "开启自动升级？(Y/n): " ;;
```

```bash
      auto_update)   echo "Auto-update: the host checks for a new stable release daily, upgrades itself, and rolls back if the service does not come back." ;;
      auto_update_q) echo "Enable auto-update? (Y/n): " ;;
```

- [ ] **Step 3: `.env` 写入**

`.env` 的 heredoc（第 478 行附近，`INSTALL_TYPE=${INSTALL_TYPE}` 之后）加一行：

```bash
AUTO_UPDATE=${AUTO_UPDATE}
```

已有 `.env` 的分支里（第 527-530 行 `env_set INSTALL_TYPE ...` 旁边）加：

```bash
env_set AUTO_UPDATE "$AUTO_UPDATE" .env
```

- [ ] **Step 4: 部署段装/拆 timer**

在健康探测通过之后、收尾 `echo` 之前（第 748 行 `echo` 前）插入：

```bash
# ---- unattended upgrade wiring ----
# Only an instance with its own database can be upgraded this way: a web-only
# install has nothing to pull, and dumping a database it does not own would fail.
if [ "$INSTALL_TYPE" != "web" ] && [ -n "$HAS_OWN_DB" ]; then
  UNIT_DIR=/etc/systemd/system
  if [ "$AUTO_UPDATE" = "yes" ]; then
    log "$(t auto_on)"
    curl -fsSL -o /tmp/bz-update.sh "${REPO_RAW}/scripts/barezen-update.sh" \
      || err "could not download scripts/barezen-update.sh"
    curl -fsSL -o /tmp/bz-update.service "${REPO_RAW}/scripts/systemd/barezen-update.service" \
      || err "could not download the systemd service unit"
    curl -fsSL -o /tmp/bz-update.timer "${REPO_RAW}/scripts/systemd/barezen-update.timer" \
      || err "could not download the systemd timer unit"
    $SUDO install -m 0755 /tmp/bz-update.sh /usr/local/bin/barezen-update.sh
    $SUDO install -m 0644 /tmp/bz-update.service "$UNIT_DIR/barezen-update.service"
    $SUDO install -m 0644 /tmp/bz-update.timer "$UNIT_DIR/barezen-update.timer"
    # The units are location-agnostic; this file is what makes them point here.
    printf 'BAREZEN_DIR=%s\nBAREZEN_COMPOSE=%s\n' "$INSTALL_DIR" "$COMPOSE" \
      | $SUDO tee /etc/barezen-update.env >/dev/null
    rm -f /tmp/bz-update.sh /tmp/bz-update.service /tmp/bz-update.timer
    $SUDO systemctl daemon-reload
    $SUDO systemctl enable --now barezen-update.timer
  else
    log "$(t auto_off)"
    # Turning it off has to actually stop it, and the units go with it so a later
    # `systemctl list-timers` does not show a timer that does nothing.
    $SUDO systemctl disable --now barezen-update.timer 2>/dev/null || true
    $SUDO rm -f "$UNIT_DIR/barezen-update.service" "$UNIT_DIR/barezen-update.timer" /usr/local/bin/barezen-update.sh /etc/barezen-update.env
    $SUDO systemctl daemon-reload 2>/dev/null || true
  fi
fi
```

对应串表（中文 / 英文各两条）：

```bash
      auto_on)   echo "已开启自动升级：每天检查稳定版，升级后不健康会自动回滚" ;;
      auto_off)  echo "已关闭自动升级：只在你手动运行 install.sh 时更新" ;;
```

```bash
      auto_on)   echo "Auto-update enabled: a new stable release is applied daily, rolled back if the service does not come back" ;;
      auto_off)  echo "Auto-update disabled: upgrades happen only when you run install.sh" ;;
```

- [ ] **Step 5: 收尾提示加一行**

在 `tip done_backup` 附近加一条中文/英文串并打印：

```bash
tip auto_status
```

```bash
      auto_status) echo "自动升级: systemctl status barezen-update.timer && journalctl -u barezen-update.service -n 50" ;;
```

（英文串同义：`Auto-update: systemctl status barezen-update.timer ...`）

- [ ] **Step 6: 给测试加接线断言**

在 `scripts/tests/barezen-update-test.sh` 末尾（`echo "all ..."` 之前）追加：

```bash
# --- install.sh wiring: the units must exist, and the script must install both
# of them only when the instance owns its database.
if [ ! -f "$HERE/../../scripts/systemd/barezen-update.service" ]; then fail "service unit missing"; fi
if [ ! -f "$HERE/../../scripts/systemd/barezen-update.timer" ]; then fail "timer unit missing"; fi
grep -q "barezen-update.timer" "$HERE/../../install.sh" || fail "install.sh never touches the timer"
grep -q "EnvironmentFile=-/etc/barezen-update.env" "$HERE/../../scripts/systemd/barezen-update.service" \
  || fail "the service does not read the per-instance env file"
grep -q "systemctl disable --now barezen-update.timer" "$HERE/../../install.sh" \
  || fail "turning auto-update off does not stop the timer"
echo "ok - timer wiring present"
```

- [ ] **Step 7: 跑测试**

Run: `bash scripts/tests/barezen-update-test.sh && bash -n install.sh`
Expected: `ok - timer wiring present` + `all barezen-update assertions passed`，`bash -n` 无输出

- [ ] **Step 8: 提交**

```bash
git add scripts/systemd/barezen-update.service scripts/systemd/barezen-update.timer install.sh scripts/tests/barezen-update-test.sh
git status --short
git commit -m "$(cat <<'EOF'
feat(deploy): 把自动升级装成 systemd timer，关掉时真的拆掉
单元文件保持与路径无关，实例目录走 /etc/barezen-update.env，这样同一份 unit 在任何
--dir 下都对。开与关都做成可重跑的：关的时候 disable --now 加删单元文件，不留一个
什么都不做的 timer 在 list-timers 里挂着。只有带本机数据库的实例才接线，web 静态端
没东西可拉，去 dump 别人的库更是错的。默认值沿用 .env 里已有的设置，非交互重跑不会
悄悄把一台手动升级的机器变成自动升级。
EOF
)"
```

---

## Task 4: 平台层拆分「下载」与「装包」，加稳定版判定

现在 `downloadAndInstallUpdate` 一走完就 `installApk`，所以后台任务没法用它——后台不该突然弹出系统安装页。拆成两段，纯判定放 commonMain 好测。

**Files:**
- Modify: `core/src/commonMain/kotlin/com/linan/barezen_drive/core/BuildInfo.kt`
- Modify: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.kt`
- Modify: `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.android.kt`
- Modify: `app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.wasm.kt`
- Create: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/update/UpdateAutoStage.kt`
- Modify: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/local/AppPrefs.kt` 与它的 android / wasm 两份 actual
- Test: `core/src/commonTest/kotlin/com/linan/barezen_drive/core/BuildInfoStableTest.kt`、`app/shared/src/commonTest/kotlin/com/linan/barezen_drive/UpdateAutoStageTest.kt`

**Interfaces:**
- Consumes: `UpdateStatus.Available(version, releaseUrl, reloadOnly, downloadUrl, fallbackUrl, sha256, size)`（现有）
- Produces:
  - `BuildInfo.isStable(version: String): Boolean`
  - `expect suspend fun stageUpdateApk(urls: List<String>, sha256: String? = null, size: Long? = null, onProgress: (Float) -> Unit = {}): String?`
  - `expect fun installStagedUpdate(path: String): Boolean`
  - `fun shouldAutoStage(status: UpdateStatus, currentVersion: String, stagedVersion: String?): UpdateStatus.Available?`
  - `AppPrefs.stagedUpdateVersion: String`

- [ ] **Step 1: 写 isStable 的失败测试**

`core/src/commonTest/kotlin/com/linan/barezen_drive/core/BuildInfoStableTest.kt`：

```kotlin
package com.linan.barezen_drive.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The unattended upgrade path accepts plain x.y.z only, so the pre-release forms
 * a beta build carries must be rejected here as well as by GitHub's
 * releases/latest endpoint.
 */
class BuildInfoStableTest {

    @Test
    fun plainThreePartVersionsAreStable() {
        assertEquals(true, BuildInfo.isStable("0.2.1"))
        assertEquals(true, BuildInfo.isStable("v1.10.0"))
        assertEquals(true, BuildInfo.isStable("10.0.3"))
    }

    @Test
    fun preReleaseAndBuildSuffixesAreNotStable() {
        assertEquals(false, BuildInfo.isStable("0.3.0-beta.1"))
        assertEquals(false, BuildInfo.isStable("0.3.0-rc1"))
        assertEquals(false, BuildInfo.isStable("0.3.0+build7"))
    }

    @Test
    fun malformedVersionsAreNotStable() {
        assertEquals(false, BuildInfo.isStable(""))
        assertEquals(false, BuildInfo.isStable("0.3"))
        assertEquals(false, BuildInfo.isStable("0.3.0.1"))
        assertEquals(false, BuildInfo.isStable("0..3"))
        assertEquals(false, BuildInfo.isStable("latest"))
    }
}
```

- [ ] **Step 2: 跑，确认编译失败**

Run: `./gradlew --init-script /home/lin/tmp/barezen-init.gradle :core:allTests --console=plain > /home/lin/tmp/bz-t4-isstable.log 2>&1; grep -E "^e: |FAILED|BUILD" /home/lin/tmp/bz-t4-isstable.log | head`
Expected: `e: ... unresolved reference: isStable`

- [ ] **Step 3: 实现 isStable**

在 `core/src/commonMain/kotlin/com/linan/barezen_drive/core/BuildInfo.kt` 里、`isNewer` 之后加：

```kotlin
    /**
     * True only for a plain `x.y.z`. A name with a `-beta`/`+build` suffix is a
     * test build, and nothing that runs unattended should install one: the
     * release endpoint already filters these out, so this is the same rule
     * checked again where the consequence of getting it wrong is a server nobody
     * is watching.
     */
    fun isStable(version: String): Boolean {
        val v = version.trim().removePrefix("v")
        if (v.isEmpty()) return false
        if (v.any { !(it.isDigit() || it == '.') }) return false
        val parts = v.split('.')
        return parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }
    }
```

- [ ] **Step 4: 跑，确认通过**

Run: `./gradlew --init-script /home/lin/tmp/barezen-init.gradle :core:allTests --console=plain > /home/lin/tmp/bz-t4-isstable.log 2>&1; grep -E "FAILED|BUILD" /home/lin/tmp/bz-t4-isstable.log | head -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 写 shouldAutoStage 的失败测试**

`app/shared/src/commonTest/kotlin/com/linan/barezen_drive/UpdateAutoStageTest.kt`：

```kotlin
package com.linan.barezen_drive

import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.data.update.shouldAutoStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The decision a background pass makes on its own, so it is tested as a pure
 * function: what may be fetched without the user asking, and what must not.
 */
class UpdateAutoStageTest {

    private fun available(version: String, url: String? = "https://example/app.apk") =
        UpdateStatus.Available(
            version = version,
            releaseUrl = "https://example/release",
            reloadOnly = false,
            downloadUrl = url,
            sha256 = "ab",
            size = 12L,
        )

    @Test
    fun aStableNewerPackageWithAUrlIsStaged() {
        val got = shouldAutoStage(available("0.3.0"), currentVersion = "0.2.1", stagedVersion = null)
        assertEquals("0.3.0", got?.version)
    }

    @Test
    fun aPreReleaseIsNeverStagedInBackground() {
        assertNull(shouldAutoStage(available("0.3.0-beta.2"), "0.2.1", null))
    }

    @Test
    fun aVersionThatIsNotNewerIsNotStaged() {
        assertNull(shouldAutoStage(available("0.2.1"), currentVersion = "0.2.1", stagedVersion = null))
        assertNull(shouldAutoStage(available("0.1.0"), currentVersion = "0.2.1", stagedVersion = null))
    }

    @Test
    fun noPackageForThisPlatformMeansNothingToStage() {
        assertNull(shouldAutoStage(available("0.3.0", url = null), "0.2.1", null))
    }

    @Test
    fun aWebClientReloadsInsteadOfInstalling() {
        val reload = UpdateStatus.Available("0.3.0", "https://example/release", reloadOnly = true)
        assertNull(shouldAutoStage(reload, "0.2.1", null))
    }

    @Test
    fun theSameVersionIsNotFetchedTwice() {
        assertNull(shouldAutoStage(available("0.3.0"), currentVersion = "0.2.1", stagedVersion = "0.3.0"))
    }

    @Test
    fun aFailedCheckStagesNothing() {
        assertNull(shouldAutoStage(UpdateStatus.Failed, "0.2.1", null))
    }
}
```

- [ ] **Step 6: 跑，确认失败**

Run: `./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:testAndroidHostTest --console=plain > /home/lin/tmp/bz-t4-stage.log 2>&1; grep -E "^e: |FAILED" /home/lin/tmp/bz-t4-stage.log | head -3`
Expected: unresolved reference `shouldAutoStage`

- [ ] **Step 7: 实现纯判定**

`app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/update/UpdateAutoStage.kt`：

```kotlin
package com.linan.barezen_drive.data.update

import com.linan.barezen_drive.core.BuildInfo

/**
 * Whether a background pass may download the new package on its own.
 *
 * Deliberately narrow: a stable version that is actually newer, that has a
 * package for this platform, and that is not already sitting on disk. Anything
 * else - a beta, a web client that only needs a reload, a manifest that lists no
 * asset for this ABI - must leave the user exactly where they were. The one
 * thing this cannot decide is whether to *install*: Android shows its own
 * confirmation for that, and no background code gets to skip it.
 *
 * [stagedVersion] is what the last successful download left behind, so a daily
 * pass does not re-fetch 20 MB when nobody taps the notification.
 */
fun shouldAutoStage(
    status: UpdateStatus,
    currentVersion: String,
    stagedVersion: String?,
): UpdateStatus.Available? {
    val available = status as? UpdateStatus.Available ?: return null
    if (available.reloadOnly) return null
    if (available.downloadUrl == null) return null
    if (!BuildInfo.isStable(available.version)) return null
    if (!BuildInfo.isNewer(available.version, currentVersion)) return null
    if (stagedVersion == available.version) return null
    return available
}
```

- [ ] **Step 8: 拆分平台函数**

`AppUpdate.kt` 在 `downloadAndInstallUpdate` 声明之后加：

```kotlin
/**
 * Downloads and verifies the package without showing anything to the user, and
 * returns the absolute path of the staged file - or null when every mirror
 * failed. Split out of [downloadAndInstallUpdate] because a background pass must
 * not be able to pop the system installer: the file waits here until the user
 * taps the notification.
 */
expect suspend fun stageUpdateApk(
    urls: List<String>,
    sha256: String? = null,
    size: Long? = null,
    onProgress: (Float) -> Unit = {},
): String?

/**
 * Hands a file returned by [stageUpdateApk] to the OS installer. Returns false
 * when there is nothing to install (web, or a file that has since been cleaned
 * up). The user still confirms in the system sheet - that step is Android's, and
 * no caller here can or should skip it.
 */
expect fun installStagedUpdate(path: String): Boolean
```

`AppUpdate.android.kt`：把现有 `downloadAndInstallUpdate` 换成组合，`downloadOne` 改名并返回路径，`installApk` 由 `installStagedUpdate` 包一层。

```kotlin
actual suspend fun downloadAndInstallUpdate(
    urls: List<String>,
    sha256: String?,
    size: Long?,
    onProgress: (Float) -> Unit,
): Boolean {
    val staged = stageUpdateApk(urls, sha256, size, onProgress) ?: return false
    // A rejected installer intent is not a download failure: the bytes are
    // verified and on disk, and the next mirror would fetch the same package.
    runCatching { installStagedUpdate(staged) }
    return true
}

actual suspend fun stageUpdateApk(
    urls: List<String>,
    sha256: String?,
    size: Long?,
    onProgress: (Float) -> Unit,
): String? = withContext(Dispatchers.IO) {
    urls.firstNotNullOfOrNull { url ->
        runCatching { downloadOne(url, sha256, size, onProgress) }.getOrNull()
    }
}

actual fun installStagedUpdate(path: String): Boolean {
    val file = File(path)
    if (!file.isFile) return false
    runCatching { installApk(AndroidContext.app, file) }
    return true
}
```

`downloadOne` 的签名与结尾改为返回路径（其余逻辑一字不动）：

```kotlin
private fun downloadOne(
    url: String,
    sha256: String?,
    size: Long?,
    onProgress: (Float) -> Unit,
): String? {
```

结尾原来 `runCatching { installApk(context, destination) }; return true` 的两行改为：

```kotlin
    onProgress(1f)
    return destination.absolutePath
```

并删掉函数开头为安装准备的 `val context = AndroidContext.app`（`installStagedUpdate` 自己取）。

`AppUpdate.wasm.kt` 补两个 actual（Web 没有装包这回事）：

```kotlin
/** The web client ships with the server; a reload is the whole update. */
actual suspend fun stageUpdateApk(
    urls: List<String>,
    sha256: String?,
    size: Long?,
    onProgress: (Float) -> Unit,
): String? = null

actual fun installStagedUpdate(path: String): Boolean = false
```

- [ ] **Step 9: 加 prefs 槽位**

`AppPrefs.kt` 接口里（`albumBucketsReviewed` 之后）加：

```kotlin
    /** Version of the update package already sitting on disk, "" when none. */
    var stagedUpdateVersion: String
```

`AppPrefs.wasm.kt` 按现有 `username` 的写法加：

```kotlin
        override var stagedUpdateVersion: String
            get() = readString("staged_update_version") ?: ""
            set(v) = writeString("staged_update_version", v)
```

Android 那份 actual（`app/shared/src/androidMain/.../data/local/AppPrefs.android.kt`，用 `SharedPreferences`）按同文件里 `username` 的既有写法加同名属性，键名 `staged_update_version`，默认 `""`。

- [ ] **Step 10: 跑两条门禁**

```
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:testAndroidHostTest --console=plain > /home/lin/tmp/bz-t4-gate.log 2>&1
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:compileKotlinWasmJs --console=plain >> /home/lin/tmp/bz-t4-gate.log 2>&1
grep -E "^e: |FAILED|BUILD" /home/lin/tmp/bz-t4-gate.log
```
Expected: 两个 `BUILD SUCCESSFUL`

- [ ] **Step 11: 提交**

```bash
git add core/src/commonMain/kotlin/com/linan/barezen_drive/core/BuildInfo.kt \
        core/src/commonTest/kotlin/com/linan/barezen_drive/core/BuildInfoStableTest.kt \
        app/shared/src/commonMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.kt \
        app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/update/UpdateAutoStage.kt \
        app/shared/src/commonTest/kotlin/com/linan/barezen_drive/UpdateAutoStageTest.kt \
        app/shared/src/androidMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.android.kt \
        app/shared/src/wasmJsMain/kotlin/com/linan/barezen_drive/platform/AppUpdate.wasm.kt \
        app/shared/src/commonMain/kotlin/com/linan/barezen_drive/data/local/AppPrefs.kt
git status --short
git commit -m "$(cat <<'EOF'
refactor(update): 把下载与装包拆开，稳定版判定收成纯函数
原来 downloadAndInstallUpdate 下完就弹系统安装页，后台任务没法用它。拆成
stageUpdateApk（下载并校验，返回落盘路径）与 installStagedUpdate（把文件交给安装器），
老函数变成两者的组合，设置页行为不变。该不该自动下这件事收进 shouldAutoStage：稳定版、
确实更新、这个平台有包、之前没下过，四条都满足才动；Web 只 reload 不装包。判定条件里
带上「不是 beta」，与宿主机那条 x.y.z 的闸门同一口径。
EOF
)"
```

---

## Task 5: Android 周期检查、自动下载与通知

**Files:**
- Create: `app/shared/src/androidMain/kotlin/com/linan/barezen_drive/update/UpdateAuto.kt`, `.../update/UpdateAutoWorker.kt`, `.../update/UpdateNotifier.kt`
- Modify: `app/androidApp/src/main/kotlin/com/linan/barezen_drive/BareZenApp.kt`
- Modify: `app/androidApp/src/main/kotlin/com/linan/barezen_drive/MainActivity.kt`
- Modify: `app/shared/src/commonMain/kotlin/com/linan/barezen_drive/i18n/Strings.kt` / `StringsZh.kt` / `StringsEn.kt`

**Interfaces:**
- Consumes: `AppClient.files`、`TokenStorage.baseUrl` / `accessToken`、`BuildInfo.VERSION`、`AppPreferences.get().stagedUpdateVersion`、Task 4 的 `shouldAutoStage` / `stageUpdateApk` / `installStagedUpdate`
- Produces: `UpdateAuto.apply()`（幂等登记）、`UpdateAutoWorker`、`UpdateNotifier.notifyReady(version: String, path: String)`、`UpdateNotifier.EXTRA_STAGED_APK`

- [ ] **Step 1: 加 i18n 三处**

`Strings.kt`（接口，放在 `notifyChannelBackupDesc` 之后）：

```kotlin
    val notifyChannelUpdate: String
    val notifyChannelUpdateDesc: String
    fun notifyUpdateReady(version: String): String
    val notifyUpdateReadyText: String
```

`StringsZh.kt`：

```kotlin
    override val notifyChannelUpdate: String = "应用更新"
    override val notifyChannelUpdateDesc: String = "新版本下载完成时的提示"
    override fun notifyUpdateReady(version: String): String = "BareZen-Drive v$version 已下载好"
    override val notifyUpdateReadyText: String = "点按完成安装"
```

`StringsEn.kt`：

```kotlin
    override val notifyChannelUpdate: String = "App updates"
    override val notifyChannelUpdateDesc: String = "New versions downloaded in the background"
    override fun notifyUpdateReady(version: String): String = "BareZen-Drive v$version is downloaded"
    override val notifyUpdateReadyText: String = "Tap to install"
```

- [ ] **Step 2: 通知器**

`app/shared/src/androidMain/kotlin/com/linan/barezen_drive/update/UpdateNotifier.kt`：

```kotlin
package com.linan.barezen_drive.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.MainActivity
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.platform.TransferNotifier

/**
 * The one thing the background updater shows: the package is on disk and verified,
 * and the only step left is the system's own confirmation. IMPORTANCE_DEFAULT so
 * it makes a sound - a silent notification nobody reads would leave the phone on
 * an old build forever, which is exactly what this is here to prevent.
 */
object UpdateNotifier {

    const val CHANNEL = "app_update"
    const val EXTRA_STAGED_APK = "com.linan.barezen_drive.extra.STAGED_APK"
    private const val NOTIFICATION_ID = 4200

    fun ensureChannel() {
        val nm = AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, I18n.strings.notifyChannelUpdate, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = I18n.strings.notifyChannelUpdateDesc },
            )
        }
    }

    fun notifyReady(version: String, path: String) {
        ensureChannel()
        // The bitmap icon the app already installs for itself (see BareZenApp);
        // without one there is no notification to post, and that is a reason to
        // skip quietly rather than crash a background pass.
        val small = TransferNotifier.smallIcon() ?: return
        val intent = Intent(AndroidContext.app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_STAGED_APK, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // FLAG_IMMUTABLE is required from API 31 and accepted below it; without it
        // the tap throws on newer Android and the notification is a dead end.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(AndroidContext.app, NOTIFICATION_ID, intent, flags)
        val nm = AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(AndroidContext.app, CHANNEL)
                .setSmallIcon(small)
                .setContentTitle(I18n.strings.notifyUpdateReady(version))
                .setContentText(I18n.strings.notifyUpdateReadyText)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The package is installed or thrown away, so the prompt must not linger. */
    fun clear() {
        (AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }
}
```

图标走 `TransferNotifier.smallIcon()`：它返回的正是 `BareZenApp` 启动时装好的那张位图图标（没有时回落到 `applicationInfo.icon`），通知图标必须是位图 app 图标这条既有裁定由它一处守住，这里不再造任何资源。

- [ ] **Step 3: Worker**

`app/shared/src/androidMain/kotlin/com/linan/barezen_drive/update/UpdateAutoWorker.kt`：

```kotlin
package com.linan.barezen_drive.update

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.linan.barezen_drive.core.BuildInfo
import com.linan.barezen_drive.data.api.AppClient
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.data.update.UpdateChecker
import com.linan.barezen_drive.data.update.shouldAutoStage
import com.linan.barezen_drive.platform.stageUpdateApk

/**
 * Checks the instance for a new stable release and, when one exists, downloads
 * and verifies it. Stopping short of the install is the whole design: Android
 * owns that confirmation.
 *
 * A signed-out pass has nothing to ask, so it exits success without touching the
 * network - same guard the sync workers use.
 */
class UpdateAutoWorker(context: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (TokenStorage.baseUrl.isBlank() || TokenStorage.accessToken == null) return Result.success()
        val prefs = AppPreferences.get()
        val status = runCatching { UpdateChecker.check(AppClient.files) }.getOrNull() ?: return Result.retry()
        val target = shouldAutoStage(
            status = status,
            currentVersion = BuildInfo.VERSION,
            stagedVersion = prefs.stagedUpdateVersion,
        ) ?: return Result.success()

        val urls = listOfNotNull(target.downloadUrl, target.fallbackUrl)
        val staged = stageUpdateApk(urls, target.sha256, target.size)
            ?: return Result.retry()  // a dead mirror is worth another pass
        prefs.stagedUpdateVersion = target.version
        UpdateNotifier.notifyReady(target.version, staged)
        return Result.success()
    }

    companion object {
        const val WORK = "barezen-update-auto"
        /** One pass a day is enough: a release is published by hand, not hourly. */
        const val INTERVAL_HOURS = 24L
    }
}
```

- [ ] **Step 4: 登记入口**

`app/shared/src/androidMain/kotlin/com/linan/barezen_drive/update/UpdateAuto.kt`：

```kotlin
package com.linan.barezen_drive.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.linan.barezen_drive.AndroidContext
import java.util.concurrent.TimeUnit

/**
 * Registers the daily update pass. Called from `Application.onCreate`, like the
 * backup workers, so a process started by any job (or by the launcher) keeps the
 * schedule in place.
 *
 * `CONNECTED`, not `UNMETERED`: the operator asked for the package to be fetched
 * on any network. It costs about 20 MB once a day, and only when a stable release
 * actually exists - the check itself is a single small JSON.
 */
object UpdateAuto {

    fun apply() {
        val wm = WorkManager.getInstance(AndroidContext.app)
        val request = PeriodicWorkRequestBuilder<UpdateAutoWorker>(
            UpdateAutoWorker.INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        // UPDATE, not KEEP: the interval and constraints live here, and a build
        // that changes them must take effect on the next launch rather than
        // waiting out a schedule registered by the previous one.
        wm.enqueueUniquePeriodicWork(
            UpdateAutoWorker.WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UpdateAutoWorker.WORK)
    }
}
```

- [ ] **Step 5: 启动时登记**

`BareZenApp.kt` 的 `onCreate` 里，在现有 `MediaSync.apply(...)` 之后加：

```kotlin
        // The daily stable-release pass. Registered here rather than in an
        // Activity because WorkManager may cold-start this process with no
        // Activity at all.
        runCatching { UpdateAuto.apply() }
```

并加 import `com.linan.barezen_drive.update.UpdateAuto`。

- [ ] **Step 6: 点通知走安装**

`MainActivity.kt`：在 `onCreate` 末尾与 `onNewIntent` 里各调一次同一个私有方法：

```kotlin
    /**
     * A tapped "update ready" notification carries the verified package. This is
     * the only place the installer is launched from the background path, and it
     * happens because the user tapped - which is as far as an app may go.
     */
    private fun installFromNotification(intent: Intent?) {
        val path = intent?.getStringExtra(UpdateNotifier.EXTRA_STAGED_APK) ?: return
        intent.removeExtra(UpdateNotifier.EXTRA_STAGED_APK)
        if (!installStagedUpdate(path)) {
            // The file is gone (cleaned up, or the app was reinstalled): drop the
            // remembered version so the next pass fetches it again.
            AppPreferences.get().stagedUpdateVersion = ""
        }
        UpdateNotifier.clear()
    }
```

加 import：`android.content.Intent`、`com.linan.barezen_drive.data.local.AppPreferences`、`com.linan.barezen_drive.platform.installStagedUpdate`、`com.linan.barezen_drive.update.UpdateNotifier`。

- [ ] **Step 7: 跑两条门禁**

```
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:testAndroidHostTest --console=plain > /home/lin/tmp/bz-t5-gate.log 2>&1
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:compileKotlinWasmJs --console=plain >> /home/lin/tmp/bz-t5-gate.log 2>&1
grep -E "^e: |FAILED|BUILD" /home/lin/tmp/bz-t5-gate.log
```
Expected: 两个 `BUILD SUCCESSFUL`（`androidApp` 的编译由 `:app:shared` 之外的任务负责，下一步补）

- [ ] **Step 8: 编 androidApp**

Run: `./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:androidApp:assembleDebug --console=plain > /home/lin/tmp/bz-t5-apk.log 2>&1; grep -E "^e: |FAILED|BUILD" /home/lin/tmp/bz-t5-apk.log | head`
Expected: `BUILD SUCCESSFUL`（这一步才真正编译 `MainActivity` 与 `BareZenApp`）

- [ ] **Step 9: 提交**

```bash
git add app/shared/src/androidMain/kotlin/com/linan/barezen_drive/update/ \
        app/androidApp/src/main/kotlin/com/linan/barezen_drive/BareZenApp.kt \
        app/androidApp/src/main/kotlin/com/linan/barezen_drive/MainActivity.kt \
        app/shared/src/commonMain/kotlin/com/linan/barezen_drive/i18n/Strings.kt \
        app/shared/src/commonMain/kotlin/com/linan/barezen_drive/i18n/StringsZh.kt \
        app/shared/src/commonMain/kotlin/com/linan/barezen_drive/i18n/StringsEn.kt
git status --short
git commit -m "$(cat <<'EOF'
feat(android): 每天自动下好新稳定版，点通知才进安装页
WorkManager 每日一趟，问本机 /api/version，符合条件就把 APK 下完并校验 sha256，
然后只发一条通知。弹系统安装页这一步留给用户点那一下：Android 的确认框不是我们能
跳过的，后台任务突然冒出安装界面只会让人以为中了什么。约束用 CONNECTED 而不是
UNMETERED，按「任意网络都下」的口径来；一次约 20MB，且只在确实有新稳定版时发生。
记住已下好的版本号，通知没点就不会每天重下一遍。
EOF
)"
```

---

## Task 6: 文档

**Files:**
- Modify: `docs/usage.md`、`docs/usage.en.md`、`docs/development.md`、`docs/architecture.md`、`docs/roadmap.md`

**Interfaces:**
- Consumes: Task 1-5 的最终形态
- Produces: 文档与代码一致（本仓库的既有验收标准）

- [ ] **Step 1: usage.md 加「自动升级」小节**

放在现有升级段（`bash install.sh --dir ... --version ...` 那段）之后：

```markdown
### 自动升级（可选，默认开）

`install.sh` 会在宿主机装一个每天 04:15（带 45 分钟随机抖动）跑一次的 systemd 定时器：

```bash
systemctl status barezen-update.timer          # 有没有装、下次什么时候跑
journalctl -u barezen-update.service -n 50     # 上次做了什么
sudo systemctl disable --now barezen-update.timer   # 停掉
```

它只做**稳定版**：问本机 `/api/version`，拿 `releases/latest` 解析出来的版本（GitHub
这个端点本身就排除 pre-release，所以测试版不会进来），再要求版本号是纯 `x.y.z`。判定为
可升级时按这个顺序走：先 `pg_dump` 到 `<安装目录>/backups/auto-<版本>-<时间>.sql.gz` →
改 compose 标签 → `docker compose pull server` → `up -d server` → 探 `/readyz`。

三种情况它自己收场：dump 失败就**什么都不改**直接退出；`pull` 失败把标签退回去；升级后
`/readyz` 一直 503 就把标签改回原版本再拉一次、再探一次，并在日志里写明回滚结果。回滚只
换镜像标签，不动数据——schema 只增列，旧镜像读得懂新库。**如果哪天某个版本要改列类型或
加约束，这个自动升级必须先对该版本关掉**，回滚不再免费。

重跑 `install.sh` 时那一问答「n」即可彻底拆掉单元文件；`.env` 里的 `AUTO_UPDATE` 是它的答案，
非交互重跑会沿用旧值，不会悄悄改。

手机端（Android）另有一条：每天检查一次，遇到新的稳定版就在当前网络下把 APK 下好并校验，
然后发一条通知，点通知才进系统安装页。安装确认是 Android 的，去不掉。
```

- [ ] **Step 2: usage.en.md 同义段**

按 Step 1 的英文对应写同样五条：装/查/停的命令、只跟稳定版、dump→retag→pull→up→probe 的顺序、三种自收场、以及"改列类型那一天必须先关掉自动"。

- [ ] **Step 3: development.md 加测试跑法**

```markdown
### 宿主机脚本的测试

`install.sh` 与 `scripts/barezen-update.sh` 没有框架可依，用桩跑：

```bash
bash scripts/tests/install-type-test.sh
bash scripts/tests/barezen-update-test.sh
```

后者把 `docker` 与 `curl` 换成临时目录里的桩，断言的是调用序列与 compose 标签的终态，
所以「升级后不健康要回滚」这条分支不需要真把一台服务器搞坏来验证。
```

- [ ] **Step 4: architecture.md 部署段补一句**

在部署那段的末尾加：

```markdown
自动升级住在宿主机而不是容器里（容器换不了自己的镜像）：`install.sh` 装
`scripts/barezen-update.sh` 到 `/usr/local/bin`，配一对 systemd 单元每天跑一次，只跟
`releases/latest` 的稳定版，失败自动退回原标签。
```

- [ ] **Step 5: roadmap.md**

在 v0.2 段末尾加一条 `- [x] 稳定版自动升级（2026-10-18。…）`，写清：宿主机 timer、dump 先行、`/readyz` 判活、自动回滚、只跟非 prerelease、Android 侧自动下载 + 通知安装、`install.sh` 安装类型收两项与默认值沿用现状。

- [ ] **Step 6: 提交**

```bash
git add docs/usage.md docs/usage.en.md docs/development.md docs/architecture.md docs/roadmap.md
git status --short
git commit -m "$(cat <<'EOF'
docs(auto-update): 写清自动升级的边界与那个必须先关它的条件
顺序、三种自收场、只跟稳定版、怎么查日志怎么停，都落到 usage 中英两份。
最要紧的一条写进文档也写进脚本注释：回滚之所以免费，全靠 schema 只增列不改列；
真到要改列类型或加约束的那天，自动升级必须对该版本先关掉，否则退回去的镜像读不懂库。
EOF
)"
```

---

## Task 7: 全门禁与生产实例验证

**Files:** 无（验证）

**Interfaces:**
- Consumes: Task 1-6 全部落地
- Produces: 四条门禁绿 + 一次真机真服务器验证记录

- [ ] **Step 1: 四条门禁**

```bash
cd /home/lin/All_projects/Javaproject/BareZen-Drive
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :server:test --rerun-tasks --console=plain > /home/lin/tmp/bz-t7-server.log 2>&1
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :core:allTests --rerun-tasks --console=plain > /home/lin/tmp/bz-t7-core.log 2>&1
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:testAndroidHostTest --console=plain > /home/lin/tmp/bz-t7-shared.log 2>&1
./gradlew --init-script /home/lin/tmp/barezen-init.gradle :app:shared:compileKotlinWasmJs --console=plain > /home/lin/tmp/bz-t7-wasm.log 2>&1
grep -H "BUILD" /home/lin/tmp/bz-t7-*.log
bash scripts/tests/install-type-test.sh && bash scripts/tests/barezen-update-test.sh
```
Expected: 四个 `BUILD SUCCESSFUL`，两个测试脚本 `all ... passed`

- [ ] **Step 2: 推上去，确认 CI 绿**

```bash
git push origin master
gh run list --branch master --limit 1
gh run watch <run-id> --exit-status
```

- [ ] **Step 3: 生产实例接线（这一步会改 158.101.10.158，逐条确认后再执行）**

```bash
ssh -i /home/lin/桌面/amd2.key -p 22 ubuntu@158.101.10.158 \
  'curl -fsSL -o /tmp/bz-install.sh https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/install.sh && \
   cd /opt/barezen && bash /tmp/bz-install.sh --dir /opt/barezen --version 0.2.1 </dev/null'
```
Expected: 结尾出现「已开启自动升级」，且服务仍健康。

- [ ] **Step 4: 验证 timer 与"无事发生"这条路径**

```bash
ssh -i /home/lin/桌面/amd2.key -p 22 ubuntu@158.101.10.158 \
  'systemctl list-timers barezen-update.timer --no-pager | head -3; \
   sudo /usr/local/bin/barezen-update.sh; echo "exit=$?"'
```
Expected: timer 已 enabled 且有下次触发时间；手动跑输出 `up to date: running 0.2.1, newest stable 0.2.1`，`exit=0`，`/opt/barezen/backups/` 不新增文件（没升级就不该 dump）。

- [ ] **Step 5: 验证升级分支真的会动（在实例上做一次受控的假升级，跑完即还原）**

```bash
ssh -i /home/lin/桌面/amd2.key -p 22 ubuntu@158.101.10.158 \
  'cd /opt/barezen && sudo sed -i "s#barezen-drive:0.2.1#barezen-drive:0.2.0#" docker-compose.yml && \
   BAREZEN_HEALTH_TRIES=3 sudo -E /usr/local/bin/barezen-update.sh; echo "exit=$?"; \
   grep -n "image:" docker-compose.yml | head -3'
```
Expected: 脚本把标签从 0.2.0 升到 0.2.1、留下一份 `auto-0.2.1-*.sql.gz`、探活通过、`exit=0`，`docker-compose.yml` 最终 pin 在 `0.2.1`，容器 `healthy`。这一步是整条链在真环境里唯一一次真跑，跑完实例状态与跑前一致。

- [ ] **Step 6: 手机端**

装 0.2.1 的 APK（设置页 → 检查更新 → 下载更新），确认：
- 下载完成后不弹安装页，只有一条通知；
- 点通知进系统安装页；
- `adb logcat -s WorkManager` 里能看到 `barezen-update-auto` 被登记。

---

## 验收清单（全部满足才算完）

- [ ] `install.sh` 只问两个安装类型，且非交互重跑不会改变已有实例的形态
- [ ] `bash scripts/tests/install-type-test.sh` 与 `bash scripts/tests/barezen-update-test.sh` 全过
- [ ] 生产实例上 `systemctl list-timers barezen-update.timer` 有下次时间，手动跑输出 `up to date`
- [ ] 受控假升级跑通：dump 落盘、标签前移、`/readyz` 通过、终态与跑前一致
- [ ] 四条门禁绿，CI 对本次 push 的 run 全绿
- [ ] Android：后台只下载并发通知，点通知才进安装页；同一版本不会被重复下载
- [ ] `docs/usage.md` / `usage.en.md` / `development.md` / `architecture.md` / `roadmap.md` 与代码一致
