#!/usr/bin/env bash
# Real-HTTP smoke test for the S3-compatible surface.
#
# Usage:
#   BASE=http://127.0.0.1:18090 USER_NAME=s3user PASS=password123 \
#     SERVER_LOG=/tmp/bz-s3-server.log bash scripts/s3_smoke.sh
#
# WHY THIS EXISTS
#
# The server's own suite drives S3 through Ktor's in-process test host, and rclone
# is never involved. Two whole classes of bug survive that: headers that exist in
# the shared response object but never reach the wire, and the parts of the S3
# dialect rclone actually depends on (the `X-Amz-Meta-*` round trip, path-style
# URLs, presigned GET, what a `purge` really sends). This script talks to a
# running server with a real S3 client.
#
# Requires: curl, python3. rclone is optional - when it is missing the rclone
# assertions are reported as UNVERIFIED and the script still exits 0, because a
# skipped client check is honest whereas a faked one is not. The curl-only
# assertions (routing, presigning, log redaction) always run.
#
# It creates its own bucket, objects and tokens, and deletes what it made.

set -uo pipefail

BASE="${BASE:-http://127.0.0.1:18080}"
USER_NAME="${USER_NAME:-s3user}"
PASS="${PASS:-password123}"
BUCKET="${BUCKET:-bzsmoke}"
BUCKET2="${BUCKET2:-bzsmoke2}"
SERVER_LOG="${SERVER_LOG:-server.log}"

pass=0
fail=0
skipped=0

ok()      { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass + 1)); }
bad()     { printf '  \033[31mFAIL\033[0m %s\n' "$1"; printf '       %s\n' "${2:-}"; fail=$((fail + 1)); }
skip()    { printf '  \033[33m--\033[0m    %s (UNVERIFIED: %s)\n' "$1" "${2:-}"; skipped=$((skipped + 1)); }
head_()   { printf '\n\033[1m%s\033[0m\n' "$1"; }

assert_status() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then ok "$label ($got)"; else bad "$label" "expected $want, got $got"; fi
}

assert_header() {
  local label="$1" name="$2" want="$3" raw="$4" got
  got="$(printf '%s' "$raw" | grep -i "^${name}:" | head -1 | sed "s/^[^:]*: *//" | tr -d '\r')"
  if printf '%s' "$got" | grep -Eqi "$want"; then
    ok "$label ($name: $got)"
  else
    bad "$label" "expected $name matching /$want/, got '${got:-<absent>}'"
  fi
}

assert_body() {
  local label="$1" want="$2" got="$3"
  if printf '%s' "$got" | grep -Eq "$want"; then ok "$label"; else bad "$label" "expected /$want/ in body: $(printf '%s' "$got" | head -c 240)"; fi
}

assert_no_body() {
  local label="$1" want="$2" got="$3"
  if printf '%s' "$got" | grep -Eq "$want"; then bad "$label" "must not contain /$want/: $(printf '%s' "$got" | head -c 240)"; else ok "$label"; fi
}

# ---------------------------------------------------------------- setup

head_ "setup"

if ! command -v rclone >/dev/null 2>&1; then
  echo "  rclone is not on PATH: the real-client half of this script is SKIPPED." >&2
  echo "  Install rclone and re-run to verify the client-facing behaviour." >&2
  HAVE_RCLONE=0
else
  HAVE_RCLONE=1
fi

curl_post_json() {
  curl -sS -o "$2" -w '%{http_code}' -X POST "$BASE$1" \
    -H 'Content-Type: application/json' -d "$3"
}

TOKEN=""
code="$(curl_post_json /api/auth/register /tmp/s3smoke-register.json \
  "{\"username\":\"${USER_NAME}\",\"password\":\"${PASS}\"}")"
case "$code" in
  200|201|409) ok "register endpoint reachable ($code)" ;;
  *) bad "register" "unexpected $code: $(cat /tmp/s3smoke-register.json 2>/dev/null | head -c 200)" ;;
esac

code="$(curl_post_json /api/auth/login /tmp/s3smoke-login.json \
  "{\"username\":\"${USER_NAME}\",\"password\":\"${PASS}\"}")"
assert_status "login" 200 "$code"
TOKEN="$(python3 -c 'import json,sys; print(json.load(open("/tmp/s3smoke-login.json"))["accessToken"])' 2>/dev/null)"
if [ -z "$TOKEN" ]; then bad "login yields an access token" "no accessToken"; exit 1; fi
ok "login yields an access token"

# A WebDAV token carries the S3 credentials: id == Access Key, plaintext == Secret Key.
code="$(curl -sS -o /tmp/s3smoke-token.json -w '%{http_code}' -X POST "$BASE/api/webdav/tokens" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"label":"smoke","readOnly":false}')"
assert_status "create a read-write token" 201 "$code"
ACCESS_KEY="$(python3 -c 'import json; print(json.load(open("/tmp/s3smoke-token.json"))["token"]["id"])')"
SECRET_KEY="$(python3 -c 'import json; print(json.load(open("/tmp/s3smoke-token.json"))["plaintext"])')"
if [ -n "$ACCESS_KEY" ] && [ -n "$SECRET_KEY" ]; then
  ok "token exposes an access key and a plaintext secret"
else
  bad "token exposes an access key and a plaintext secret" "id or plaintext missing"
  exit 1
fi

api_get() { curl -sS -H "Authorization: Bearer $TOKEN" "$BASE$1"; }

# Create the bucket as an ordinary folder through /api: that is the whole premise
# of the surface (bucket == top-level folder) and it hands us the folder id the
# later assertions need.
code="$(curl -sS -o /tmp/s3smoke-bucket.json -w '%{http_code}' -X POST "$BASE/api/folders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"parentId\":null,\"name\":\"${BUCKET}\"}")"
assert_status "create the bucket folder via /api" 201 "$code"
BUCKET_ID="$(python3 -c 'import json; print(json.load(open("/tmp/s3smoke-bucket.json"))["id"])')"
if [ -z "$BUCKET_ID" ]; then bad "bucket folder id" "not returned"; exit 1; fi

find_file_id() {
  api_get "/api/folders/${BUCKET_ID}/contents" | BUCKET_NAME="$1" python3 -c '
import json,os,sys
body=json.load(sys.stdin)
want=os.environ["BUCKET_NAME"]
for f in body.get("files",[]):
    if f.get("name")==want:
        print(f["id"]); break
' 2>/dev/null
}

# ---------------------------------------------------------------- routing floor

head_ "routing: S3 must not swallow the other surfaces"

body="$(curl -sS "$BASE/health")"
assert_body "GET /health still answers JSON" '"(status|ok|version)"' "$body"
assert_no_body "GET /health is not S3 XML" '<Error>' "$body"

curl -sS -D - -o /tmp/s3smoke-deep.txt "$BASE/some/deep/link" >/dev/null
body="$(cat /tmp/s3smoke-deep.txt)"
assert_no_body "an unsigned deep link is not answered with S3 XML" '<Code>NoSuchBucket</Code>' "$body"
assert_no_body "an unsigned deep link carries no S3 error envelope" '<Error>' "$body"

head_ "credentials and errors over a real socket"

# 既没有 Authorization 也没有 x-amz-* query：路由按 spec §4.2 保守交回 SPA。
# 这里断言的是"没被 S3 抢走"，不是 403——匿名 GET 本来就不该变成 NoSuchBucket XML。
code="$(curl -sS -o /tmp/s3smoke-anon.json -w '%{http_code}' "$BASE/$BUCKET")"
assert_status "an unsigned bucket GET falls through to the SPA" 200 "$code"
assert_no_body "an unsigned bucket GET is not S3 XML" '<Code>NoSuchBucket</Code>' "$(cat /tmp/s3smoke-anon.json)"

# 带 SigV4 痕迹的错签名必须被拦下，且原因说清楚。日期取当前时刻：
# 写死的时间戳会先撞上 15 分钟偏移判定，测到的就不是签名错误了。
AMZ_DATE="$(date -u +%Y%m%dT%H%M%SZ)"
DATE_STAMP="$(date -u +%Y%m%d)"
code="$(curl -sS -D /tmp/s3smoke-badsig.hdr -o /tmp/s3smoke-badsig.json -w '%{http_code}' \
  -H "Authorization: AWS4-HMAC-SHA256 Credential=${ACCESS_KEY}/${DATE_STAMP}/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=$(printf '0%.0s' $(seq 64))" \
  -H "x-amz-date: $AMZ_DATE" -H "x-amz-content-sha256: UNSIGNED-PAYLOAD" \
  "$BASE/$BUCKET")"
assert_status "a wrong signature is rejected" 403 "$code"
assert_body "a wrong signature names the reason" '<Code>SignatureDoesNotMatch</Code>' "$(cat /tmp/s3smoke-badsig.json)"

# 每个 S3 响应都带 request id，包括上面这个错误响应。
assert_header "error responses carry x-amz-request-id" 'x-amz-request-id' '.+' "$(cat /tmp/s3smoke-badsig.hdr)"

# ---------------------------------------------------------------- rclone

if [ "$HAVE_RCLONE" -eq 1 ]; then
  export RCLONE_CONFIG_BZSMOKE_TYPE=s3
  export RCLONE_CONFIG_BZSMOKE_PROVIDER=Other
  export RCLONE_CONFIG_BZSMOKE_ACCESS_KEY_ID="$ACCESS_KEY"
  export RCLONE_CONFIG_BZSMOKE_SECRET_ACCESS_KEY="$SECRET_KEY"
  export RCLONE_CONFIG_BZSMOKE_ENDPOINT="$BASE"
  export RCLONE_CONFIG_BZSMOKE_REGION=us-east-1
  export RCLONE_CONFIG_BZSMOKE_FORCE_PATH_STYLE=true

  head_ "rclone $(rclone version 2>/dev/null | head -1 | awk '{print $2}') against $BASE"

  lsd="$(rclone lsd "bzsmoke:" 2>&1)"
  assert_body "an /api-created folder is visible as a bucket" ".*${BUCKET}\$" "$lsd"

  if rclone mkdir "bzsmoke:$BUCKET2" 2>/tmp/s3smoke-mkdir.err; then
    ok "rclone mkdir creates a bucket"
    assert_body "the new bucket lists back" ".*${BUCKET2}\$" "$(rclone lsd "bzsmoke:" 2>&1)"
  else
    bad "rclone mkdir creates a bucket" "$(head -c 300 /tmp/s3smoke-mkdir.err)"
  fi

  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  mkdir -p "$WORK/overwrite"
  head -c 204800 /dev/urandom > "$WORK/small.txt"
  head -c 12582912 /dev/urandom > "$WORK/big.bin"
  SMALL_SHA="$(sha256sum "$WORK/small.txt" | awk '{print $1}')"
  BIG_SHA="$(sha256sum "$WORK/big.bin" | awk '{print $1}')"

  # --s3-upload-cutoff has to drop below 12 MiB or the "big" file never reaches
  # multipart; the default cutoff is far larger than this file.
  if rclone copy "$WORK" "bzsmoke:$BUCKET" --s3-upload-cutoff 5M -q 2>/tmp/s3smoke-copy.err; then
    ok "rclone copy uploads a 200 KB and a 12 MB object"
  else
    bad "rclone copy uploads a 200 KB and a 12 MB object" "$(head -c 400 /tmp/s3smoke-copy.err)"
  fi

  ls_out="$(rclone ls "bzsmoke:$BUCKET" 2>&1)"
  assert_body "rclone ls lists the small object" "small\.txt\$" "$ls_out"
  assert_body "rclone ls lists the large object" "big\.bin\$" "$ls_out"

  if rclone check "$WORK" "bzsmoke:$BUCKET" 2>/tmp/s3smoke-check.err; then
    ok "rclone check passes (metadata and checksum round trip)"
  else
    bad "rclone check passes (metadata and checksum round trip)" "$(head -c 500 /tmp/s3smoke-check.err)"
  fi

  got_big="$(rclone cat "bzsmoke:$BUCKET/big.bin" 2>/dev/null | sha256sum | awk '{print $1}')"
  if [ "$got_big" = "$BIG_SHA" ]; then ok "rclone cat returns the 12 MB object byte for byte"; else bad "rclone cat returns the 12 MB object byte for byte" "$BIG_SHA != $got_big"; fi

  # Overwrite with different bytes: S3 PUT is an overwrite, so no revision row.
  head -c 204800 /dev/urandom > "$WORK/overwrite/small.txt"
  NEW_SHA="$(sha256sum "$WORK/overwrite/small.txt" | awk '{print $1}')"
  SMALL_ID="$(find_file_id small.txt)"
  if rclone copy "$WORK/overwrite" "bzsmoke:$BUCKET" -q 2>/tmp/s3smoke-overwrite.err; then
    ok "rclone copy overwrites an existing object"
  else
    bad "rclone copy overwrites an existing object" "$(head -c 300 /tmp/s3smoke-overwrite.err)"
  fi
  got_small="$(rclone cat "bzsmoke:$BUCKET/small.txt" 2>/dev/null | sha256sum | awk '{print $1}')"
  if [ "$got_small" = "$NEW_SHA" ]; then ok "the overwritten bytes came back"; else bad "the overwritten bytes came back" "$NEW_SHA != $got_small"; fi

  if [ -n "$SMALL_ID" ]; then
    versions_body="$(api_get "/api/files/${SMALL_ID}/versions")"
    assert_body "an overwrite created no file version" '"versions" *: *\[\]' "$versions_body"
  else
    bad "an overwrite created no file version" "could not resolve the id of small.txt"
  fi

  # rclone carries an object's mtime in x-amz-meta-mtime. If the server dropped it
  # on the floor, LIST would hand back "now" and rclone check would re-upload forever.
  # lsjson 打印的是本地时间，所以期望值也按本地时区算，否则会差一个时区偏移。
  touch -d '2020-01-02T03:04:05Z' "$WORK/mtimes.txt"
  MT_EPOCH="$(date -d '2020-01-02T03:04:05Z' +%s)"
  MT_WANT="$(date -d "@$MT_EPOCH" +%Y-%m-%dT%H:%M:%S)"
  rclone copyto "$WORK/mtimes.txt" "bzsmoke:$BUCKET/mtimes.txt" --s3-upload-cutoff 5M -q 2>/dev/null
  mt="$(rclone lsjson "bzsmoke:$BUCKET" 2>/dev/null | python3 -c '
import json,sys
for e in json.load(sys.stdin):
    if e.get("Name")=="mtimes.txt":
        print((e.get("ModTime") or "")[:19]); break
')"
  if [ "$mt" = "$MT_WANT" ]; then ok "x-amz-meta-mtime round trips through LIST"
  else bad "x-amz-meta-mtime round trips through LIST" "expected $MT_WANT, got '${mt:-<none>}'"; fi

  if rclone delete "bzsmoke:$BUCKET" -q 2>/tmp/s3smoke-delete.err; then
    ok "rclone delete removes the objects"
  else
    bad "rclone delete removes the objects" "$(head -c 300 /tmp/s3smoke-delete.err)"
  fi
  assert_no_body "the deleted objects are gone from the listing" 'small\.txt|big\.bin' "$(rclone ls "bzsmoke:$BUCKET" 2>&1)"

  # The implicit directories behind big.bin/mtimes.txt must be pruned, or the
  # bucket can never be removed - this is what pins s3PruneEmptyAncestors.
  if rclone rmdir "bzsmoke:$BUCKET" 2>/tmp/s3smoke-rmdir.err; then
    ok "rclone rmdir removes an emptied bucket (implicit directories were pruned)"
  else
    bad "rclone rmdir removes an emptied bucket" "$(head -c 300 /tmp/s3smoke-rmdir.err)"
  fi

  assert_no_body "S3 deletion did not go to the trash" '"name": *"(small\.txt|big\.bin|mtimes\.txt)"' "$(api_get /api/trash)"

  # ------------------------------------------------ presigning
  head_ "presigned URLs (rclone signs them, so this is not self-confirming)"

  rclone copyto "$WORK/small.txt" "bzsmoke:$BUCKET/presign.txt" --s3-upload-cutoff 5M -q 2>/dev/null
  URL="$(rclone link "bzsmoke:$BUCKET/presign.txt" --expire 10m 2>/dev/null)"
  if [ -z "$URL" ]; then
    bad "rclone link produces a presigned URL" "rclone link returned nothing"
  else
    assert_body "the presigned URL is path style" "/${BUCKET}/presign\.txt" "$URL"
    assert_body "the presigned URL carries a SigV4 query" 'X-Amz-Signature=[0-9a-f]{64}' "$URL"
    code="$(curl -sS -o /tmp/s3smoke-presign.bin -w '%{http_code}' "$URL")"
    assert_status "a presigned GET succeeds with no Authorization header" 200 "$code"
    got="$(sha256sum /tmp/s3smoke-presign.bin | awk '{print $1}')"
    want="$(sha256sum "$WORK/small.txt" | awk '{print $1}')"
    if [ "$got" = "$want" ]; then ok "the presigned GET returns the right bytes"; else bad "the presigned GET returns the right bytes" "$want != $got"; fi

    # rclone link signs for at most the requested lifetime, so asking for one
    # second and waiting it out is what produces a genuinely expired URL.
    SHORT="$(rclone link "bzsmoke:$BUCKET/presign.txt" --expire 1s 2>/dev/null)"
    sleep 4
    code="$(curl -sS -o /tmp/s3smoke-expired.json -w '%{http_code}' "$SHORT")"
    assert_status "an expired presigned URL is refused" 403 "$code"
    assert_body "an expired presigned URL says RequestExpired" '<Code>RequestExpired</Code>' "$(cat /tmp/s3smoke-expired.json)"

    # ------------------------------------------------ log redaction
    head_ "access log hygiene"
    if [ -r "$SERVER_LOG" ]; then
      if grep -q 'X-Amz-Signature=\*\*\*' "$SERVER_LOG"; then
        ok "the access log masks X-Amz-Signature"
      else
        bad "the access log masks X-Amz-Signature" "no 'X-Amz-Signature=***' line in $SERVER_LOG"
      fi
      if grep -Eq "X-Amz-Signature=[0-9a-f]{64}" "$SERVER_LOG"; then
        bad "no real signature reached the log" "a full signature was found in $SERVER_LOG"
      else
        ok "no real signature reached the log"
      fi
    else
      skip "access log redaction" "$SERVER_LOG is not readable; set SERVER_LOG"
    fi
  fi

  rclone purge "bzsmoke:$BUCKET" -q 2>/dev/null || true
  rclone purge "bzsmoke:$BUCKET2" -q 2>/dev/null || true
else
  head_ "rclone"
  skip "bucket/object lifecycle through rclone" "rclone not installed"
  skip "rclone check (metadata and checksum round trip)" "rclone not installed"
  skip "multipart upload via rclone" "rclone not installed"
  skip "empty-directory pruning via rmdir" "rclone not installed"
  skip "presigned GET via rclone link" "rclone not installed"
  skip "access log redaction" "needs a presigned request from rclone"
fi

# ---------------------------------------------------------------- cleanup

head_ "cleanup"

code="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/webdav/tokens/${ACCESS_KEY}" \
  -H "Authorization: Bearer $TOKEN")"
assert_status "the smoke token can be revoked" 204 "$code"

# ---------------------------------------------------------------- summary

printf '\n%s\n' "----------------------------------------"
printf '%d passed, %d failed, %d unverified\n' "$pass" "$fail" "$skipped"
[ "$fail" -eq 0 ] || exit 1
