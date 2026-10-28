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
#
# Exit codes, the four states a journal reader has to tell apart:
#   0  nothing to do, or the upgrade landed and /readyz answers
#   1  the upgrade failed and the rollback worked: the previous tag serves
#      traffic again and the new release waits for a human to look at it
#   2  the rollback itself failed: the service answers on neither tag, the last
#      line names the dump to recover from
#   3  stopped before anything was changed (dump or pull failed): the pin, the
#      containers and the database are exactly as this run found them
# The systemd unit carries no SuccessStatus= on purpose: a non-zero exit has to
# leave the unit failed in `systemctl status`, that is the whole signal.
# Every line that ends a run repeats its own code, so the journal can be read
# without this file open next to it.
set -euo pipefail

DIR="${BAREZEN_DIR:-/opt/barezen}"
COMPOSE="${BAREZEN_COMPOSE:-docker compose}"
PROBE="${BAREZEN_PROBE_URL:-http://127.0.0.1:8080}"
STATE="${BAREZEN_STATE_DIR:-$DIR/.auto-update}"
# 90 x 2 s: a cold JVM plus Flyway on a 1C/1G box can take a minute more than
# the 60 s this used to allow, and rolling back a container that is still
# migrating is worse than waiting. A genuinely broken release still gets rolled
# back inside the unit's 30-minute timeout.
HEALTH_TRIES="${BAREZEN_HEALTH_TRIES:-90}"
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
flock -n 9 || { log "skipped (exit 0): another update is already running"; exit 0; }

cd "$DIR"
[ -f docker-compose.yml ] || { log "aborted (exit 3): no docker-compose.yml in $DIR, nothing was changed"; exit 3; }

json="$(curl -fsS "$PROBE/api/version" 2>/dev/null || true)"
if [ -z "$json" ]; then log "skipped (exit 0): $PROBE/api/version is unreachable"; exit 0; fi

running="$(printf '%s' "$json" | sed -n 's/.*"serverVersion":"\([^"]*\)".*/\1/p')"
latest="$(printf '%s' "$json" | sed -n 's/.*"latestVersion":"\([^"]*\)".*/\1/p')"
pinned="$(current_tag docker-compose.yml)"

if [ -z "$running" ]; then log "skipped (exit 0): the response carried no serverVersion"; exit 0; fi
if [ -z "$latest" ]; then log "skipped (exit 0): the server could not resolve any release"; exit 0; fi
if ! stable "$latest"; then log "skipped (exit 0): $latest is not a stable release"; exit 0; fi
if ! newer "$latest" "$running"; then log "up to date (exit 0): running $running, newest stable $latest"; exit 0; fi
if [ -z "$pinned" ]; then log "aborted (exit 3): cannot read the pinned tag out of docker-compose.yml, nothing was changed"; exit 3; fi
# The pin is the rollback target: the failure paths retag it back to whatever was
# on file before this run. A mutable tag like `latest` makes that target the same
# (or a newer) release, so there is nothing to roll back to - refuse to upgrade
# rather than promise a recovery that cannot happen. install.sh pins the actual
# release, so this is only the case for an install made before it did.
if ! stable "$pinned"; then
  log "skipped (exit 0): the compose pin is '$pinned', not a release version, so an upgrade would have no rollback target. Re-run install.sh to pin the current release."
  exit 0
fi

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
  log "aborted (exit 3): the database dump failed, so nothing was changed"
  exit 3
fi
log "database dumped to $dump"
# Keep the last 7 automatic dumps. Each is a full copy of the database and this
# disk usually also holds the drive's data; a release that fails on this host
# would otherwise leave another one every morning until the disk fills.
if ! ls -1t -- "$DIR"/backups/auto-*.sql.gz 2>/dev/null | tail -n +8 \
  | while IFS= read -r old; do rm -f -- "$old"; done; then
  log "warning: could not prune old automatic dumps in $DIR/backups"
fi

retag docker-compose.yml "$pinned" "$latest" || { log "aborted (exit 3): retag failed, the pin still says $pinned"; exit 3; }
if ! $COMPOSE pull server; then
  log "aborted (exit 3): pull failed, restoring the pin to $pinned, no container was recreated"
  # Guarded: unguarded, set -e would end the run with sed's exit code, and a
  # non-zero that is not 3 reads as a different outcome to whoever checks the
  # journal. The restore is best-effort here - the message already says the pin
  # may name $latest, and nothing was recreated.
  retag docker-compose.yml "$latest" "$pinned" || log "warning: could not restore the pin to $pinned"
  exit 3
fi
# A recreate that fails outright is the same incident as one that comes up unhealthy:
# the pin already names $latest and nothing is serving /api/version. Unguarded, docker's
# exit code plus set -e would end the run here with no rollback, no FATAL line and no
# pointer to the dump - and every later timer would then skip quietly, because the
# endpoint it asks is served by the container that failed to start. One missed upgrade
# window would silently turn into a permanent outage.
if ! $COMPOSE up -d server; then
  log "up -d server failed after moving the pin to $latest"
  rollback="recreate failed after upgrading to $latest"
else
  ok=""
  for _ in $(seq 1 "$HEALTH_TRIES"); do
    if healthy; then ok=1; break; fi
    sleep "$HEALTH_DELAY"
  done
  if [ -n "$ok" ]; then
    log "upgraded to $latest and /readyz is answering (exit 0)"
    exit 0
  fi
  rollback="not healthy after upgrading to $latest"
fi

log "$rollback; rolling back to $pinned"
# Guarded for the same reason as the pull-failure restore above, and one more:
# if this retag fails the pin still names $latest, which is NOT "rolled back".
# Left unguarded, set -e would exit 1 and the journal would report the one
# outcome that did not happen.
if ! retag docker-compose.yml "$latest" "$pinned"; then
  log "FATAL (exit 2, manual recovery needed): could not move the pin back to $pinned - docker-compose.yml still names $latest. Manual recovery: set the image tag to $pinned and recreate, or restore from $dump."
  exit 2
fi
$COMPOSE pull server || true
# Guarded like the recreate above: unguarded, docker's exit code plus set -e ends
# the run before the one line an operator needs when the rollback itself is what
# broke - the one naming the dump. Reaching the FATAL below is the point.
if ! $COMPOSE up -d server; then
  log "recreate failed while restoring $pinned"
else
  for _ in $(seq 1 "$HEALTH_TRIES"); do
    if healthy; then log "rolled back to $pinned and healthy (exit 1): the upgrade failed, the old release is serving, the new release needs a look"; exit 1; fi
    sleep "$HEALTH_DELAY"
  done
fi
log "FATAL (exit 2, manual recovery needed): the rollback to $pinned is not healthy either and the service answers on neither tag. Manual recovery: $dump holds the pre-upgrade database."
exit 2
