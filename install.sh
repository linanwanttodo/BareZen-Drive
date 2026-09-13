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
      domain)        echo "绑定域名（留空则使用 http://服务器IP:端口）: " ;;
      cert)          echo "启用 HTTPS（Caddy 自动申请并续签 Let's Encrypt 证书）？[Y/n]: " ;;
      web_remote)    echo "Web 静态端不创建账号；打开页面后在登录框填写你的服务器地址。" ;;
      building)      echo "正在下载并部署…" ;;
      done_title)    echo "部署完成 - Successful!" ;;
      done_url)      echo "访问地址: " ;;
      done_admin)    echo "管理员账号（首次登录使用）: " ;;
      done_data)     echo "数据目录: " ;;
      done_upgrade)  echo "升级: cd %DIR% && %COMPOSE% pull && %COMPOSE% up -d" ;;
      done_logs)     echo "日志: cd %DIR% && %COMPOSE% logs -f server" ;;
      done_reg)      echo "提示: 首个注册的账号即实例所有者；可在 设置→服务器 关闭开放注册。" ;;
      installing)    echo "正在下载 Web 静态包并启动…" ;;
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
      domain)        echo "Domain to bind (blank = http://<host-ip>:<port>): " ;;
      cert)          echo "Enable HTTPS (Caddy issues and renews Let's Encrypt certificates automatically)? [Y/n]: " ;;
      web_remote)    echo "The static web hosts no accounts; enter your server address on the login page." ;;
      building)      echo "Downloading and deploying…" ;;
      done_title)    echo "Deployment finished - Successful!" ;;
      done_url)      echo "Open: " ;;
      done_admin)    echo "Admin account (first login): " ;;
      done_data)     echo "Data directory: " ;;
      done_upgrade)  echo "Upgrade: cd %DIR% && %COMPOSE% pull && %COMPOSE% up -d" ;;
      done_logs)     echo "Logs: cd %DIR% && %COMPOSE% logs -f server" ;;
      done_reg)      echo "Note: the first registered account owns the instance; close open registration in Settings -> Server." ;;
      installing)    echo "Fetching the web bundle and starting…" ;;
      failed)        echo "the service did not become ready within 120s - see the logs above." ;;
    esac
  fi
}

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
fi

# ---- step 4: port ----
read -r -p "$(t port) [8080]: " input_port
PORT="${input_port:-8080}"
case "$PORT" in ''|*[!0-9]*) PORT=8080 ;; esac
echo

# ---- step 5: domain ----
read -r -p "$(t domain) " DOMAIN
DOMAIN="${DOMAIN%%/}"
echo

# ---- step 6: HTTPS (needs a domain; Caddy issues + renews automatically) ----
USE_HTTPS="no"
if [ -n "$DOMAIN" ] && [ "$INSTALL_TYPE" != "web" -o "$INSTALL_TYPE" = "web" ]; then
  read -r -p "$(t cert)" cert_choice
  case "${cert_choice:-Y}" in n|N|no|NO) USE_HTTPS="no" ;; *) USE_HTTPS="yes" ;; esac
fi
if [ -n "$DOMAIN" ] && [ "$USE_HTTPS" = "yes" ]; then
  PUBLIC_URL="https://${DOMAIN}"
else
  PUBLIC_URL="${DOMAIN:+http://${DOMAIN}}";
  [ -z "$DOMAIN" ] && PUBLIC_URL="http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo localhost):${PORT}"
  [ "$INSTALL_TYPE" = "web" ] && [ -n "$DOMAIN" ] && PUBLIC_URL="http://${DOMAIN}:${PORT}"
  [ "$INSTALL_TYPE" = "web" ] && [ -z "$DOMAIN" ] && PUBLIC_URL="http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo localhost):${PORT}"
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
    cat > .env <<EOF
# Generated by install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
INSTALL_TYPE=web
WEB_PORT=${PORT}
APP_DOMAIN=${DOMAIN}
EOF
  else
    DB_PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)"
    JWT="$(head -c 48 /dev/urandom | base64)"
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
  # Resolve the newest release tag, then pull its web bundle.
  TAG="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/${GITHUB_REPO}/releases/latest" | sed 's#.*/tag/##')"
  V="${TAG#v}"
  curl -fsSL -o web.zip "https://github.com/${GITHUB_REPO}/releases/download/${TAG}/BareZen-Drive-${V}-web.zip"
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
  READY_URL="http://127.0.0.1:${PORT}"
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
    READY_URL="http://127.0.0.1:${PORT}"
  else
    $COMPOSE pull --quiet || true
    $COMPOSE up -d
    READY_URL="http://127.0.0.1:${PORT}"
  fi
fi

log "Waiting for the service to become ready"
READY=0
for i in $(seq 1 60); do
  if curl -fsS "${READY_URL}/health" >/dev/null 2>&1 || curl -fsS "${READY_URL}/" >/dev/null 2>&1; then
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
[ "$INSTALL_TYPE" != "web" ] && echo "  $(t done_admin) ${ADMIN_USER}"
echo "  $(t done_data) $INSTALL_DIR"
echo
[ "$INSTALL_TYPE" != "web" ] && echo "  $(t done_reg)"
UPGRADE_TIP="$(t done_upgrade)"; echo "  ${UPGRADE_TIP//%DIR%/$INSTALL_DIR}"
LOGS_TIP="$(t done_logs)";     echo "  ${LOGS_TIP//%DIR%/$INSTALL_DIR}"
echo
exit 0
