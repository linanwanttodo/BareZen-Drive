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
    log "upgraded to $latest and /readyz is answering"
    exit 0
  fi
  rollback="not healthy after upgrading to $latest"
fi

log "$rollback; rolling back to $pinned"
retag docker-compose.yml "$latest" "$pinned"
$COMPOSE pull server || true
$COMPOSE up -d server
for _ in $(seq 1 "$HEALTH_TRIES"); do
  if healthy; then log "rolled back to $pinned and healthy; the new release needs a look"; exit 1; fi
  sleep "$HEALTH_DELAY"
done
log "FATAL: the rollback to $pinned is not healthy either. Manual recovery: $dump holds the pre-upgrade database."
exit 1
