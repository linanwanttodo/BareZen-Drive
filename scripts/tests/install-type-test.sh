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
echo "all install-type assertions passed"
