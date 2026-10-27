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
  *"pull server"*)
    # The mirror of FAIL_UP_VER for the pull: only the pin named here fails to
    # download, which is the branch that has to abort with nothing changed.
    if [ -n "$FAIL_PULL_VER" ] && grep -qF "barezen-drive:$FAIL_PULL_VER" "$COMPOSE_FILE"; then
      exit 1
    fi ;;
  *"up -d server"*)
    # Same trick as FAIL_READY_VER: only the pin named in FAIL_UP_VER fails to
    # recreate, so the upgrade breaks and the rollback succeeds inside one run.
    if [ -n "$FAIL_UP_VER" ] && grep -qF "barezen-drive:$FAIL_UP_VER" "$COMPOSE_FILE"; then
      exit 1
    fi ;;
esac
exit 0
EOF
  cat > "$d/bin/curl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"/api/version"*) [ -n "$VERSION_JSON" ] && printf '%s' "$VERSION_JSON"; exit 0 ;;
  *"/readyz"*)
    # FAIL_READY_ALWAYS drives the exit-2 branch: with the endpoint dead no
    # matter which pin is on file, the rollback itself is confirmed unhealthy.
    # The per-version gate below cannot express that, because the run has to be
    # healthy again for exit 1 to be the right answer.
    if [ -n "$FAIL_READY_ALWAYS" ]; then exit 22; fi
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
# Positive guard before the negative ones: every assertion below says "this did not
# happen", and an updater that never started (exit 127 from a wrong path) satisfies
# all of them while `run_case ... && fail` reads that 127 as "non-zero". The dump
# call being in the log is the only proof that the abort was the updater's.
has "$d" "exec -T db pg_dump" || fail "the dump was never attempted, so the updater did not run"
hasnt "$d" "up -d" || fail "it upgraded without a backup"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "a failed dump still moved the pin"
echo "ok - failed dump aborts before touching anything"

# 6. An unreachable version endpoint is a quiet no-op, not a timer failure.
d="$(new_env 0.2.1)"
run_case "$d" ""
hasnt "$d" "up -d" || fail "it upgraded with nothing to read"
echo "ok - unreachable endpoint is skipped"

# 7. A recreate that fails is a rollback, not a silent stop: the pin already names
#    the new image and nothing is serving /api/version, so the next timer would read
#    "unreachable" and skip for ever.
d="$(new_env 0.2.1)"
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_UP_VER=0.3.0 && fail "a failed recreate must exit non-zero"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "no rollback after a failed recreate: pin is $(pin_of "$d")"
[ "$(grep -c 'up -d server' "$d/calls.log")" = "2" ] || fail "the rollback never recreated the old image"
grep -q "recreate failed after upgrading to 0.3.0; rolling back to 0.2.1" "$d/out.log" || fail "the log does not blame the recreate"
grep -q "rolled back to 0.2.1 and healthy" "$d/out.log" || fail "the rollback was never confirmed healthy"
echo "ok - failed recreate rolls back"

# 8. The pull is the other "nothing was changed" abort: the image never arrives,
#    the pin goes straight back, and no container is recreated. The code is 3 and
#    not 1 because the old release was never taken out of service at all.
d="$(new_env 0.2.1)"
rc=0
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_PULL_VER=0.3.0 || rc=$?
[ "$rc" = 3 ] || fail "a failed pull must exit 3, got $rc"
has "$d" "pull server" || fail "the pull was never attempted, so the updater did not run"
hasnt "$d" "up -d" || fail "a failed pull still recreated the container"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "a failed pull left the pin at $(pin_of "$d")"
grep -q "aborted (exit 3): pull failed" "$d/out.log" || fail "the pull abort does not name its exit code"
echo "ok - failed pull exits 3 with the pin restored"

# 9. Same code from the other abort: a missing dump. Case 5 only proves this one
#    exits non-zero, and "non-zero" is also what a rollback success looks like.
d="$(new_env 0.2.1)"
rc=0
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_DUMP=1 || rc=$?
[ "$rc" = 3 ] || fail "a failed dump must exit 3, got $rc"
grep -q "aborted (exit 3): the database dump failed" "$d/out.log" || fail "the dump abort does not name its exit code"
echo "ok - failed dump exits 3"

# 10. A rollback that comes back healthy is 1: the service is up on the old tag
#    and the new release waits for a look. Nobody has to be paged at 04:15, which
#    is exactly what makes it a different number from 2.
d="$(new_env 0.2.1)"
rc=0
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_READY_VER=0.3.0 || rc=$?
[ "$rc" = 1 ] || fail "a rollback that came back healthy must exit 1, got $rc"
grep -q "(exit 1)" "$d/out.log" || fail "the healthy rollback does not name its exit code"
echo "ok - rolled back and healthy exits 1"

# 11. The rollback itself is unhealthy: neither tag answers, so this is the one
#    state a human has to act on, and the log line has to carry the dump path.
d="$(new_env 0.2.1)"
rc=0
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_READY_ALWAYS=1 || rc=$?
[ "$rc" = 2 ] || fail "a rollback that is unhealthy too must exit 2, got $rc"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "the failed rollback left the pin at $(pin_of "$d")"
grep -q "FATAL (exit 2, manual recovery needed)" "$d/out.log" || fail "no FATAL line naming exit 2"
grep -q "Manual recovery: $d/backups/auto-0.3.0-" "$d/out.log" || fail "the FATAL line does not name the dump"
echo "ok - failed rollback exits 2 with the dump path"

# 12. The recreate on the rollback path is guarded like the one on the upgrade
#    path. Unguarded, docker's exit code plus set -e ends the run here, which is
#    the one moment the FATAL line above is actually needed: the operator gets
#    exit 1 (looks like a clean rollback) and never learns where the dump is.
d="$(new_env 0.2.1)"
rc=0
run_case "$d" '{"serverVersion":"0.2.1","latestVersion":"0.3.0"}' FAIL_UP_VER=0.2.1 FAIL_READY_VER=0.3.0 || rc=$?
[ "$rc" = 2 ] || fail "a rollback whose recreate fails must exit 2, got $rc"
[ "$(pin_of "$d")" = "0.2.1" ] || fail "the pin never went back to the old tag: $(pin_of "$d")"
grep -q "recreate failed while restoring 0.2.1" "$d/out.log" || fail "the rollback recreate failure is not in the log"
grep -q "FATAL (exit 2, manual recovery needed)" "$d/out.log" || fail "the guarded recreate still swallowed the FATAL line"
echo "ok - a failing rollback recreate still reports exit 2"

echo "all barezen-update assertions passed"
