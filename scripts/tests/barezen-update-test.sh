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

# --- the host precheck in install.sh, extracted and run the way
# install-type-test.sh runs the wizard's decision line. flock, a GNU sed and a
# `sort -V` that actually sorts by version are what the updater above is built
# on: a timer installed on a host without them is guaranteed to fail every
# morning, so the check has to be executed with the tools absent and with them
# present, not grepped for.
INSTALL_SH="$HERE/../../install.sh"
eval "$(sed -n '/^unattended_update_supported()/,/^}/p' "$INSTALL_SH")"
type unattended_update_supported >/dev/null 2>&1 || fail "the host precheck is not a function in install.sh"
bz_check() { # bz_check <path> - the verdict and the list it built, on one line
  ( PATH="$1"
    if unattended_update_supported; then printf 'ok %s\n' "$UPDATE_UNSUPPORTED"
    else printf 'off %s\n' "$UPDATE_UNSUPPORTED"; fi )
}
BZ_HOST="$(mktemp -d)"
BZ_WIZ="$(mktemp -d)"
trap 'rm -rf "$BZ_HOST" "$BZ_WIZ"' EXIT
mkdir -p "$BZ_HOST/absent" "$BZ_HOST/present"
cat > "$BZ_HOST/absent/sed" <<EOF
#!/bin/sh
if [ "\$1" = "--version" ]; then echo "sed: illegal option -- -" >&2; exit 1; fi
exec $(command -v sed) "\$@"
EOF
cat > "$BZ_HOST/absent/sort" <<EOF
#!/bin/sh
# A sort that ignores -V: it accepts the flag and orders lexically anyway, which
# is how 1.10 ends up below 1.9 on a userland without version sort.
keep=""
for a in "\$@"; do [ "\$a" = "-V" ] || keep="\$keep \$a"; done
exec $(command -v sort) \$keep
EOF
chmod +x "$BZ_HOST/absent/sed" "$BZ_HOST/absent/sort"
for tool in sed sort flock; do
  p="$(command -v "$tool" 2>/dev/null || true)"
  # `&& ln` would be a false-valued top-level command on a host without the tool
  # (set -e ends the file there), so the if is load-bearing, not style.
  if [ -n "$p" ]; then ln -s "$p" "$BZ_HOST/present/$tool"; fi
done
res="$(bz_check "$BZ_HOST/absent")"
case "$res" in off*) ;; *) fail "a host with no flock, no GNU sed and no version sort was called supported: $res" ;; esac
for want in flock gnu-sed sort-V; do
  case " $res " in *" $want "*) ;; *) fail "the precheck did not name $want: $res" ;; esac
done
echo "ok - a host missing the updater's tools is refused by name"
# The same check must not cry wolf where the three tools exist, or the answer
# would always be no and the timer would never be installed anywhere. A host
# without all three (a BSD dev box) is the case the check exists for, so it only
# gets the positive run when it actually has them.
if [ -x "$BZ_HOST/present/flock" ] && [ -x "$BZ_HOST/present/sed" ] && [ -x "$BZ_HOST/present/sort" ] \
  && [ "$(printf '1.10\n1.9\n' | sort -V 2>/dev/null || true)" = "$(printf '1.9\n1.10')" ]; then
  res="$(bz_check "$BZ_HOST/present")"
  for banned in flock gnu-sed sort-V; do
    case " $res " in *" $banned "*) fail "a host that has $banned was refused: $res" ;; esac
  done
  echo "ok - a host with the tools is not refused"
fi

# --- the wizard's answer -> AUTO_UPDATE, run as written rather than paraphrased.
# install-type-test.sh learned this the hard way: grepping a decision line proves
# the text exists, not that the branch does what it claims, so the block is
# located by its marker, extracted, and executed against a real .env with `ask`
# stubbed.
n_6b="$(grep -cF '# ---- step 6b: unattended upgrades ----' "$INSTALL_SH" || true)"
if [ "$n_6b" != 1 ]; then
  fail "expected exactly one step 6b block in install.sh, found $n_6b - the wizard no longer asks in one place, so update this pattern together with install.sh"
fi
BLOCK_6B="$(sed -n '/# ---- step 6b: unattended upgrades ----/,/^echo$/p' "$INSTALL_SH")"
case "$BLOCK_6B" in *AUTO_UPDATE=\"no\"*) ;; *) fail "the step 6b extraction found no AUTO_UPDATE assignment - the block no longer ends at a lone echo" ;; esac
eval "$(sed -n '/^t() {/,/^}/p' "$INSTALL_SH")"
eval "$(sed -n '/^env_get()/,/^}/p' "$INSTALL_SH")"
type t env_get >/dev/null 2>&1 || fail "t / env_get not found in install.sh"
bz_wizard() { # bz_wizard <answer> <env-value|absent> <install-type> <path> -> AUTO_UPDATE
  local answer="$1" existing="$2" itype="$3" path="$4"
  local auto_choice AUTO_UPDATE PRE_ENV INSTALL_TYPE L=en
  PRE_ENV="$BZ_WIZ/.env"
  rm -f "$PRE_ENV"
  if [ "$existing" != absent ]; then printf 'AUTO_UPDATE=%s\n' "$existing" > "$PRE_ENV"; fi
  INSTALL_TYPE="$itype"
  warn() { printf 'warning: %s\n' "$*"; }
  # An empty answer is `curl | bash` with no terminal: ask fails, and the wizard
  # line is supposed to fall back to the value already on file.
  ask() { printf -v "$2" '%s' "$answer"; [ -n "$answer" ] || return 1; }
  ( PATH="$path"
    eval "$BLOCK_6B" >/dev/null 2>&1
    printf '%s' "$AUTO_UPDATE" )
}
[ "$(bz_wizard "" absent server "$PATH")" = yes ] || fail "a fresh server install with no terminal did not default to on"
[ "$(bz_wizard "" no server "$PATH")" = no ] || fail "a re-run with no terminal turned an off instance into an auto-updating one"
[ "$(bz_wizard "" yes server "$PATH")" = yes ] || fail "a re-run with no terminal silently disabled an auto-updating instance"
[ "$(bz_wizard n absent server "$PATH")" = no ] || fail "a typed n was not honoured"
[ "$(bz_wizard y no server "$PATH")" = yes ] || fail "a typed y did not override the value on file"
[ "$(bz_wizard "" yes web "$PATH")" = no ] || fail "a static web install recorded on with no image to upgrade"
[ "$(bz_wizard y absent server "$BZ_HOST/absent")" = no ] \
  || fail "a host missing flock, a GNU sed and sort -V was still promised a daily timer"
echo "ok - the wizard's auto-update answer behaves as written"

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

# Both halves of the wiring gate matter: an instance that does not own a database
# must not be handed a updater that dumps somebody else's, and a first install -
# where HAS_OWN_DB was read before the volume existed - must still get its timer.
# The re-probe is matched by the shape of the new line, not by the volume name:
# install.sh already inspects that volume earlier, so the bare command matches
# even when the second look is gone.
grep -qF 'if [ "$INSTALL_TYPE" != "web" ] && [ -n "$HAS_OWN_DB" ]; then' "$INSTALL_SH" \
  || fail "the timer is wired without checking that this instance owns its database"
grep -qF '[ -z "$HAS_OWN_DB" ] && docker volume inspect "$OUR_PGDATA_VOL"' "$INSTALL_SH" \
  || fail "a fresh install is never re-probed for the volume it just created"
# Turning it off has to remove what turning it on installed, and the answer has to
# survive into .env, or the next re-run defaults from nothing.
grep -qF 'rm -f "$UNIT_DIR/barezen-update.service" "$UNIT_DIR/barezen-update.timer"' "$INSTALL_SH" \
  || fail "auto-update off leaves the unit files behind"
grep -qF 'env_set AUTO_UPDATE "$AUTO_UPDATE" .env' "$INSTALL_SH" \
  || fail "the auto-update answer never reaches .env"
echo "ok - the wiring gate still requires a database of its own"

# The unit runs the file install.sh writes, at the path it writes it to, and
# nothing papers over a non-zero exit: SuccessStatus= would let systemd call a
# rolled-back (or unrecoverable) upgrade a clean unit.
grep -q "ExecStart=/usr/local/bin/barezen-update.sh" "$HERE/../../scripts/systemd/barezen-update.service" \
  || fail "the service does not run the path install.sh installs to"
# The pairing is what matters, not just the string: install.sh may mention that
# path in the disable branch too, so the assertion has to name the write.
grep -qE 'install -m 0755 "[^"]*" /usr/local/bin/barezen-update\.sh' "$HERE/../../install.sh" \
  || fail "install.sh does not install the script where the unit expects it"
for u in "$HERE/../../scripts/systemd/barezen-update.service" "$HERE/../../scripts/systemd/barezen-update.timer"; do
  if grep -q "SuccessStatus" "$u"; then fail "$(basename "$u") declares SuccessStatus and hides a failed run"; fi
done
# The instance's own loopback address is the only probe that works on a port
# other than 8080 and behind Caddy; without it every daily run would read
# "unreachable" and skip in silence.
grep -q "BAREZEN_PROBE_URL=http://127.0.0.1:" "$HERE/../../install.sh" \
  || fail "install.sh never tells the updater which port to probe"
# The host dependency check is what keeps a doomed timer from being enabled, so
# the gate has to be the function that ran above and not a stray comment.
grep -q "unattended_update_supported" "$HERE/../../install.sh" \
  || fail "install.sh does not check the host for flock, GNU sed and sort -V"
echo "ok - updater, units and installer describe the same layout"

# --help is the only copy of the step list an operator reads before installing
# (`$0` is `bash` when the script arrives on stdin, so the header comment above it
# is not reachable), and the two have already drifted once: the header gained the
# auto-update step while usage() kept seven steps without it.
if ! awk '/^usage\(\) \{/,/^}/' "$INSTALL_SH" | grep -qF '6b. auto-update'; then
  fail "usage() does not list the auto-update step the wizard actually asks"
fi
echo "ok - the help text lists the step the wizard asks"

# --- the teardown direction is run as a scenario, not grepped. The shape the gate
# above cannot express is the switch from server to web over the same --dir: that
# run used to skip the whole wiring block, so the timer the earlier install enabled
# stayed on file and fired daily at a compose project that no longer exists - it
# reads an endpoint nobody serves and exits 0 in silence. install.sh's block is
# located by fixed anchors, extracted, and executed with systemctl stubbed and the
# three absolute write targets redirected into a sandbox, the way
# install-type-test.sh runs the wizard instead of paraphrasing it.
n_unitdir="$(grep -cxF 'UNIT_DIR=/etc/systemd/system' "$INSTALL_SH" || true)"
if [ "$n_unitdir" != 1 ]; then
  fail "expected exactly one UNIT_DIR assignment in install.sh, found $n_unitdir - the wiring block no longer starts where this pattern expects, so update this test together with install.sh"
fi
n_teardown="$(grep -cxF 'remove_unattended_updater() {' "$INSTALL_SH" || true)"
if [ "$n_teardown" != 1 ]; then
  fail "expected exactly one remove_unattended_updater definition in install.sh, found $n_teardown - the teardown is no longer one named list both off-directions call"
fi
BZ_WIRE="$(mktemp -d)"
# Re-armed rather than replaced: the trap above only knows the two directories it
# made, and a fail() inside the scenario below must not leave a third one behind.
trap 'rm -rf "$BZ_HOST" "$BZ_WIZ" "$BZ_WIRE"' EXIT
bz_sb="$BZ_WIRE/case"
bz_redirect() { # bz_redirect - install.sh text with its write targets sandboxed
  sed "s#/etc/systemd/system#$bz_sb/unit#g; s#/usr/local/bin/barezen-update.sh#$bz_sb/bin/barezen-update.sh#g; s#/etc/barezen-update.env#$bz_sb/env#g"
}
bz_wiring="$(sed -n '\|^UNIT_DIR=/etc/systemd/system$|,/^fi$/p' "$INSTALL_SH" | bz_redirect)"
bz_teardown="$(sed -n '/^remove_unattended_updater()/,/^}$/p' "$INSTALL_SH" | bz_redirect)"
# An extraction that silently comes back short would make every assertion below
# green on a block that never ran, so both pieces have to be complete.
if [ -z "$bz_teardown" ]; then fail "remove_unattended_updater was not extracted from install.sh"; fi
case "$bz_teardown" in *"/.auto-update"*) ;; *) fail "the teardown list leaves the updater's state directory behind" ;; esac
web_gate='[ "$INSTALL_TYPE" = "web" ]'
case "$bz_wiring" in
  *"$web_gate"*) ;;
  *) fail "the wiring extraction stopped before the web branch - the block no longer ends at a lone fi, so update this pattern together with install.sh" ;;
esac
mkdir -p "$BZ_WIRE/stub"
cat > "$BZ_WIRE/stub/systemctl" <<'EOF'
#!/usr/bin/env bash
echo "systemctl $*" >> "$BZ_CALLS"
exit 0
EOF
cat > "$BZ_WIRE/stub/curl" <<'EOF'
#!/usr/bin/env bash
# Stands in for the three downloads: whatever -o names gets a file, so the install
# branch has something real to install and tee.
out=""
prev=""
for a in "$@"; do
  if [ "$prev" = "-o" ]; then out="$a"; fi
  prev="$a"
done
if [ -n "$out" ]; then printf 'downloaded\n' > "$out"; fi
exit 0
EOF
chmod +x "$BZ_WIRE/stub/systemctl" "$BZ_WIRE/stub/curl"
bz_wire() { # bz_wire <install-type> <has-own-db> <answer> [empty] - what the block did
  rm -rf "$bz_sb"
  mkdir -p "$bz_sb/unit" "$bz_sb/bin" "$bz_sb/.auto-update"
  # What an earlier run that answered yes left on this host under the same --dir.
  # Without these the scenario proves nothing: there has to be a zombie to remove
  # before "it was removed" means anything. `empty` is the opposite case - an
  # instance that never had the updater wired - and leaves the sandbox bare.
  if [ "${4:-left}" != "empty" ]; then
    : > "$bz_sb/unit/barezen-update.service"
    : > "$bz_sb/unit/barezen-update.timer"
    : > "$bz_sb/bin/barezen-update.sh"
    printf 'BAREZEN_DIR=/opt/barezen\n' > "$bz_sb/env"
    : > "$bz_sb/.auto-update/lock"
  fi
  : > "$bz_sb/calls.log"
  : > "$bz_sb/out.log"
  # In a separate bash so the extracted block's own `trap ... EXIT` cannot touch
  # this test's cleanup, and with set -u like install.sh runs under.
  PATH="$BZ_WIRE/stub:$PATH" BZ_CALLS="$bz_sb/calls.log" BZ_OUT="$bz_sb/out.log" \
  BZ_SB="$bz_sb" \
  INSTALL_TYPE="$1" HAS_OWN_DB="$2" AUTO_UPDATE="$3" SUDO="" INSTALL_DIR="$bz_sb" \
  COMPOSE="docker compose" PORT=8080 REPO_RAW="bz://stub" \
  bash -c '
    set -euo pipefail
    log() { printf "log: %s\n" "$*" >> "$BZ_OUT"; }
    warn() { printf "warn: %s\n" "$*" >> "$BZ_OUT"; }
    err() { printf "err: %s\n" "$*" >> "$BZ_OUT"; exit 1; }
    t() { printf "%s" "$1"; }
    eval "$1"
    eval "$2"
    printf "TIMER_ON=%s\n" "${TIMER_ON:-}"
    printf "CALLS=%s\n" "$(tr "\n" ";" < "$BZ_CALLS")"
    printf "UNITS=%s\n" "$(ls -A "$UNIT_DIR" 2>/dev/null | tr "\n" ";")"
    printf "SCRIPT=%s\n" "$([ -e "$BZ_SB/bin/barezen-update.sh" ] && echo kept || echo gone)"
    printf "ENVFILE=%s\n" "$([ -e "$BZ_SB/env" ] && echo kept || echo gone)"
    printf "STATE=%s\n" "$([ -e "$BZ_SB/.auto-update" ] && echo kept || echo gone)"
    printf "OUT=%s\n" "$(tr "\n" ";" < "$BZ_OUT")"
  ' _ "$bz_teardown" "$bz_wiring"
}
field() { # field <transcript> <key>
  printf '%s\n' "$1" | sed -n "s/^$2=//p"
}

# A static web install over a directory that was wired as a server yesterday.
res="$(bz_wire web "" no)" || fail "the wiring scenario for [web "" no] exited non-zero"
case "$(field "$res" CALLS)" in
  *"disable --now barezen-update.timer"*) ;;
  *) fail "choosing web left the previous install's timer enabled: $(field "$res" CALLS)" ;;
esac
[ "$(field "$res" UNITS)" = "" ] || fail "choosing web left the unit files behind: $(field "$res" UNITS)"
[ "$(field "$res" SCRIPT)" = "gone" ] || fail "choosing web left /usr/local/bin/barezen-update.sh behind"
[ "$(field "$res" ENVFILE)" = "gone" ] || fail "choosing web left /etc/barezen-update.env behind"
[ "$(field "$res" STATE)" = "gone" ] || fail "choosing web left the updater's state directory behind"
[ "$(field "$res" TIMER_ON)" = "" ] || fail "a web install set TIMER_ON: the summary would offer timer commands for a host with none"
case "$(field "$res" OUT)" in
  *"log: auto_off"*) ;;
  *) fail "the web teardown did not tell the operator what it did: $(field "$res" OUT)" ;;
esac
echo "ok - switching an instance to web tears the updater down instead of skipping"

# The same list has to come off for the answer that was already covered: a server
# instance that says no. Both off-directions call one function, so both are checked.
res="$(bz_wire server yes no)" || fail "the wiring scenario for [server yes no] exited non-zero"
case "$(field "$res" CALLS)" in
  *"disable --now barezen-update.timer"*) ;;
  *) fail "answering no left the timer enabled: $(field "$res" CALLS)" ;;
esac
[ "$(field "$res" UNITS)" = "" ] || fail "answering no left the unit files behind: $(field "$res" UNITS)"
[ "$(field "$res" STATE)" = "gone" ] || fail "answering no left the updater's state directory behind"
echo "ok - the no answer runs the same teardown list"

# Two controls, so the green above cannot come from a block that does nothing at
# all: the yes branch must still install, and a web install that never had a timer
# must stay quiet instead of running the teardown for show.
res="$(bz_wire server yes yes)" || fail "the wiring scenario for [server yes yes] exited non-zero"
[ "$(field "$res" TIMER_ON)" = "yes" ] || fail "a server install answering yes did not wire the timer: $(field "$res" OUT)"
[ "$(field "$res" UNITS)" = "barezen-update.service;barezen-update.timer;" ] \
  || fail "the yes branch did not leave both units on file: $(field "$res" UNITS)"
[ "$(field "$res" ENVFILE)" = "kept" ] || fail "the yes branch did not write the per-instance env file"
[ "$(field "$res" STATE)" = "kept" ] || fail "the yes branch deleted the state directory it did not create"
case "$(field "$res" CALLS)" in
  *"enable --now barezen-update.timer"*) ;;
  *) fail "the yes branch never enabled the timer: $(field "$res" CALLS)" ;;
esac
echo "ok - the yes branch still installs and enables both units"
# Nothing on file for a static install to remove: no systemctl call, no message,
# and no teardown running for show on a host that never had the updater wired.
res="$(bz_wire web "" no empty)" || fail "the wiring scenario for [web "" no empty] exited non-zero"
[ "$(field "$res" CALLS)" = "" ] || fail "a plain web install ran the teardown with nothing on file: $(field "$res" CALLS)"
case "$(field "$res" OUT)" in
  *"log:"*) fail "a plain web install was told its auto-update was turned off: $(field "$res" OUT)" ;;
esac
[ "$(field "$res" TIMER_ON)" = "" ] || fail "a plain web install set TIMER_ON"
echo "ok - the web teardown only runs when the earlier install left something behind"

echo "all barezen-update assertions passed"
