#!/usr/bin/env bash
# BareZen-Drive interactive installer.
#
# One wizard, seven steps:
#   1. language (script i18n)
#   2. install type (web static / server)
#   3. admin account + password (seeded on the server's first boot)
#   4. service port
#   5. domain (blank = http://<host-ip>:<port>)
#   6. HTTPS with automatic certificate issuance and renewal (Caddy)
#   6b. auto-update (daily systemd timer, server installs only)
#   7. prints the deployed address and Successful!
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/install.sh | bash
#   bash install.sh --dir /opt/barezen --version latest
set -euo pipefail

# The documented usage pipes this script into bash, and in that mode stdin *is*
# the script: a plain `read` consumes the script's own remaining text instead of
# the user's answer. Reproducible with any script past bash's ~8KB read buffer -
# the first prompt never prints and every later answer arrives one step late.
# Reading the terminal explicitly makes `curl … | bash` behave like
# `bash install.sh`; with no terminal at all (CI, `printf … | bash`) it falls
# back to stdin so scripted installs still work.
TTY_IN=/dev/tty
# `-r` is not enough: the device node exists and is readable even with no
# controlling terminal (CI, `bash < script`), where opening it fails. Probe it
# by actually opening it, and fall back to stdin when that fails.
if ! { true > /dev/tty; } 2>/dev/null; then TTY_IN=/dev/stdin; fi

# ask <prompt> <varname> - one visible line of input. Returns non-zero when the
# input ended (EOF), which callers inside a retry loop must honour: a validation
# loop that keeps prompting on EOF never terminates.
ask() {
  local __reply=""
  if IFS= read -r -p "$1" __reply < "$TTY_IN"; then
    printf -v "$2" '%s' "$__reply"
  else
    printf -v "$2" '%s' ""
    return 1
  fi
}

# ask_secret <prompt> <varname> - same, without echoing (passwords)
ask_secret() {
  local __reply=""
  if IFS= read -r -s -p "$1" __reply < "$TTY_IN"; then
    printf -v "$2" '%s' "$__reply"
  else
    printf -v "$2" '%s' ""
    return 1
  fi
}

# Self-contained help: `$0` is `bash` when the script arrives on stdin, so the
# header comment cannot be the only copy.
usage() {
  cat <<'USAGE'
BareZen-Drive interactive installer.

One wizard, seven steps:
  1. language (script i18n)
  2. install type (web static / server)
  3. admin account + password (seeded on the server's first boot)
  4. service port
  5. domain (blank = http://<host-ip>:<port>)
  6. HTTPS with automatic certificate issuance and renewal (Caddy)
  6b. auto-update (daily systemd timer, server installs only)
  7. prints the deployed address and Successful!

Usage:
  curl -fsSL https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/install.sh | bash
  bash install.sh --dir /opt/barezen --version latest
USAGE
}

REPO_RAW="https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master"
GITHUB_REPO="linanwanttodo/BareZen-Drive"
INSTALL_DIR="/opt/barezen"
IMAGE_TAG="latest"

log() { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }
err() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*"; }

# env_get <key> <file> - value of the last KEY= line, empty when absent.
env_get() {
  [ -f "$2" ] || { printf '%s' ""; return 0; }
  sed -n "s#^$1=##p" "$2" | tail -1
}

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

# env_set <key> <value> <file> - rewrite a key in place, append it when missing.
# Re-running the wizard has to write back what it just asked for: keeping the
# old value would print an address the service is not listening on.
env_set() {
  local key="$1" value="$2" file="$3" tmp
  if [ -f "$file" ] && grep -q "^$key=" "$file"; then
    tmp="${file}.tmp"
    awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$file" > "$tmp" \
      && mv "$tmp" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

# unattended_update_supported - can this host run scripts/barezen-update.sh at
# all? On failure it leaves UPDATE_UNSUPPORTED naming what is missing. Four
# things, each one a hard dependency of the updater rather than a preference:
#   flock               two timer runs must not overlap, and it is the only
#                       serialisation the script has
#   GNU sed             the updater rewrites the compose pin with `sed -i`; BSD
#                       sed reads that as a missing backup suffix and refuses,
#                       which is the macOS/BSD case this check exists for
#   sort -V             version ordering, so 0.10 is not read as older than 0.9
#   /run/systemd/system there has to be an init system to hand the timer to, or
#                       `systemctl enable` would abort an install whose
#                       deployment already succeeded
# Nothing here is patched around: the updater runs on the Linux host that serves
# the containers, not on the machine that built them.
unattended_update_supported() {
  UPDATE_UNSUPPORTED=""
  local sorted
  command -v flock >/dev/null 2>&1 || UPDATE_UNSUPPORTED="$UPDATE_UNSUPPORTED flock"
  case "$(sed --version 2>/dev/null || true)" in *GNU*) ;; *) UPDATE_UNSUPPORTED="$UPDATE_UNSUPPORTED gnu-sed" ;; esac
  sorted="$(printf '1.10\n1.9\n' | sort -V 2>/dev/null || true)"
  [ "$sorted" = $'1.9\n1.10' ] || UPDATE_UNSUPPORTED="$UPDATE_UNSUPPORTED sort-V"
  [ -d /run/systemd/system ] || UPDATE_UNSUPPORTED="$UPDATE_UNSUPPORTED systemd"
  [ -z "$UPDATE_UNSUPPORTED" ] || return 1
}

# Options come before anything that touches the host: `--help` must not cost
# the operator a package install, and `--dir` has to be known before the first
# file gets written anywhere.
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) [ $# -ge 2 ] || err "missing value for --dir (see --help)"; INSTALL_DIR="$2"; shift 2 ;;
    --version) [ $# -ge 2 ] || err "missing value for --version (see --help)"; IMAGE_TAG="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) err "unknown option: $1 (see --help)" ;;
  esac
done

# ---------------------------------------------------------------------------
# i18n: t <key>  (zh default detected, switchable in step 1)
# ---------------------------------------------------------------------------
L="$(case "${LANG:-}" in zh*) echo zh ;; *) echo en ;; esac)"

t() {
  local key="$1"
  if [ "$L" = "zh" ]; then
    case "$key" in
      lang)          echo "选择脚本语言 / Select language:" ;;
      lang_set)      echo "已切换为中文。" ;;
      type)          echo "选择安装类型:" ;;
      type_web)      echo "  1) 仅 Web 静态端（连一台远程服务器，本机不存数据）" ;;
      type_server)   echo "  2) 服务端（含 Web 端与本机数据库，推荐）" ;;
      admin)         echo "设置管理员账号（首次启动自动创建，即实例所有者）" ;;
      admin_user)    echo "管理员用户名 (3-32 位字母/数字/下划线): " ;;
      admin_pass)    echo "管理员密码 (至少 8 位): " ;;
      admin_user_bad) echo "  用户名格式不正确，请重试。" ;;
      admin_pass_bad) echo "  密码至少 8 位，请重试。" ;;
      rerun_hint)    echo "检测到已有配置：直接回车即沿用 .env 里的当前值。" ;;
      port)          echo "服务端口:" ;;
      port_bad)      echo "  端口必须是数字，请重试。" ;;
      port_range)    echo "  端口需在 1-65535 之间，请重试。" ;;
      domain)        echo "绑定域名（留空则使用 http://服务器IP:端口）: " ;;
      domain_bad)    echo "  域名格式不正确（形如 drive.example.com，不要带 http://），请重试。" ;;
      port_busy)     echo "  80 或 443 已被其它进程占用，Caddy 无法监听并签发证书。请先释放端口，或改用不带域名的 HTTP 安装。" ;;
      other_data)    echo "本机已存在其它部署的数据库卷：" ;;
      other_data_2)  echo "换目录安装等于新建一套实例：数据不会自动带过来，管理员账号会在空库上重新创建。" ;;
      other_data_3)  echo "仍要继续？[y/N]: " ;;
      dumping)       echo "升级前备份数据库到 " ;;
      dump_failed)   echo "  数据库备份未完成（服务可能没在运行）。升级本身会继续，但请先手工确认备份可用。" ;;
      env_seed)      echo "  .env 缺少本次安装必需的项，已新生成：" ;;
      compose_bak)   echo "  本地的 docker-compose.yml 与线上版本不一致，已保留为 " ;;
      cancelled)     echo "已取消：没有启动或改动任何服务。" ;;
      cert)          echo "启用 HTTPS（Caddy 自动申请并续签 Let's Encrypt 证书）？[Y/n]: " ;;
      cert_skip)     echo "  未填域名，证书无法签发给纯 IP，将使用 HTTP。" ;;
      auto_update)   echo "自动升级：宿主机每天检查一次稳定版，自己升级并在不健康时自动回滚。" ;;
      auto_update_q) echo "开启自动升级？(Y/n): " ;;
      auto_web_skip) echo "  仅 Web 静态端：本机没有镜像标签需要升级，跳过自动升级。" ;;
      auto_deps)     echo "本机缺少自动升级需要的命令，已强制关闭自动升级，缺少：" ;;
      auto_on)       echo "已开启自动升级：每天检查稳定版，升级后不健康会自动回滚" ;;
      auto_off)      echo "已关闭自动升级：只在你手动运行 install.sh 时更新" ;;
      web_remote)    echo "Web 静态端不创建账号；打开页面后在登录框填写你的服务器地址。" ;;
      precheck_miss) echo "缺少必需命令: " ;;
      deps_install)  echo "检测到缺少依赖，正在自动安装:" ;;
      pkg_missing)   echo "无法识别包管理器，请手动安装:" ;;
      docker_install) echo "未检测到 Docker，是否现在安装（约需几分钟）？[Y/n]: " ;;
      dir_noaccess)  echo "无法创建安装目录，请用 sudo 运行或通过 --dir 指定可写目录" ;;
      building)      echo "正在下载并部署…" ;;
      done_title)    echo "部署完成 - Successful!" ;;
      done_url)      echo "访问地址: " ;;
      done_admin)    echo "管理员账号（首次登录使用）: " ;;
      done_data)     echo "数据目录: " ;;
      done_upgrade)  echo "升级: cd %DIR% && %COMPOSE% pull && %COMPOSE% up -d" ;;
      done_logs)     echo "日志: cd %DIR% && %COMPOSE% logs -f server" ;;
      done_reg)      echo "提示: 管理员账号已由本脚本创建（仅在新实例上生效）。" ;;
      done_reg_closed) echo "提示: 注册默认关闭，只有管理员能登录；要邀请别人请在 设置→服务器 打开注册。" ;;
      done_backup)   echo "备份: cd %DIR% && %COMPOSE% exec -T db pg_dump -U %DBUSER% %DBNAME% | gzip > backup.sql.gz" ;;
      auto_status)   echo "自动升级: systemctl status barezen-update.timer && journalctl -u barezen-update.service -n 50" ;;
      installing)    echo "正在下载 Web 静态包并启动…" ;;
      web_tag)       echo "Web 静态包版本:" ;;
      failed)        echo "服务未能在 120 秒内就绪，请查看上方日志。" ;;
    esac
  else
    case "$key" in
      lang)          echo "Select language / 选择脚本语言:" ;;
      lang_set)      echo "Language set to English." ;;
      type)          echo "Choose the install type:" ;;
      type_web)      echo "  1) Web static only (talks to a remote server, stores nothing here)" ;;
      type_server)   echo "  2) Server (includes the web client and a local database, recommended)" ;;
      admin)         echo "Admin account (created automatically on first boot; it owns the instance)" ;;
      admin_user)    echo "Admin username (3-32 letters/digits/underscore): " ;;
      admin_pass)    echo "Admin password (at least 8 characters): " ;;
      admin_user_bad) echo "  Invalid username, try again." ;;
      admin_pass_bad) echo "  At least 8 characters, try again." ;;
      rerun_hint)    echo "Existing configuration found: press Enter to keep the value already in .env." ;;
      port)          echo "Service port:" ;;
      port_bad)      echo "  The port must be numeric, try again." ;;
      port_range)    echo "  The port must be between 1 and 65535, try again." ;;
      domain)        echo "Domain to bind (blank = http://<host-ip>:<port>): " ;;
      domain_bad)    echo "  That does not look like a domain (drive.example.com, no http:// prefix), try again." ;;
      port_busy)     echo "  Something else already listens on 80 or 443, so Caddy cannot bind it and issue a certificate. Free the port, or install without a domain." ;;
      other_data)    echo "This host already has a database volume from another deployment:" ;;
      other_data_2)  echo "Installing into a new directory creates a separate instance: nothing is carried over, and the admin account is seeded against an empty database." ;;
      other_data_3)  echo "Continue anyway? [y/N]: " ;;
      dumping)       echo "Backing up the database to " ;;
      dump_failed)   echo "  The database backup did not complete (the service may not be running). The upgrade continues, but check that a usable backup exists." ;;
      env_seed)      echo "  .env was missing a key this install type needs - generated:" ;;
      compose_bak)   echo "  The local docker-compose.yml differed from the published one, kept as " ;;
      cancelled)     echo "Cancelled: no service was started or changed." ;;
      cert)          echo "Enable HTTPS (Caddy issues and renews Let's Encrypt certificates automatically)? [Y/n]: " ;;
      cert_skip)     echo "  No domain given: a certificate cannot be issued for a bare IP, using HTTP." ;;
      auto_update)   echo "Auto-update: the host checks for a new stable release daily, upgrades itself, and rolls back if the service does not come back." ;;
      auto_update_q) echo "Enable auto-update? (Y/n): " ;;
      auto_web_skip) echo "  Static web only: this host pins no image to upgrade, skipping auto-update." ;;
      auto_deps)     echo "This host is missing a command the unattended upgrade needs, so auto-update stays off. Missing:" ;;
      auto_on)       echo "Auto-update enabled: a new stable release is applied daily, rolled back if the service does not come back" ;;
      auto_off)      echo "Auto-update disabled: upgrades happen only when you run install.sh" ;;
      web_remote)    echo "The static web hosts no accounts; enter your server address on the login page." ;;
      precheck_miss) echo "Missing required command: " ;;
      deps_install)  echo "Missing dependencies detected, installing:" ;;
      pkg_missing)   echo "Could not detect a package manager, install manually:" ;;
      docker_install) echo "Docker not found, install it now (takes a few minutes)? [Y/n]: " ;;
      dir_noaccess)  echo "Cannot create the install directory; run with sudo or pass a writable --dir" ;;
      building)      echo "Downloading and deploying…" ;;
      done_title)    echo "Deployment finished - Successful!" ;;
      done_url)      echo "Open: " ;;
      done_admin)    echo "Admin account (first login): " ;;
      done_data)     echo "Data directory: " ;;
      done_upgrade)  echo "Upgrade: cd %DIR% && %COMPOSE% pull && %COMPOSE% up -d" ;;
      done_logs)     echo "Logs: cd %DIR% && %COMPOSE% logs -f server" ;;
      done_reg)      echo "Note: the admin account was created by this script (new instances only)." ;;
      done_reg_closed) echo "Note: registration is closed by default, only the admin can sign in; open it in Settings -> Server to invite others." ;;
      done_backup)   echo "Backup: cd %DIR% && %COMPOSE% exec -T db pg_dump -U %DBUSER% %DBNAME% | gzip > backup.sql.gz" ;;
      auto_status)   echo "Auto-update: systemctl status barezen-update.timer && journalctl -u barezen-update.service -n 50" ;;
      installing)    echo "Fetching the web bundle and starting…" ;;
      web_tag)       echo "Bundled web version:" ;;
      failed)        echo "the service did not become ready within 120s - see the logs above." ;;
    esac
  fi
}

# ---------------------------------------------------------------------------
# Prerequisites: auto-install missing tools via the system package manager
# (apt/dnf/yum/apk/pacman), then make sure Docker + Compose are available.
# ---------------------------------------------------------------------------
SUDO=""
[ "$(id -u)" != "0" ] && SUDO="sudo"

pkg_install() {
  if command -v apt-get >/dev/null 2>&1; then
    $SUDO apt-get update -y >/dev/null 2>&1 || true
    $SUDO DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
  elif command -v dnf >/dev/null 2>&1; then
    $SUDO dnf install -y "$@"
  elif command -v yum >/dev/null 2>&1; then
    $SUDO yum install -y "$@"
  elif command -v apk >/dev/null 2>&1; then
    $SUDO apk add --no-progress -- "$@"
  elif command -v pacman >/dev/null 2>&1; then
    $SUDO pacman -Sy --noconfirm --needed "$@"
  else
    err "$(t pkg_missing) $*"
  fi
}

MISSING=""
for c in curl sed awk unzip; do
  command -v "$c" >/dev/null 2>&1 || MISSING="$MISSING $c"
done
if [ -n "$MISSING" ]; then
  log "$(t deps_install)${MISSING}"
  pkg_install $MISSING
fi
for c in curl sed awk unzip; do
  command -v "$c" >/dev/null 2>&1 || err "$(t precheck_miss) $c"
done

if ! command -v docker >/dev/null 2>&1; then
  ask "$(t docker_install)" docker_choice || docker_choice="n"
  case "${docker_choice:-Y}" in
    n|N|no|NO) err "Docker is required: https://docs.docker.com/engine/install/" ;;
  esac
  curl -fsSL https://get.docker.com | $SUDO sh || err "Docker install failed: https://docs.docker.com/engine/install/"
  command -v docker >/dev/null 2>&1 || err "Docker still missing after installation - see the output above."
fi
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  err "Docker Compose is required (docker compose plugin or docker-compose)."
fi
# Extra -f files an install has to pass to every compose invocation. Only the
# HTTPS front uses one today, and it is not optional: without the override the
# server publishes ${PORT} on every interface instead of loopback, and the
# unattended updater - which reads the compose command out of
# /etc/barezen-update.env - would recreate the container that way at 04:15.
COMPOSE_FILES=""

echo
echo "=============================================="
echo "  BareZen-Drive  -  self-hosted personal cloud"
echo "=============================================="
echo
echo "$(t lang)"
echo "  1) 中文"
echo "  2) English"
ask "> " lang_choice || lang_choice=""
case "${lang_choice:-1}" in 2) L=en ;; *) L=zh ;; esac
[ "$lang_choice" = "2" ] && t lang_set >/dev/null
echo

# Values already on file are the defaults for this run: re-running the wizard to
# change one answer must not silently reset the others, and a blank line means
# "keep what is there". Read them before the directory is created so a fresh
# install simply gets empty strings.
PRE_ENV="$INSTALL_DIR/.env"
PRE_ADMIN_USER="$(env_get BOOTSTRAP_ADMIN_USER "$PRE_ENV")"
PRE_ADMIN_PASS="$(env_get BOOTSTRAP_ADMIN_PASSWORD "$PRE_ENV")"
PRE_DOMAIN="$(env_get APP_DOMAIN "$PRE_ENV")"
PRE_HTTPS="$(env_get USE_HTTPS "$PRE_ENV")"
if [ -f "$PRE_ENV" ]; then
  echo "$(t rerun_hint)"
  if [ -n "$PRE_DOMAIN" ]; then echo "  $(t domain) [$PRE_DOMAIN]"; fi
fi
echo

# ---- step 2: install type ----
echo "$(t type)"
t type_web; t type_server
ask "> " type_choice || type_choice=""
INSTALL_TYPE="$(install_type_of "${type_choice:-}" "$(env_get INSTALL_TYPE "$PRE_ENV")")"
echo

# ---- step 3: admin account (server / both only) ----
ADMIN_USER=""
ADMIN_PASS=""
if [ "$INSTALL_TYPE" != "web" ]; then
  log "$(t admin)"
  # A blank answer, or input that ends, keeps the account already in .env. The
  # seed only ever applies to a brand-new database, so on a re-run it is
  # cosmetic - and a loop that kept re-prompting on EOF would never finish.
  while true; do
    if ! ask "$(t admin_user)" ADMIN_USER; then ADMIN_USER="$PRE_ADMIN_USER"; break; fi
    if [ -z "$ADMIN_USER" ]; then
      if [ -n "$PRE_ADMIN_USER" ]; then ADMIN_USER="$PRE_ADMIN_USER"; break; fi
    elif [[ "$ADMIN_USER" =~ ^[a-zA-Z0-9_]{3,32}$ ]]; then
      break
    fi
    echo "$(t admin_user_bad)"
  done
  while true; do
    if ! ask_secret "$(t admin_pass)" ADMIN_PASS; then ADMIN_PASS="$PRE_ADMIN_PASS"; echo; break; fi
    echo
    if [ -z "$ADMIN_PASS" ] && [ -n "$PRE_ADMIN_PASS" ]; then ADMIN_PASS="$PRE_ADMIN_PASS"; break; fi
    [ ${#ADMIN_PASS} -ge 8 ] && break
    echo "$(t admin_pass_bad)"
  done
  if [ -z "$ADMIN_USER" ] || [ -z "$ADMIN_PASS" ]; then
    err "no terminal and no existing $PRE_ENV: seed BOOTSTRAP_ADMIN_USER and BOOTSTRAP_ADMIN_PASSWORD there first."
  fi
  echo
else
  # The static bundle authenticates against a remote server, so there is no
  # local account to seed - say so instead of leaving the user wondering.
  echo "$(t web_remote)"
  echo
fi

# ---- step 4: port ----
# The port already published by this instance is the default, so re-running to
# change something else does not silently move the service back to 8080.
if [ "$INSTALL_TYPE" = "web" ]; then
  DEF_PORT="$(env_get WEB_PORT "$PRE_ENV")"
else
  DEF_PORT="$(env_get SERVER_PORT "$PRE_ENV")"
fi
DEF_PORT="${DEF_PORT:-8080}"
while true; do
  ask "$(t port) [$DEF_PORT]: " input_port || input_port=""
  PORT="${input_port:-$DEF_PORT}"
  # Trim surrounding blanks so " 8080 " is accepted rather than silently reset.
  PORT="$(printf '%s' "$PORT" | tr -d '[:space:]')"
  case "$PORT" in
    ''|*[!0-9]*) echo "$(t port_bad)"; continue ;;
  esac
  # Leading zeros would be read as octal by arithmetic; normalise them away.
  PORT="$((10#$PORT))"
  if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    echo "$(t port_range)"; continue
  fi
  break
done
echo

# ---- step 5: domain ----
# This value is written unquoted into a Caddyfile site address and into .env,
# so normalise it (scheme and path off, lowercase) and validate what is left.
# Operators paste "https://drive.example.com/" as often as the bare host, and a
# value with a space or a brace in it would deploy a Caddyfile Caddy refuses to
# read - after the containers are already up.
DOMAIN=""
while true; do
  ask "$(t domain)" input_domain || input_domain=""
  DOMAIN="$(printf '%s' "$input_domain" | tr -d '[:space:]' | tr 'A-Z' 'a-z')"
  DOMAIN="${DOMAIN#http://}"; DOMAIN="${DOMAIN#https://}"
  DOMAIN="${DOMAIN%%/*}"
  # Blank keeps the domain already configured; removing one is an edit to .env,
  # not something to risk with a stray Enter on a re-run.
  if [ -z "$DOMAIN" ]; then DOMAIN="$PRE_DOMAIN"; break; fi
  [[ "$DOMAIN" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] && break
  echo "$(t domain_bad)"
done
echo

# ---- step 6: HTTPS (needs a domain; Caddy issues + renews automatically) ----
# Certificate issuance requires a real domain: Caddy cannot get a Let's Encrypt
# certificate for a bare IP, so stay on plain HTTP when no domain was given.
USE_HTTPS="no"
if [ -n "$DOMAIN" ]; then
  ask "$(t cert)" cert_choice || cert_choice=""
  case "${cert_choice:-${PRE_HTTPS:-Y}}" in n|N|no|NO|false) USE_HTTPS="no" ;; *) USE_HTTPS="yes" ;; esac
  # Caddy needs 80 and 443 for the ACME HTTP-01 challenge and for TLS itself.
  # Failing here costs nothing; failing after `up -d` costs a half-dead stack
  # that never gets a certificate and no explanation in the app's own logs.
  if [ "$USE_HTTPS" = "yes" ]; then
    for p in 80 443; do
      if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then err "$(t port_busy)"; fi
    done
  fi
else
  echo "$(t cert_skip)"
fi

# ---- step 6b: unattended upgrades ----
# Default to the setting already on file, so a non-interactive re-run does not
# quietly turn a hand-driven install into an auto-updating one (or the reverse).
PRE_AUTO="$(env_get AUTO_UPDATE "$PRE_ENV")"
if [ "$INSTALL_TYPE" = "web" ]; then
  # A static bundle pins no image and the database it talks to is not here, so
  # there is nothing for the updater to dump, pull or roll back. Recording yes
  # would promise a timer the deploy section below refuses to install.
  AUTO_UPDATE="no"
  echo "$(t auto_web_skip)"
elif ! unattended_update_supported; then
  # scripts/barezen-update.sh is built on flock, a GNU-style `sed -i` and
  # `sort -V`, and something has to run the timer. A host missing one of those
  # would schedule a failure at 04:15 every day, so this answer is not the
  # operator's to make: BSD userlands (a macOS dev box) land here and are not
  # supported, and no attempt is made to work around them.
  AUTO_UPDATE="no"
  warn "$(t auto_deps) ${UPDATE_UNSUPPORTED# }"
else
  echo "$(t auto_update)"
  ask "$(t auto_update_q) [$([ "${PRE_AUTO:-yes}" = no ] && echo n || echo Y)]: " auto_choice || auto_choice=""
  case "${auto_choice:-${PRE_AUTO:-yes}}" in
    n|N|no|NO|false) AUTO_UPDATE="no" ;;
    *) AUTO_UPDATE="yes" ;;
  esac
fi
echo

# Reachable-from-the-outside address, and the loopback URL used for the health
# probe. With HTTPS the front door is Caddy on port 80/443, not ${PORT}.
HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$HOST_IP" ] || HOST_IP="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1); exit}')"
[ -n "$HOST_IP" ] || HOST_IP="localhost"

# Extra curl arguments for the readiness probe: -k because the certificate is
# for ${DOMAIN} while the probe connects to the loopback address, and a Host
# header because that same certificate is what Caddy matches the site on -
# without it the probe reaches a server that has no site to answer it and a
# healthy deployment gets reported as failed.
PROBE_ARGS=()
if [ -n "$DOMAIN" ] && [ "$USE_HTTPS" = "yes" ]; then
  PUBLIC_URL="https://${DOMAIN}"
  PROBE_URL="https://127.0.0.1"
  PROBE_ARGS=(-k -H "Host: ${DOMAIN}")
elif [ -n "$DOMAIN" ]; then
  # Plain HTTP on a domain: server mode still listens on ${PORT}.
  PUBLIC_URL="http://${DOMAIN}:${PORT}"
  PROBE_URL="http://127.0.0.1:${PORT}"
else
  PUBLIC_URL="http://${HOST_IP}:${PORT}"
  PROBE_URL="http://127.0.0.1:${PORT}"
fi
echo

# Create the install directory; fall back to sudo + chown when the default
# location (/opt/barezen) is not writable by the current user.
if ! mkdir -p "$INSTALL_DIR" 2>/dev/null; then
  $SUDO mkdir -p "$INSTALL_DIR" \
    && $SUDO chown "$(id -u):$(id -g)" "$INSTALL_DIR" \
    || err "$(t dir_noaccess)"
fi
cd "$INSTALL_DIR"

# The secrets and the database name belong to the instance, so an existing
# .env keeps them; what the wizard just asked for is written back afterwards.
if [ -f .env ]; then
  log "$INSTALL_DIR/.env exists - keeping its secrets, updating the wizard answers"
else
  log "Writing configuration to $INSTALL_DIR"
  if [ "$INSTALL_TYPE" = "web" ]; then
    # The static bundle is served by Caddy; INSTALL_TYPE/WEB_PORT are recorded
    # for the operator's reference only, nothing in the compose file reads them.
    cat > .env <<EOF
# Generated by install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
INSTALL_TYPE=web
WEB_PORT=${PORT}
APP_DOMAIN=${DOMAIN}
USE_HTTPS=${USE_HTTPS}
# The app is reached directly here (no reverse proxy in front of it), so there
# is nothing to trust and nothing to forward. Left empty on purpose: the
# server only reads X-Forwarded-For from a peer inside TRUST_PROXY_CIDRS.
TRUST_PROXY_CIDRS=
EOF
  else
    DB_PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)"
    JWT="$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9')"
    cat > .env <<EOF
# Generated by install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
INSTALL_TYPE=${INSTALL_TYPE}
AUTO_UPDATE=${AUTO_UPDATE}
JWT_SECRET=${JWT}
POSTGRES_DB=barezen
POSTGRES_USER=barezen
POSTGRES_PASSWORD=${DB_PASS}
SERVER_PORT=${PORT}
APP_DOMAIN=${DOMAIN}
USE_HTTPS=${USE_HTTPS}
# Closed by default: the first account to exist owns the instance, so an open
# default hands it to the first stranger who presses register. The owner
# reopens registration from Settings -> Server, which rewrites this key.
REGISTRATION_OPEN=${REGISTRATION_OPEN:-false}
# Caddy terminates TLS in its own container on this compose network, so the
# application sees the proxy as a 172.16/12 peer and X-Forwarded-For as
# Caddy's doing. Without this the rate limiter buckets by Caddy's single
# address: one visitor spending the login budget locks out everyone else.
# Narrow it to this network's bridge range if you know it; 172.16.0.0/12 is
# the safe upper bound for a stock Docker bridge.
TRUST_PROXY_CIDRS=${TRUST_PROXY_CIDRS:-172.16.0.0/12}
BOOTSTRAP_ADMIN_USER=${ADMIN_USER}
BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PASS}
EOF
  fi
fi

# An instance switched from the web install type arrives here with a .env that
# never got a secret: compose then fails on ${POSTGRES_PASSWORD:?} after the
# directories were made, leaving a half-installed deployment. Every key this
# install type requires is seeded if the file does not already hold it.
if [ "$INSTALL_TYPE" != "web" ]; then
  for k in POSTGRES_DB POSTGRES_USER; do
    [ -n "$(env_get "$k" .env)" ] || env_set "$k" barezen .env
  done
  if [ -z "$(env_get POSTGRES_PASSWORD .env)" ]; then
    env_set POSTGRES_PASSWORD "$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)" .env
    warn "$(t env_seed) POSTGRES_PASSWORD"
  fi
  if [ -z "$(env_get JWT_SECRET .env)" ]; then
    env_set JWT_SECRET "$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9')" .env
    warn "$(t env_seed) JWT_SECRET"
  fi
fi

# Wizard answers are the current truth about where the service is reachable.
# Skipping them left the old port in .env while the summary printed the new one,
# so the readiness probe checked an address nothing was listening on and a
# healthy deployment was reported as a failure. Registration is deliberately not
# rewritten: an owner who opened it stays open after a re-run.
if [ "$INSTALL_TYPE" = "web" ]; then
  env_set INSTALL_TYPE web .env
  env_set WEB_PORT "$PORT" .env
else
  env_set INSTALL_TYPE "$INSTALL_TYPE" .env
  env_set SERVER_PORT "$PORT" .env
  env_set BOOTSTRAP_ADMIN_USER "$ADMIN_USER" .env
  env_set BOOTSTRAP_ADMIN_PASSWORD "$ADMIN_PASS" .env
fi
env_set APP_DOMAIN "$DOMAIN" .env
env_set USE_HTTPS "$USE_HTTPS" .env
# Recorded for both install types: on a web-only install it is the honest "no",
# and the deploy section below only ever wires the timer for a server instance.
env_set AUTO_UPDATE "$AUTO_UPDATE" .env
chmod 600 .env

# The database role and name the dumps and the summary line both need.
DB_USER="$(env_get POSTGRES_USER .env)"; DB_USER="${DB_USER:-barezen}"
DB_NAME="$(env_get POSTGRES_DB .env)"; DB_NAME="${DB_NAME:-barezen}"

# ---------------------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------------------
# Compose names its volumes after the project, and the project defaults to this
# directory's name, so a fresh install under a new --dir starts with an empty
# database while the previous one stays untouched in the other volume. That is
# a second instance, not an upgrade, and step 3's admin seed would land in the
# empty one - say it out loud rather than letting the operator notice later.
OUR_PROJECT="$(env_get COMPOSE_PROJECT_NAME .env)"
[ -n "$OUR_PROJECT" ] || OUR_PROJECT="$(basename "$INSTALL_DIR" | tr 'A-Z' 'a-z' | tr -cd 'a-z0-9_.-')"
[ -n "$OUR_PROJECT" ] || OUR_PROJECT="barezen"
OUR_PGDATA_VOL="${OUR_PROJECT}_pgdata"
HAS_OWN_DB=""
if docker volume inspect "$OUR_PGDATA_VOL" >/dev/null 2>&1; then HAS_OWN_DB="yes"; fi
if [ "$INSTALL_TYPE" != "web" ] && [ -z "$HAS_OWN_DB" ]; then
  OTHER_VOLS="$(docker volume ls --format '{{.Name}}' 2>/dev/null | grep 'pgdata$' || true)"
  if [ -n "$OTHER_VOLS" ]; then
    warn "$(t other_data) $(printf '%s' "$OTHER_VOLS" | tr '\n' ' ')"
    echo "$(t other_data_2)"
    ask "$(t other_data_3)" second_choice || second_choice=""
    case "${second_choice:-n}" in y|Y|yes|YES) ;; *) err "$(t cancelled)" ;; esac
  fi
fi

if [ "$INSTALL_TYPE" = "web" ]; then
  log "$(t installing)"
  # Resolve the tag to deploy: an explicit --version wins, otherwise the newest
  # release is looked up so the bundle always matches the running server.
  if [ "$IMAGE_TAG" != "latest" ]; then
    TAG="$IMAGE_TAG"
  else
    TAG="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/${GITHUB_REPO}/releases/latest" | sed 's#.*/tag/##')"
  fi
  case "$TAG" in v*) ;; *) TAG="v${TAG}" ;; esac
  V="${TAG#v}"
  echo "  $(t web_tag) ${TAG}"
  curl -fsSL -o web.zip "https://github.com/${GITHUB_REPO}/releases/download/${TAG}/BareZen-Drive-${V}-web.zip" \
    || err "could not download BareZen-Drive-${V}-web.zip for ${TAG} - check the tag exists and is a release."
  rm -rf web && mkdir -p web && unzip -oq web.zip -d web && rm -f web.zip

  # Caddy serves the static bundle; with a domain it terminates HTTPS and
  # renews certificates automatically.
  if [ -n "$DOMAIN" ] && [ "$USE_HTTPS" = "yes" ]; then
    cat > Caddyfile <<EOF
${DOMAIN} {
    root * /srv/web
    file_server
}
EOF
    cat > docker-compose.web.yml <<EOF
services:
  web:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./web:/srv/web:ro
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
volumes:
  caddy_data:
  caddy_config:
EOF
  else
    cat > Caddyfile <<EOF
:${PORT} {
    root * /srv/web
    file_server
}
EOF
    cat > docker-compose.web.yml <<EOF
services:
  web:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "${PORT}:${PORT}"
    volumes:
      - ./web:/srv/web:ro
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
EOF
  fi
  $COMPOSE -f docker-compose.web.yml up -d
  READY_URL="$PROBE_URL"
else
  log "$(t building)"

  # The image pull and the container recreate are the point of no return for an
  # upgrade, so take the dump first - while the old version is still running and
  # its schema is still the one the dump describes.
  if [ -n "$HAS_OWN_DB" ]; then
    mkdir -p backups
    DUMP="backups/barezen-$(date -u +%Y%m%d-%H%M%S).sql.gz"
    log "$(t dumping) $INSTALL_DIR/$DUMP"
    # Best effort: a stopped service or a full disk must not brick an upgrade
    # that the operator asked for, but it must not pass silently either.
    if ! $COMPOSE exec -T db pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$DUMP"; then
      rm -f "$DUMP"; warn "$(t dump_failed)"
    fi
  fi

  # The published compose file is what an upgrade needs (new keys, new defaults),
  # while a locally edited one can hold a mount or a limit this instance depends
  # on. Fetch beside it, and keep any differing local version under a name the
  # operator can diff instead of one that is silently overwritten.
  curl -fsSL -o docker-compose.yml.new "${REPO_RAW}/docker-compose.yml" \
    || err "could not download docker-compose.yml from ${REPO_RAW}"
  if [ -f docker-compose.yml ] && ! cmp -s docker-compose.yml docker-compose.yml.new; then
    mv docker-compose.yml docker-compose.yml.local
    warn "$(t compose_bak) docker-compose.yml.local"
  fi
  mv docker-compose.yml.new docker-compose.yml
  sed -i.bak "s#ghcr.io/linanwanttodo/barezen-drive:latest#ghcr.io/linanwanttodo/barezen-drive:${IMAGE_TAG}#" docker-compose.yml && rm -f docker-compose.yml.bak

  # uid 10001 inside the container writes every blob here. 777 would let any
  # other local user (or any other container on the host) read and rewrite the
  # whole drive, so hand the directory to that uid; only when this shell cannot
  # chown does it fall back to the mode that lets the service start at all.
  mkdir -p data/storage
  if $SUDO chown -R 10001:10001 data/storage 2>/dev/null; then
    $SUDO chmod 750 data/storage
  else
    chmod 777 data/storage
  fi

  # HTTPS front (Caddy) only when a domain is bound: it proxies to the server
  # and keeps certificates renewed on its own.
  if [ -n "$DOMAIN" ] && [ "$USE_HTTPS" = "yes" ]; then
    cat > Caddyfile <<EOF
${DOMAIN} {
    reverse_proxy server:8080
}
EOF
    cat > docker-compose.caddy.yml <<EOF
services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - server
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
  # With a certificate in front, the plain mapping would keep the same drive
  # reachable unencrypted on ${PORT} - and WebDAV Basic over plain HTTP is what
  # the README rules out. Publish it on loopback only: the one outside door is
  # Caddy's, and the readiness probe here stays reachable.
  server:
    ports: !override
      - "127.0.0.1:${PORT}:8080"
volumes:
  caddy_data:
  caddy_config:
EOF
    $COMPOSE pull --quiet server || true
    # Recorded as well as used: the unattended updater replays this exact
    # command later, and "docker compose" alone would drop the loopback-only
    # port override the section above exists for.
    COMPOSE_FILES="-f docker-compose.yml -f docker-compose.caddy.yml"
    $COMPOSE $COMPOSE_FILES up -d
    READY_URL="$PROBE_URL"
  else
    $COMPOSE pull --quiet || true
    $COMPOSE up -d
    READY_URL="$PROBE_URL"
  fi
fi

log "Waiting for the service to become ready"
READY=0
# The web-only install is a static bundle behind Caddy, so its page is the whole
# of readiness. Anything running the server has to answer a query first: /readyz
# returns 503 while the database is unreachable, and a page that loads against a
# dead database would otherwise be reported as a working install.
if [ "$INSTALL_TYPE" = "web" ]; then
  READY_PATH="/"
else
  READY_PATH="/readyz"
fi
for i in $(seq 1 60); do
  if curl -fsS ${PROBE_ARGS[@]+"${PROBE_ARGS[@]}"} "${READY_URL}${READY_PATH}" >/dev/null 2>&1; then
    READY=1; break
  fi
  sleep 2
done
[ "$READY" = "1" ] || { $COMPOSE logs --tail 40 2>/dev/null || true; err "$(t failed)"; }

# ---- unattended upgrade wiring ----
# HAS_OWN_DB is decided before the stack is created (there it only warns about
# another deployment's volume), so on a first install it is still empty here even
# though the `up -d` above just made this instance own its pgdata volume. Ask the
# volume again instead of leaving a brand-new server without a timer until the
# wizard happens to be run a second time.
if [ "$INSTALL_TYPE" != "web" ] && [ -z "$HAS_OWN_DB" ] && docker volume inspect "$OUR_PGDATA_VOL" >/dev/null 2>&1; then
  HAS_OWN_DB=yes
fi
# remove_unattended_updater - the whole teardown list: both units, the updater
# script, the per-instance env file and the state directory the updater keeps its
# lock in. Written once and called from both directions that turn the updater off
# (the "no" answer below, and a switch to the web install type), so neither can
# drift away from what the "yes" branch installs. The state directory is safe to
# delete: the updater makes it again with mkdir -p on the first run after it is
# re-enabled, so removing it costs nothing and leaving it behind does not.
remove_unattended_updater() {
  # Ownership guard: everything below except the state directory is a
  # host-global name, and the env file is the only record of which --dir the
  # units were wired for. A second install under a different --dir (a web
  # static end next to a running server is an expected layout) that answers no
  # must not switch the first instance's timer off and delete its units, script
  # and env. The updater's own env is what makes those units point at a
  # directory, so that line is the evidence: without it, remove nothing at all,
  # this directory's state file included.
  grep -qFx "BAREZEN_DIR=$INSTALL_DIR" /etc/barezen-update.env 2>/dev/null || return 0
  # Turning it off has to actually stop it, and the units go with it so a later
  # `systemctl list-timers` does not show a timer that does nothing.
  $SUDO systemctl disable --now barezen-update.timer 2>/dev/null || true
  $SUDO rm -f "$UNIT_DIR/barezen-update.service" "$UNIT_DIR/barezen-update.timer" /usr/local/bin/barezen-update.sh /etc/barezen-update.env
  $SUDO rm -rf "$INSTALL_DIR/.auto-update"
  $SUDO systemctl daemon-reload 2>/dev/null || true
}
# Only an instance with its own database can be upgraded this way: a web-only
# install has nothing to pull, and dumping a database it does not own would fail.
# Uniting that with the host check above is what keeps a doomed timer from ever
# being enabled. TIMER_ON is what the closing summary reads, so the tip cannot
# claim a timer that this run never installed.
UNIT_DIR=/etc/systemd/system
TIMER_ON=""
if [ "$INSTALL_TYPE" != "web" ] && [ -n "$HAS_OWN_DB" ]; then
  if [ "$AUTO_UPDATE" = "yes" ]; then
    log "$(t auto_on)"
    TIMER_ON=yes
    # A private directory rather than fixed /tmp names: /tmp is world-writable,
    # and the files picked up here are about to be installed as root into
    # /usr/local/bin, so a predictable path is a symlink waiting for the next
    # local user. The trap covers every way out of the block, including a failed
    # download and an install that could not write.
    UPDATE_TMP="$(mktemp -d)"
    trap 'rm -rf "$UPDATE_TMP"' EXIT
    curl -fsSL -o "$UPDATE_TMP/barezen-update.sh" "${REPO_RAW}/scripts/barezen-update.sh" \
      || err "could not download scripts/barezen-update.sh"
    curl -fsSL -o "$UPDATE_TMP/barezen-update.service" "${REPO_RAW}/scripts/systemd/barezen-update.service" \
      || err "could not download the systemd service unit"
    curl -fsSL -o "$UPDATE_TMP/barezen-update.timer" "${REPO_RAW}/scripts/systemd/barezen-update.timer" \
      || err "could not download the systemd timer unit"
    $SUDO install -m 0755 "$UPDATE_TMP/barezen-update.sh" /usr/local/bin/barezen-update.sh
    $SUDO install -m 0644 "$UPDATE_TMP/barezen-update.service" "$UNIT_DIR/barezen-update.service"
    $SUDO install -m 0644 "$UPDATE_TMP/barezen-update.timer" "$UNIT_DIR/barezen-update.timer"
    rm -rf "$UPDATE_TMP"
    trap - EXIT
    # The units are location-agnostic; this file is what makes them point here.
    # BAREZEN_PROBE_URL has to be written too: the script's own default is 8080
    # and an instance on another port would read "endpoint unreachable" and skip
    # every single day. Loopback on plain HTTP reaches the container directly,
    # past Caddy, so the answer cannot depend on a certificate.
    printf 'BAREZEN_DIR=%s\nBAREZEN_COMPOSE=%s\nBAREZEN_PROBE_URL=http://127.0.0.1:%s\n' \
      "$INSTALL_DIR" "$COMPOSE${COMPOSE_FILES:+ $COMPOSE_FILES}" "$PORT" | $SUDO tee /etc/barezen-update.env >/dev/null
    # A deployment that is already up and healthy is not worth failing the whole
    # wizard over: the units are on file either way, so what is missing is one
    # command, and it is printed instead of being lost in a stack trace.
    $SUDO systemctl daemon-reload \
      || warn "systemd was not reloaded - run: sudo systemctl daemon-reload"
    $SUDO systemctl enable --now barezen-update.timer \
      || warn "the timer did not start - run: sudo systemctl enable --now barezen-update.timer"
  else
    log "$(t auto_off)"
    remove_unattended_updater
  fi
elif [ "$INSTALL_TYPE" = "web" ] && [ -f "$UNIT_DIR/barezen-update.timer" ] \
  && grep -qFx "BAREZEN_DIR=$INSTALL_DIR" /etc/barezen-update.env 2>/dev/null; then
  # The other direction the gate above cannot express: an instance switched from
  # server to web over the same --dir. The wizard has already recorded no for a
  # static install, and the previous run's timer is still enabled on this host -
  # pointed now at a compose project that no longer exists, so it fires every
  # morning, reads an endpoint nobody serves and exits 0 in silence. Skipping the
  # block left that zombie in place, so tear it down exactly like the "no" answer.
  # The existence check is what keeps a plain web install from seeing an extra
  # "auto-update disabled" line when nothing was ever wired here. The env-file
  # check is what keeps a web install under a DIFFERENT --dir from switching off
  # and deleting the units, the script and the env of the server instance that
  # owns them: those four targets are host-global names, and only the BAREZEN_DIR
  # line says whose they are.
  log "$(t auto_off)"
  remove_unattended_updater
fi

echo
echo "=============================================="
echo "  $(t done_title)"
echo "=============================================="
echo "  $(t done_url) ${PUBLIC_URL}"
if [ "$INSTALL_TYPE" != "web" ]; then
  echo "  $(t done_admin) ${ADMIN_USER}"
  echo "  $(t done_reg)"
  # Registration is closed by default (REGISTRATION_OPEN=false in both .env and
  # the compose file), so the summary must not claim the opposite.
  echo "  $(t done_reg_closed)"
fi
echo "  $(t done_data) $INSTALL_DIR"
echo
# tip <key> - one copy-pasteable command line with every placeholder filled in.
tip() {
  local s; s="$(t "$1")"
  # The compose command printed here is the one this install actually uses,
  # override files included - a copy-pasted `up -d` without them would drop the
  # loopback-only port binding on an HTTPS install.
  s="${s//%DIR%/$INSTALL_DIR}"; s="${s//%COMPOSE%/$COMPOSE${COMPOSE_FILES:+ $COMPOSE_FILES}}"
  s="${s//%DBUSER%/$DB_USER}"; s="${s//%DBNAME%/$DB_NAME}"
  echo "  $s"
}
tip done_upgrade
tip done_logs
if [ "$INSTALL_TYPE" != "web" ]; then tip done_backup; fi
# Only when this run actually wired the timer: the two commands below answer
# nothing on a host where the units were never installed.
if [ "$TIMER_ON" = "yes" ]; then tip auto_status; fi
echo
exit 0
