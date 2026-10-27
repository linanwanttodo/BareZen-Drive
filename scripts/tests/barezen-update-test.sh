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
  # The call log starts empty rather than absent: grep on a missing file exits 2
  # and prints an error, so "no docker call was recorded" would pass on a case
  # where the updater died before it ever reached docker.
  : > "$d/calls.log"
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
  env "$@" bash "$UPDATER" >"$d/out.log" 2>&1
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
