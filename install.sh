#!/usr/bin/env bash
# BareZen-Drive interactive installer.
#
# One wizard, seven steps:
#   1. language (script i18n)
#   2. install type (web static / server / both)
#   3. admin account + password (seeded on the server's first boot)
#   4. service port
#   5. domain (blank = http://<host-ip>:<port>)
#   6. HTTPS with automatic certificate issuance and renewal (Caddy)
#   7. prints the deployed address and Successful!
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/install.sh | bash
#   bash install.sh --dir /opt/barezen --version latest
set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master"
GITHUB_REPO="linanwanttodo/BareZen-Drive"
INSTALL_DIR="/opt/barezen"
IMAGE_TAG="latest"

log() { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }
err() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) INSTALL_DIR="$2"; shift 2 ;;
    --version) IMAGE_TAG="$2"; shift 2 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) err "unknown option: $1 (see --help)" ;;
  esac
done

command -v docker >/dev/null 2>&1 || err "Docker is required: https://docs.docker.com/engine/install/"
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  err "Docker Compose is required (docker compose plugin or docker-compose)."
fi

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
      type_web)      echo "  1) 仅 Web 静态端（连接远程服务器）" ;;
      type_server)   echo "  2) 仅服务器（含内置 Web，本机数据库）" ;;
      type_both)     echo "  3) 服务器 + Web（推荐，等同 2）" ;;
      admin)         echo "设置管理员账号（首次启动自动创建，即实例所有者）" ;;
      admin_user)    echo "管理员用户名 (3-32 位字母/数字/下划线): " ;;
      admin_pass)    echo "管理员密码 (至少 8 位): " ;;
      port)          echo "服务端口:" ;;
      port_bad)      echo "  端口必须是数字，请重试。" ;;
      port_range)    echo "  端口需在 1-65535 之间，请重试。" ;;
      domain)        echo "绑定域名（留空则使用 http://服务器IP:端口）: " ;;
      cert)          echo "启用 HTTPS（Caddy 自动申请并续签 Let's Encrypt 证书）？[Y/n]: " ;;
      cert_skip)     echo "  未填域名，证书无法签发给纯 IP，将使用 HTTP。" ;;
      web_remote)    echo "Web 静态端不创建账号；打开页面后在登录框填写你的服务器地址。" ;;
      precheck_miss) echo "缺少必需命令: " ;;
      building)      echo "正在下载并部署…" ;;
      done_title)    echo "部署完成 - Successful!" ;;
      done_url)      echo "访问地址: " ;;
      done_admin)    echo "管理员账号（首次登录使用）: " ;;
      done_data)     echo "数据目录: " ;;
      done_upgrade)  echo "升级: cd %DIR% && %COMPOSE% pull && %COMPOSE% up -d" ;;
      done_logs)     echo "日志: cd %DIR% && %COMPOSE% logs -f server" ;;
      done_reg)      echo "提示: 管理员账号已由本脚本创建（仅在新实例上生效）。" ;;
      done_reg_open) echo "提示: 当前开放注册，任何人都能注册；可在 设置→服务器 关闭。" ;;
      installing)    echo "正在下载 Web 静态包并启动…" ;;
      web_tag)       echo "Web 静态包版本:" ;;
      failed)        echo "服务未能在 120 秒内就绪，请查看上方日志。" ;;
    esac
  else
    case "$key" in
      lang)          echo "Select language / 选择脚本语言:" ;;
      lang_set)      echo "Language set to English." ;;
      type)          echo "Choose the install type:" ;;
      type_web)      echo "  1) Web static only (connects to a remote server)" ;;
      type_server)   echo "  2) Server only (embedded web, local database)" ;;
      type_both)     echo "  3) Server + Web (recommended, same as 2)" ;;
      admin)         echo "Admin account (created automatically on first boot; it owns the instance)" ;;
      admin_user)    echo "Admin username (3-32 letters/digits/underscore): " ;;
      admin_pass)    echo "Admin password (at least 8 characters): " ;;
      port)          echo "Service port:" ;;
      port_bad)      echo "  The port must be numeric, try again." ;;
      port_range)    echo "  The port must be between 1 and 65535, try again." ;;
      domain)        echo "Domain to bind (blank = http://<host-ip>:<port>): " ;;
      cert)          echo "Enable HTTPS (Caddy issues and renews Let's Encrypt certificates automatically)? [Y/n]: " ;;
      cert_skip)     echo "  No domain given: a certificate cannot be issued for a bare IP, using HTTP." ;;
      web_remote)    echo "The static web hosts no accounts; enter your server address on the login page." ;;
      precheck_miss) echo "Missing required command: " ;;
      building)      echo "Downloading and deploying…" ;;
      done_title)    echo "Deployment finished - Successful!" ;;
      done_url)      echo "Open: " ;;
      done_admin)    echo "Admin account (first login): " ;;
      done_data)     echo "Data directory: " ;;
      done_upgrade)  echo "Upgrade: cd %DIR% && %COMPOSE% pull && %COMPOSE% up -d" ;;
      done_logs)     echo "Logs: cd %DIR% && %COMPOSE% logs -f server" ;;
      done_reg)      echo "Note: the admin account was created by this script (new instances only)." ;;
      done_reg_open) echo "Note: registration is open, anyone can sign up; disable it in Settings -> Server." ;;
      installing)    echo "Fetching the web bundle and starting…" ;;
      web_tag)       echo "Bundled web version:" ;;
      failed)        echo "the service did not become ready within 120s - see the logs above." ;;
    esac
  fi
}

# Commands used further down. curl, sed, awk and unzip are called
# unconditionally, so a minimalist host (Alpine, slim images) would otherwise
# fail halfway through the deploy.
MISSING=""
for c in curl sed awk unzip; do
  command -v "$c" >/dev/null 2>&1 || MISSING="$MISSING $c"
done
if [ -n "$MISSING" ]; then
  err "$(t precheck_miss)${MISSING}"
fi

echo
echo "=============================================="
echo "  BareZen-Drive  -  self-hosted personal cloud"
echo "=============================================="
echo
echo "$(t lang)"
echo "  1) 中文"
echo "  2) English"
read -r -p "> " lang_choice
case "${lang_choice:-1}" in 2) L=en ;; *) L=zh ;; esac
[ "$lang_choice" = "2" ] && t lang_set >/dev/null
echo

# ---- step 2: install type ----
echo "$(t type)"
t type_web; t type_server; t type_both
read -r -p "> " type_choice
case "${type_choice:-3}" in
  1) INSTALL_TYPE="web" ;;
  2) INSTALL_TYPE="server" ;;
  *) INSTALL_TYPE="both" ;;
esac
echo

# ---- step 3: admin account (server / both only) ----
ADMIN_USER=""
ADMIN_PASS=""
if [ "$INSTALL_TYPE" != "web" ]; then
  log "$(t admin)"
  while true; do
    read -r -p "$(t admin_user)" ADMIN_USER
    [[ "$ADMIN_USER" =~ ^[a-zA-Z0-9_]{3,32}$ ]] && break
    [ "$L" = zh ] && echo "用户名格式不正确，请重试。" || echo "Invalid username, try again."
  done
  while true; do
    read -r -s -p "$(t admin_pass)" ADMIN_PASS; echo
    [ ${#ADMIN_PASS} -ge 8 ] && break
    [ "$L" = zh ] && echo "密码至少 8 位，请重试。" || echo "At least 8 characters, try again."
  done
  echo
else
  # The static bundle authenticates against a remote server, so there is no
  # local account to seed - say so instead of leaving the user wondering.
  echo "$(t web_remote)"
  echo
fi

# ---- step 4: port ----
while true; do
  read -r -p "$(t port) [8080]: " input_port
  PORT="${input_port:-8080}"
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
read -r -p "$(t domain) " DOMAIN
DOMAIN="${DOMAIN%%/}"
echo

# ---- step 6: HTTPS (needs a domain; Caddy issues + renews automatically) ----
# Certificate issuance requires a real domain: Caddy cannot get a Let's Encrypt
# certificate for a bare IP, so stay on plain HTTP when no domain was given.
USE_HTTPS="no"
if [ -n "$DOMAIN" ]; then
  read -r -p "$(t cert)" cert_choice
  case "${cert_choice:-Y}" in n|N|no|NO) USE_HTTPS="no" ;; *) USE_HTTPS="yes" ;; esac
else
  echo "$(t cert_skip)"
fi

# Reachable-from-the-outside address, and the loopback URL used for the health
# probe. With HTTPS the front door is Caddy on port 80/443, not ${PORT}.
HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$HOST_IP" ] || HOST_IP="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1); exit}')"
[ -n "$HOST_IP" ] || HOST_IP="localhost"

if [ -n "$DOMAIN" ] && [ "$USE_HTTPS" = "yes" ]; then
  PUBLIC_URL="https://${DOMAIN}"
  PROBE_URL="https://127.0.0.1"
  PROBE_INSECURE="yes"
elif [ -n "$DOMAIN" ]; then
  # Plain HTTP on a domain: server mode still listens on ${PORT}.
  PUBLIC_URL="http://${DOMAIN}:${PORT}"
  PROBE_URL="http://127.0.0.1:${PORT}"
  PROBE_INSECURE="no"
else
  PUBLIC_URL="http://${HOST_IP}:${PORT}"
  PROBE_URL="http://127.0.0.1:${PORT}"
  PROBE_INSECURE="no"
fi
echo

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

# Keep an existing .env on re-runs; a fresh install gets a full one.
if [ -f .env ] && [ "$INSTALL_TYPE" != "web" ]; then
  log "$INSTALL_DIR/.env exists - keeping it (admin seed from step 3 is appended)"
  grep -q '^BOOTSTRAP_ADMIN_USER=' .env && sed -i.bak '/^BOOTSTRAP_ADMIN_USER=/d;/^BOOTSTRAP_ADMIN_PASSWORD=/d;/^# install wizard admin/d' .env && rm -f .env.bak
  {
    echo "# install wizard admin"
    echo "BOOTSTRAP_ADMIN_USER=${ADMIN_USER}"
    echo "BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PASS}"
  } >> .env
  chmod 600 .env
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
EOF
  else
    DB_PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)"
    JWT="$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9')"
    cat > .env <<EOF
# Generated by install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
INSTALL_TYPE=${INSTALL_TYPE}
JWT_SECRET=${JWT}
POSTGRES_DB=barezen
POSTGRES_USER=barezen
POSTGRES_PASSWORD=${DB_PASS}
SERVER_PORT=${PORT}
APP_DOMAIN=${DOMAIN}
USE_HTTPS=${USE_HTTPS}
BOOTSTRAP_ADMIN_USER=${ADMIN_USER}
BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PASS}
EOF
  fi
  chmod 600 .env
fi

# ---------------------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------------------
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
EOF
  fi
  $COMPOSE -f docker-compose.web.yml up -d
  READY_URL="$PROBE_URL"
else
  log "$(t building)"
  curl -fsSL -o docker-compose.yml "${REPO_RAW}/docker-compose.yml"
  sed -i.bak "s#ghcr.io/linanwanttodo/barezen-drive:latest#ghcr.io/linanwanttodo/barezen-drive:${IMAGE_TAG}#" docker-compose.yml && rm -f docker-compose.yml.bak

  # uid 10001 inside the container needs a writable storage directory.
  mkdir -p data/storage
  chmod 777 data/storage

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
volumes:
  caddy_data:
  caddy_config:
EOF
    $COMPOSE pull --quiet server || true
    $COMPOSE -f docker-compose.yml -f docker-compose.caddy.yml up -d
    READY_URL="$PROBE_URL"
  else
    $COMPOSE pull --quiet || true
    $COMPOSE up -d
    READY_URL="$PROBE_URL"
  fi
fi

log "Waiting for the service to become ready"
READY=0
for i in $(seq 1 60); do
  CURL_TLS=""
  [ "$PROBE_INSECURE" = "yes" ] && CURL_TLS="-k"
  if curl -fsS $CURL_TLS "${READY_URL}/health" >/dev/null 2>&1 || curl -fsS $CURL_TLS "${READY_URL}/" >/dev/null 2>&1; then
    READY=1; break
  fi
  sleep 2
done
[ "$READY" = "1" ] || { $COMPOSE logs --tail 40 2>/dev/null || true; err "$(t failed)"; }

echo
echo "=============================================="
echo "  $(t done_title)"
echo "=============================================="
echo "  $(t done_url) ${PUBLIC_URL}"
if [ "$INSTALL_TYPE" != "web" ]; then
  echo "  $(t done_admin) ${ADMIN_USER}"
  echo "  $(t done_reg)"
  # Registration stays open by default; the seeded owner is already in place, so
  # spell out what open registration now means.
  echo "  $(t done_reg_open)"
fi
echo "  $(t done_data) $INSTALL_DIR"
echo
UPGRADE_TIP="$(t done_upgrade)"; echo "  ${UPGRADE_TIP//%DIR%/$INSTALL_DIR}"
LOGS_TIP="$(t done_logs)";     echo "  ${LOGS_TIP//%DIR%/$INSTALL_DIR}"
echo
exit 0
