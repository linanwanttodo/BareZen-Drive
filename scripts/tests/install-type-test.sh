#!/usr/bin/env bash
# install.sh has no test harness and cannot be sourced (sourcing runs it), so
# the one decision worth pinning down - answer -> INSTALL_TYPE - is extracted as
# a self-contained function and evaluated here. The function alone is not enough:
# the call site that feeds it the previous value is checked the same way, by
# running the line taken straight out of install.sh (see check_call_site).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INSTALL_SH="$HERE/../../install.sh"

eval "$(sed -n '/^install_type_of()/,/^}/p' "$INSTALL_SH")"
# The extraction must actually have found the function, or every assertion below
# would pass by calling nothing.
type install_type_of >/dev/null 2>&1 || { echo "FAIL: install_type_of not found in install.sh" >&2; exit 1; }
# env_get is extracted too: the call-site check below feeds the real wizard line
# a real .env, and reading the previous value out of it is what that line does.
eval "$(sed -n '/^env_get()/,/^}/p' "$INSTALL_SH")"
type env_get >/dev/null 2>&1 || { echo "FAIL: env_get not found in install.sh" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $1" >&2; exit 1; }
check() { # check <answer> <existing> <expected> <label>
  local label="$4"
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

# Everything above is a unit test of a pure function, but the bug this task fixed
# lived one line further out, in the wizard's call. Replacing the second argument
# with `""` (or leaving the old `:-3` default in place) keeps all six assertions
# and both greps green while a non-interactive re-run still moves a web-only
# install onto a server. So the call site itself is located in install.sh and
# *executed* against a real .env - the line as written, not a paraphrase of it,
# which is the only way this check can fail for the reason it exists.
#
# Found by a fixed string rather than a line number, so edits above it cannot
# silently move the target. Exactly one match is required, and the count comes
# from grep itself: zero (the call was renamed or split over lines) and several
# both fail with this message rather than eval-ing an empty or ambiguous string.
call_site_re='INSTALL_TYPE="$(install_type_of '
n_call_sites="$(grep -cF "$call_site_re" "$INSTALL_SH" || true)"
if [ "$n_call_sites" != 1 ]; then
  fail "expected exactly one INSTALL_TYPE call site in install.sh, found $n_call_sites - the wizard no longer feeds install_type_of from a single line, so update this pattern together with install.sh"
fi
call_site="$(grep -F "$call_site_re" "$INSTALL_SH")"

# check_call_site <answer> <.env value or "absent"> <expected> <label>
# "absent" models a fresh install: the wizard reads $INSTALL_DIR/.env before that
# directory exists, so env_get is handed no file at all.
check_call_site() {
  local answer="$1" existing="$2" expected="$3" label="$4"
  local type_choice INSTALL_TYPE
  rm -f "$WORK/.env"
  if [ "$existing" != "absent" ]; then printf 'INSTALL_TYPE=%s\n' "$existing" > "$WORK/.env"; fi
  # `ask "> " type_choice || type_choice=""` leaves an empty variable with no
  # terminal, which is exactly the state `${type_choice:-}` is written for.
  type_choice="$answer"
  PRE_ENV="$WORK/.env"
  eval "$call_site"
  [ "$INSTALL_TYPE" = "$expected" ] || fail "$label: the call site produced '$INSTALL_TYPE', want '$expected' (answer='$answer', .env='$existing')"
  echo "ok - $label"
}

check_call_site "" web    web    "re-run over a web .env with no answer stays web"
check_call_site "" both   server "re-run over a legacy both .env reads as server"
check_call_site "" absent server "blank answer on a fresh install takes the recommended server"
check_call_site 1  web    web    "a typed 1 beats the existing value"
check_call_site 2  web    server "a typed 2 beats the existing value"

echo "all install-type assertions passed"
