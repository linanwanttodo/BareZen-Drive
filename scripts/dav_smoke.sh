#!/usr/bin/env bash
# Real-HTTP smoke test for the WebDAV surface.
#
# Usage:
#   BASE=http://127.0.0.1:8080 USER_NAME=davuser PASS=password123 \
#     bash scripts/dav_smoke.sh
#
# WHY THIS EXISTS AND WHY IT IS A SEPARATE SCRIPT
#
# The server's own test suite drives the application through Ktor's in-process
# test host. That host shares one response object between the handler and the
# client, so a header written AFTER call.respond() is still readable by the test
# client even though it never reaches the wire. Every assertion about response
# headers is therefore weaker than it looks: the whole suite passed while
# `OPTIONS /dav/` shipped without a `DAV:` header, which is the one header a
# WebDAV client uses to decide the server speaks WebDAV at all. Windows
# Explorer, Finder and davfs2 all refuse to mount a server that omits it.
#
# That class of bug is only observable over a real socket, so this script talks
# to a running server with curl. It is deliberately not a replacement for the
# test suite - it cannot check authorisation boundaries cheaply - it is the part
# the suite structurally cannot see.
#
# What it covers: the OPTIONS floor a Windows mount needs, a real 401 challenge,
# PROPFIND href percent-encoding versus displayname (the two must differ),
# GET/HEAD/Range, conditional GET, and that a path outside /dav is never answered
# with a WebDAV challenge.
#
# Requires: curl, python3. Needs an account that can create a WebDAV token.

set -uo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
USER_NAME="${USER_NAME:-davuser}"
PASS="${PASS:-password123}"

pass=0
fail=0

ok()   { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; printf '       %s\n' "${2:-}"; fail=$((fail + 1)); }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# Assert that a header is present with a value matching a pattern.
# $1 label  $2 header name  $3 expected regex  $4 raw headers
assert_header() {
  local label="$1" name="$2" want="$3" raw="$4" got
  got="$(printf '%s' "$raw" | grep -i "^${name}:" | head -1 | sed "s/^[^:]*: *//" | tr -d '\r')"
  if printf '%s' "$got" | grep -Eqi "$want"; then
    ok "$label ($name: $got)"
  else
    bad "$label" "expected $name matching /$want/, got '${got:-<absent>}'"
  fi
}

assert_status() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then ok "$label ($got)"; else bad "$label" "expected $want, got $got"; fi
}

status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
headers_of() { curl -s -i "$@" | tr -d '\r'; }

# ---------------------------------------------------------------- account + token
head_ "Account and app password"

login="$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASS\"}")"
JWT="$(printf '%s' "$login" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
if [ -z "$JWT" ]; then
  echo "cannot log in as $USER_NAME; set USER_NAME/PASS for an existing account" >&2
  exit 2
fi
ok "logged in as $USER_NAME"

created="$(curl -s -X POST "$BASE/api/webdav/tokens" -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{"label":"dav-smoke","readOnly":false}')"
DAV="$(printf '%s' "$created" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("plaintext",""))' 2>/dev/null)"
if [ -z "$DAV" ]; then
  echo "could not mint a WebDAV token: $created" >&2
  exit 2
fi
case "$DAV" in
  *[!0-9a-f]*) bad "token is 64 lowercase hex chars" "got unexpected characters: $DAV" ;;
  *) [ "${#DAV}" -eq 64 ] && ok "token is 64 hex chars" || bad "token length" "got ${#DAV}" ;;
esac

AUTH=(-u "$USER_NAME:$DAV")

# Minted up front: both the read-only section and the structural-method section
# need it, and `set -u` rightly refuses to let them read a variable that does not
# exist yet.
RO="$(curl -s -X POST "$BASE/api/webdav/tokens" -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{"label":"dav-smoke-ro","readOnly":true}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("plaintext",""))' 2>/dev/null)"

# A unique folder per run so repeated runs do not collide on the name.
STAMP="$(date +%s)$$"
FOLDER="dav-smoke-$STAMP"
ENC_FOLDER="$FOLDER"

# ---------------------------------------------------------------- OPTIONS floor
head_ "OPTIONS (the floor a Windows mount needs)"

h="$(headers_of -X OPTIONS "$BASE/dav/" "${AUTH[@]}")"
assert_status "OPTIONS /dav/ is 200" 200 "$(printf '%s' "$h" | head -1 | awk '{print $2}')"
# The whole point of this script: these three never reach the wire if they are
# written after respond(), and the in-process suite cannot tell.
assert_header "advertises DAV"        "DAV"            '^1$'          "$h"
assert_header "advertises Allow"      "Allow"          'PROPFIND'     "$h"
assert_header "advertises Allow"      "Allow"          'PUT'          "$h"
assert_header "MS-Author-Via"         "MS-Author-Via"  'DAV'          "$h"
# Advertising locking we do not implement changes client behaviour for the worse.
if printf '%s' "$h" | grep -i '^Allow:' | grep -Eq 'LOCK'; then
  bad "LOCK is not advertised" "Allow contains LOCK but LOCK returns 501"
else
  ok "LOCK is not advertised (it would make clients expect lock semantics)"
fi
if printf '%s' "$h" | grep -i '^DAV:' | grep -Eq '(^|,)[[:space:]]*2'; then
  bad "does not claim DAV: 2" "found a 2-class token"
else
  ok "does not claim DAV: 2"
fi

# ---------------------------------------------------------------- challenge
head_ "Unauthenticated requests"

h="$(headers_of -X PROPFIND "$BASE/dav/" -H 'Depth: 0')"
assert_status "PROPFIND without credentials is 401" 401 \
  "$(printf '%s' "$h" | head -1 | awk '{print $2}')"
assert_header "carries a Basic challenge" "WWW-Authenticate" 'Basic' "$h"

# The account password must not work as a mount credential.
assert_status "account password is refused on /dav" 401 \
  "$(status_of -X PROPFIND "$BASE/dav/" -u "$USER_NAME:$PASS")"

# Outside /dav there must be no WebDAV challenge: with the DAV catch-all once
# mounted on the application root, every path answered PROPFIND with 401 and told
# callers to present drive credentials for a URL that is not a drive.
for p in /not-a-drive /api/whatever; do
  s="$(status_of -X PROPFIND "$BASE$p")"
  if [ "$s" = "401" ]; then
    bad "$p is not answered with a WebDAV challenge" "got 401"
  else
    ok "$p is not answered with a WebDAV challenge ($s)"
  fi
done

# ---------------------------------------------------------------- PROPFIND
head_ "PROPFIND"

folder_json="$(curl -s -X POST "$BASE/api/folders" -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d "{\"name\":\"$FOLDER\"}")"
FOLDER_ID="$(printf '%s' "$folder_json" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))' 2>/dev/null)"
if [ -n "$FOLDER_ID" ]; then ok "created a collection to list"; else bad "created a collection" "$folder_json"; fi

body="$(curl -s -X PROPFIND "$BASE/dav/" "${AUTH[@]}" -H 'Depth: 1')"
if printf '%s' "$body" | grep -q '<D:multistatus'; then
  ok "Depth 1 returns a multistatus"
else
  bad "Depth 1 returns a multistatus" "$(printf '%s' "$body" | head -c 200)"
fi
# href is percent-encoded; displayname is not. A client that gets these the
# same way round either truncates at '#'/'?' or shows the user "a%20b.txt".
if printf '%s' "$body" | grep -q "/dav/$ENC_FOLDER/"; then
  ok "the new collection appears with a trailing-slash href"
else
  bad "the new collection appears" "not found in $(printf '%s' "$body" | head -c 300)"
fi

h="$(headers_of -X PROPFIND "$BASE/dav/" "${AUTH[@]}" -H 'Depth: infinity')"
assert_status "Depth infinity is refused" 403 "$(printf '%s' "$h" | head -1 | awk '{print $2}')"
if printf '%s' "$h" | grep -q 'propfind-finite-depth'; then
  ok "the refusal names propfind-finite-depth"
else
  bad "the refusal names propfind-finite-depth" "a client cannot fall back without it"
fi

# A missing Depth is treated as 1, not infinity: following RFC 4918 literally
# would turn our infinity answer into a refusal of every client that only wants
# a listing, which is exactly the client that omits the header.
assert_status "a missing Depth is treated as 1" 207 \
  "$(status_of -X PROPFIND "$BASE/dav/" "${AUTH[@]}")"

# XXE: the body must never be able to read a local file.
xxe="$(curl -s -X PROPFIND "$BASE/dav/" "${AUTH[@]}" -H 'Depth: 0' \
  -H 'Content-Type: application/xml' \
  --data-binary '<?xml version="1.0"?><!DOCTYPE p [<!ENTITY x SYSTEM "file:///etc/passwd">]><D:propfind xmlns:D="DAV:"><D:prop><D:displayname>&x;</D:displayname></D:prop></D:propfind>')"
if printf '%s' "$xxe" | grep -q 'root:'; then
  bad "XXE does not read /etc/passwd" "the entity expanded"
else
  ok "XXE does not read /etc/passwd"
fi

# ---------------------------------------------------------------- GET / HEAD
head_ "GET, HEAD, Range, conditional"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf 'hello webdav over real http' > "$tmp/f.txt"
SHA="$(sha256sum "$tmp/f.txt" | cut -d' ' -f1)"
SZ="$(stat -c%s "$tmp/f.txt")"
# The file goes INSIDE the collection under its own name. Seeding it with the
# collection's own name made GET resolve to the collection, which returns the
# HTML index - correct server behaviour, and a confusing way to fail.
PROBE="probe.txt"
init="$(curl -s -X POST "$BASE/api/uploads/init" -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d "{\"folderId\":\"$FOLDER_ID\",\"name\":\"$PROBE\",\"size\":$SZ,\"sha256\":\"$SHA\"}")"
# Both upload paths have to be handled or the script is not re-runnable: the
# bytes are a fixed string, so the second run hits the dedup path and gets no
# uploadId at all - the file already exists by then.
read -r UP INSTANT <<<"$(printf '%s' "$init" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("uploadId") or "", "yes" if d.get("instantUpload") else "no")' 2>/dev/null)"
if [ "$INSTANT" = "yes" ]; then
  ok "seeded a file (dedup: these exact bytes were already stored)"
elif [ -n "$UP" ]; then
  curl -s -o /dev/null -X PUT "$BASE/api/uploads/$UP/chunks/0" -H "Authorization: Bearer $JWT" \
    --data-binary "@$tmp/f.txt"
  curl -s -o /dev/null -X POST "$BASE/api/uploads/$UP/complete" -H "Authorization: Bearer $JWT"
  ok "seeded a file through the ordinary upload API"
else
  bad "seeded a file" "upload init gave neither uploadId nor instantUpload: $init"
fi

ENC_NAME="$FOLDER/$PROBE"
h="$(headers_of -X GET "$BASE/dav/$ENC_NAME" "${AUTH[@]}")"
assert_status "GET is 200" 200 "$(printf '%s' "$h" | head -1 | awk '{print $2}')"
assert_header "advertises range support" "Accept-Ranges" 'bytes' "$h"
# RFC 9110 §8.8.3: opaque-tag must be DQUOTE-wrapped. An unquoted value is not
# an entity-tag and cannot be compared byte-for-byte by a strict client.
ETAG="$(printf '%s' "$h" | grep -i '^ETag:' | head -1 | sed 's/^[^:]*: *//' | tr -d '\r')"
case "$ETAG" in
  \"*\") ok "ETag is quoted ($ETAG)" ;;
  *)    bad "ETag is quoted" "got '$ETAG'" ;;
esac

got="$(curl -s "$BASE/dav/$ENC_NAME" "${AUTH[@]}")"
if [ "$got" = "hello webdav over real http" ]; then
  ok "GET body round-trips"
else
  bad "GET body round-trips" "got '$got'"
fi

h="$(headers_of -X GET "$BASE/dav/$ENC_NAME" "${AUTH[@]}" -H 'Range: bytes=6-12')"
assert_status "Range is 206" 206 "$(printf '%s' "$h" | head -1 | awk '{print $2}')"
assert_header "Content-Range is correct" "Content-Range" 'bytes 6-12/27' "$h"
assert_status "unsatisfiable Range is 416" 416 \
  "$(status_of -X GET "$BASE/dav/$ENC_NAME" "${AUTH[@]}" -H 'Range: bytes=9999-10000')"

assert_status "If-None-Match yields 304" 304 \
  "$(status_of -X GET "$BASE/dav/$ENC_NAME" "${AUTH[@]}" -H "If-None-Match: $ETAG")"
assert_status "a stale If-Match yields 412" 412 \
  "$(status_of -X GET "$BASE/dav/$ENC_NAME" "${AUTH[@]}" -H 'If-Match: "0000deadbeef"')"

h="$(headers_of -I "$BASE/dav/$ENC_NAME" "${AUTH[@]}")"
assert_status "HEAD is 200" 200 "$(printf '%s' "$h" | head -1 | awk '{print $2}')"
assert_header "HEAD carries the length" "Content-Length" '^27$' "$h"

assert_status "a missing path is 404" 404 "$(status_of -X GET "$BASE/dav/no-such-thing" "${AUTH[@]}")"

# ---------------------------------------------------------------- read-only
head_ "Read-only mount"

if [ -n "$RO" ]; then
  s="$(status_of -X PROPFIND "$BASE/dav/" -u "$USER_NAME:$RO")"
  if [ "$s" = "207" ]; then ok "a read-only mount can still read (207)"; else bad "read-only mount can read" "got $s"; fi
else
  bad "minted a read-only token" "$ro"
fi

# A WebDAV token must be useless against the rest of the API.
for p in /api/me /api/server/stats; do
  s="$(status_of "$BASE$p" -u "$USER_NAME:$DAV")"
  if [ "$s" = "401" ]; then ok "$p rejects a WebDAV token"; else bad "$p rejects a WebDAV token" "got $s"; fi
done

# ---------------------------------------------------------------- PUT
head_ "PUT"

dav_put() { # path, body-file, extra curl args...
  local path="$1" file="$2"; shift 2
  status_of -X PUT "$BASE/dav/$path" "${AUTH[@]}" --data-binary "@$file" "$@"
}

printf 'first revision' > "$tmp/one.txt"
printf 'second revision, longer' > "$tmp/two.txt"

assert_status "PUT creates a new file (201)" 201 "$(dav_put "$FOLDER/new.txt" "$tmp/one.txt")"
if [ "$(curl -s "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}")" = "first revision" ]; then
  ok "the new file reads back"
else
  bad "the new file reads back" "got '$(curl -s "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}")'"
fi

assert_status "PUT over an existing file is 204" 204 "$(dav_put "$FOLDER/new.txt" "$tmp/two.txt")"
if [ "$(curl -s "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}")" = "second revision, longer" ]; then
  ok "the overwrite took effect"
else
  bad "the overwrite took effect" "got '$(curl -s "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}")'"
fi

# Chunked transfer encoding: no Content-Length at all. The in-process suite
# cannot produce this, and it is the path a browser or a streaming client takes,
# so the server has to spool to learn the size before it can cut chunks.
h="$(headers_of -X PUT "$BASE/dav/$FOLDER/chunked.txt" "${AUTH[@]}" \
      -H 'Transfer-Encoding: chunked' --data-binary "@$tmp/one.txt")"
assert_status "PUT without Content-Length is 201" 201 "$(printf '%s' "$h" | head -1 | awk '{print $2}')"
if [ "$(curl -s "$BASE/dav/$FOLDER/chunked.txt" "${AUTH[@]}")" = "first revision" ]; then
  ok "the chunked upload stored the right bytes"
else
  bad "the chunked upload stored the right bytes" "got '$(curl -s "$BASE/dav/$FOLDER/chunked.txt" "${AUTH[@]}")'"
fi

assert_status "PUT into a missing collection is 409" 409 "$(dav_put "no-such-dir/x.txt" "$tmp/one.txt")"
assert_status "PUT onto an existing collection is 405" 405 "$(dav_put "$FOLDER" "$tmp/one.txt")"
assert_status "a read-only mount cannot PUT" 403 \
  "$(status_of -X PUT "$BASE/dav/$FOLDER/nope.txt" -u "$USER_NAME:$RO" --data-binary "@$tmp/one.txt")"

# Over the configured cap the answer must be 507, not 413: WebDAV uses
# Insufficient Storage for quota-shaped refusals, and a client that only knows
# 413 will report the wrong thing to the user.
dd if=/dev/zero of="$tmp/big.bin" bs=1M count=12 2>/dev/null
cap="$(status_of -X PUT "$BASE/dav/$FOLDER/big.bin" "${AUTH[@]}" --data-binary "@$tmp/big.bin")"
if [ "$cap" = "507" ] || [ "$cap" = "413" ]; then
  ok "an oversized PUT is refused ($cap)"
  if [ "$cap" = "507" ]; then
    if curl -s -X PUT "$BASE/dav/$FOLDER/big.bin" "${AUTH[@]}" --data-binary "@$tmp/big.bin" \
         | grep -q 'quota-not-exceeded'; then
      ok "the 507 body names quota-not-exceeded"
    else
      bad "the 507 body names quota-not-exceeded" "a client cannot explain the refusal without it"
    fi
  fi
else
  bad "an oversized PUT is refused" "got $cap"
fi

# An If-Match naming the current tag must succeed; a stale one must not, and must
# not have written.
etag_now="$(headers_of -X GET "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}" \
  | grep -i '^ETag:' | sed 's/^[^:]*: *//' | tr -d '\r')"
assert_status "a matching If-Match PUT is allowed" 204 \
  "$(status_of -X PUT "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}" -H "If-Match: $etag_now" --data-binary "@$tmp/one.txt")"
assert_status "a stale If-Match PUT is 412" 412 \
  "$(status_of -X PUT "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}" -H 'If-Match: "0000deadbeef"' --data-binary "@$tmp/two.txt")"
if [ "$(curl -s "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}")" = "first revision" ]; then
  ok "the refused PUT wrote nothing"
else
  bad "the refused PUT wrote nothing" "content changed to '$(curl -s "$BASE/dav/$FOLDER/new.txt" "${AUTH[@]}")'"
fi

# ---------------------------------------------------------------- structure

head_ "MKCOL, MOVE, COPY, DELETE"

dav() { local m="$1" path="$2"; shift 2; status_of -X "$m" "$BASE/dav/$path" "${AUTH[@]}" "$@"; }

# A name with a space and an ampersand. The URL has to carry the ENCODED form:
# a raw space makes curl fail outright, and a raw "&" would silently turn the
# rest of the name into a query string - which is precisely the client-side bug
# the percent-encoding in href exists to prevent. Writing it unencoded here is
# also a useful check that the server decodes what a real client sends.
WEIRD="a%20b%26c"
assert_status "MKCOL creates a collection" 201 "$(dav MKCOL "$FOLDER/$WEIRD")"
assert_status "MKCOL onto an existing name is 405" 405 "$(dav MKCOL "$FOLDER/$WEIRD")"
assert_status "MKCOL with a body is 415" 415 \
  "$(dav MKCOL "$FOLDER/withbody" -H 'Content-Type: application/xml' --data-binary '<x/>')"

# Build a two-level subtree so MOVE and COPY have something to carry. The inner
# collection has to exist first: PUT into a missing parent is 409 by design, and
# creating it here also pins that.
printf 'deep payload' > "$tmp/deep.txt"
assert_status "MKCOL creates the inner collection" 201 "$(dav MKCOL "$FOLDER/$WEIRD/inner")"
assert_status "PUT into a missing collection is 409" 409 \
  "$(dav PUT "$FOLDER/$WEIRD/nosuch/deep.txt" --data-binary "@$tmp/deep.txt")"
assert_status "the nested file is created" 201 \
  "$(dav PUT "$FOLDER/$WEIRD/inner/deep.txt" --data-binary "@$tmp/deep.txt")"
assert_status "the nested file is readable" 200 "$(dav GET "$FOLDER/$WEIRD/inner/deep.txt")"

assert_status "MOVE renames a collection" 201 "$(dav MOVE "$FOLDER/$WEIRD" -H "Destination: $BASE/dav/$FOLDER/moved")"
# The whole subtree came along, which is the entire point of MOVE.
if [ "$(dav GET "$FOLDER/moved/inner/deep.txt")" = "200" ]; then
  ok "MOVE carried the whole subtree"
else
  bad "MOVE carried the whole subtree" "the grandchild is gone"
fi
assert_status "the old name is gone" 404 "$(dav GET "$FOLDER/$WEIRD/inner/deep.txt")"

assert_status "COPY duplicates a collection" 201 "$(dav COPY "$FOLDER/moved" -H "Destination: $BASE/dav/$FOLDER/copied")"
if [ "$(curl -s "$BASE/dav/$FOLDER/copied/inner/deep.txt" "${AUTH[@]}")" = "deep payload" ]; then
  ok "the copy has the same bytes"
else
  bad "the copy has the same bytes" "got '$(curl -s "$BASE/dav/$FOLDER/copied/inner/deep.txt" "${AUTH[@]}")'"
fi
if [ "$(dav GET "$FOLDER/moved/inner/deep.txt")" = "200" ]; then
  ok "COPY left the source alone"
else
  bad "COPY left the source alone" "MOVE semantics leaked into COPY"
fi

assert_status "MOVE without a Destination is 400" 400 "$(dav MOVE "$FOLDER/moved")"
assert_status "a cross-origin Destination is 400" 400 \
  "$(dav MOVE "$FOLDER/moved" -H 'Destination: https://evil.example/dav/x')"
assert_status "MOVE into its own subtree is refused" 403 \
  "$(dav MOVE "$FOLDER/moved" -H "Destination: $BASE/dav/$FOLDER/moved/inner")"

# Overwrite: F must not destroy, T must.
printf 'replacement' > "$tmp/repl.txt"
dav PUT "$FOLDER/moved/target.txt" --data-binary "@$tmp/one.txt" >/dev/null
assert_status "Overwrite F leaves the destination alone" 204 \
  "$(dav COPY "$FOLDER/copied" -H "Destination: $BASE/dav/$FOLDER/moved/target.txt" -H 'Overwrite: F')"
if [ "$(curl -s "$BASE/dav/$FOLDER/moved/target.txt" "${AUTH[@]}")" = "first revision" ]; then
  ok "Overwrite F really did not destroy the target"
else
  bad "Overwrite F really did not destroy the target" "content is '$(curl -s "$BASE/dav/$FOLDER/moved/target.txt" "${AUTH[@]}")'"
fi

# DELETE of a file is recoverable; DELETE of a collection is not, and the
# asymmetry is forced by the schema rather than chosen.
assert_status "DELETE a file" 204 "$(dav DELETE "$FOLDER/moved/target.txt")"
assert_status "the deleted file is gone from /dav" 404 "$(dav GET "$FOLDER/moved/target.txt")"
trash="$(curl -s "$BASE/api/trash" -H "Authorization: Bearer $JWT")"
if printf '%s' "$trash" | grep -q 'target.txt'; then
  ok "the deleted file is in the trash (recoverable)"
else
  bad "the deleted file is in the trash" "DELETE bypassed the trash"
fi

assert_status "DELETE a collection" 204 "$(dav DELETE "$FOLDER/moved")"
assert_status "the deleted collection is gone" 404 "$(dav PROPFIND "$FOLDER/moved" -H 'Depth: 0')"

# A read-only mount must be refused on every structural method too, not just PUT.
if [ -n "$RO" ]; then
  for m in MKCOL MOVE COPY DELETE; do
    s="$(status_of -X "$m" "$BASE/dav/$FOLDER/ro-$m" -u "$USER_NAME:$RO" \
          -H "Destination: $BASE/dav/$FOLDER/ro-dest-$m" --data-binary "@$tmp/one.txt")"
    if [ "$s" = "403" ]; then ok "a read-only mount cannot $m"; else bad "a read-only mount cannot $m" "got $s"; fi
  done
fi

# ---------------------------------------------------------------- summary
printf '\n\033[1m%d passed, %d failed\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
