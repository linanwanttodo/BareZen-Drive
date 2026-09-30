#!/usr/bin/env python3
"""End-to-end functional test against a live BareZen-Drive server.

Usage: BASE=http://host:8080 USER_NAME=alice PASS=password123 python3 scripts/e2e.py
Covers: health/version/manifest, registration switch, chunked upload, instant
upload, downloads with Range, signed links, share lifecycle, server stats and
cleanup. Requires python3 with requests.

NEEDS A FRESH INSTANCE (one with no accounts). The script creates the first
account through the loopback bootstrap path, toggles the registration switch,
and leaves files and folders behind, so it cannot be pointed at an instance that
already has accounts - it stops with exit code 2 and says so instead of printing
a cascade of failures. Point BASE at a newly started server each run.

The version assertion reads the repository's own gradle.properties, so it stays
true across releases; set EXPECT_VERSION to check a server that is not built
from this checkout.
"""
import os
import hashlib
import json
import sys
import time
import math
import requests

BASE = os.environ.get("BASE", "http://127.0.0.1:8080")
USER = os.environ.get("USER_NAME", "e2etest")
PASS = os.environ.get("PASS", "e2epassword123")
TS = time.strftime("%H%M%S")


def repo_version():
    """version= line of gradle.properties, the single source of truth for the
    product version (the server, the apps and update.json all read it)."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "gradle.properties")
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                if line.strip().startswith("version="):
                    return line.strip().split("=", 1)[1].strip()
    except OSError:
        pass
    return None


EXPECT_VERSION = os.environ.get("EXPECT_VERSION") or repo_version()

passed, failed = 0, 0

def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS {name}")
    else:
        failed += 1
        print(f"  FAIL {name}  {detail}")


def skip(name, why):
    """Not a pass, and not a failure either: the precondition this check needs
    is absent. Counting these as passes would be the same lie as deleting the
    check - a run against a reused instance would look as complete as a run
    against a fresh one."""
    print(f"  SKIP {name}  ({why})")


# Whether this run got to create the instance's first account, i.e. whether the
# server was fresh when the run started. Everything that depends on "this
# account is the owner" or "registration was closed by default" is only
# meaningful then.
FRESH = False

s = requests.Session()

# 1. health + version
r = s.get(f"{BASE}/health", timeout=15)
check("health", r.json().get("status") == "ok", r.text)
r = s.get(f"{BASE}/api/version", timeout=30)
v = r.json()
# The repo's version, not a constant copied into this script: a hardcoded one
# fails against every real build, which is a false red, not a finding.
check("version endpoint reports a version", bool(v.get("serverVersion")), r.text)
if EXPECT_VERSION:
    check(f"version matches the repo ({EXPECT_VERSION})", v.get("serverVersion") == EXPECT_VERSION, r.text)
else:
    print("  SKIP version vs repo (gradle.properties not found; set EXPECT_VERSION)")
# `assets` comes from the release manifest (update.json) or the GitHub API, so
# it is empty on a server with neither configured and no outbound network - a
# sandbox, or an air-gapped deployment. Reporting that as a failure is a false
# red of exactly the kind this file used to have with the hardcoded version; it
# is reported as skipped instead, and the reason is printed.
if len(v.get("assets", [])) >= 3:
    check("version carries assets", True, r.text[:200])
else:
    skip("version carries assets", "no release manifest reachable (update.json unset or no outbound network)")
check("version updateAvailable false (same ver)", v.get("updateAvailable") is False, r.text[:200])

# 2. registration defaults to CLOSED
#
# The owner is whichever account exists first, so an open default means the
# first stranger to press register owns the instance: they can list every user,
# delete any account and reopen registration. A fresh instance therefore refuses
# the public path and only admits the install wizard (BOOTSTRAP_ADMIN_*) or a
# request from the host itself.
r = s.get(f"{BASE}/api/settings/registration", timeout=15)
INITIAL_OPEN = r.json().get("open")

# 3. register + login
#
# This run comes from the same host as the server, so it takes the loopback
# bootstrap path - the manual equivalent of the install wizard. That is the
# whole reason this step still succeeds on a fresh instance.
r = s.post(f"{BASE}/api/auth/register", json={"username": USER, "password": PASS}, timeout=20)
# 201 means this run created the instance's first account, which is only
# possible on an instance that had none - that is the proof we were fresh, and
# it is what makes the "default closed" check below mean anything.
#
# The other two answers are equally fine and say nothing about the product: 409
# is "that name is taken", and 403 REGISTRATION_DISABLED is the switch doing its
# job on a reused instance, reached before the uniqueness check ever runs. What
# actually matters is the next line - the account can log in - so that is what
# gets asserted, and the register call is only a best effort at obtaining it.
FRESH = r.status_code == 201
reg_status = f"{r.status_code} {r.text.strip()}"
check("register (accepted, taken, or closed)",
      r.status_code in (201, 409, 403), r.text)
if FRESH:
    check("registration default closed", INITIAL_OPEN is False,
          "a fresh instance must refuse the public register path")
else:
    skip("registration default closed", f"instance already had accounts; switch was open={INITIAL_OPEN}")
r = s.post(f"{BASE}/api/auth/login", json={"username": USER, "password": PASS}, timeout=20)
if r.status_code != 200 and not FRESH:
    # This script mutates the instance it runs against: it closes and reopens
    # the registration switch, and leaves files and folders behind. Pointed at
    # an instance that already has accounts, the run cannot get in - the
    # account does not exist and registration is (correctly) closed - and
    # every later check would fail for that one reason. Say so once, here,
    # instead of printing thirty confusing reds.
    print(
        f"\nThis run needs an instance with no accounts yet.\n"
        f"  register returned: {reg_status}\n"
        f"  login returned:    {r.text.strip()}\n"
        f"  (a reused instance also shares the 5-per-5min register budget with "
        f"the run that created it, which shows up as RATE_LIMITED)\n"
        f"Point BASE at a fresh server, or re-run with the same USER_NAME on the "
        f"one that created the account.",
        file=sys.stderr,
    )
    sys.exit(2)
check("login", r.status_code == 200, r.text)
tok = r.json()["accessToken"]
refresh = r.json()["refreshToken"]
H = {"Authorization": f"Bearer {tok}"}

# 4. me
r = s.get(f"{BASE}/api/me", headers=H, timeout=15)
check("me returns username", r.json().get("username") == USER, r.text)
# Ask the server whether this account is the owner rather than inferring it
# from the run order: on a reused instance the user that created the first
# account is still the owner, while a differently-named user is not, and the
# owner-only endpoints below answer differently in each case.
IS_OWNER = r.json().get("isOwner") is True

# 5. folder create + listing
r = s.post(f"{BASE}/api/folders", headers=H, json={"name": f"docs-{TS}"}, timeout=15)
check("create folder", r.status_code == 201, r.text)
folder_id = r.json()["id"]
r = s.get(f"{BASE}/api/folders/root/contents", headers=H, timeout=15)
check("list root has folder", any(f["name"].startswith("docs-") for f in r.json()["folders"]), r.text[:200])
check("version response has explicit updateAvailable", "updateAvailable" in s.get(f"{BASE}/api/version", timeout=30).json())

# 6. chunked upload (5 MiB chunk size, two chunks)
data1 = bytes(range(256)) * 4096            # 1 MiB
data2 = b"BareZen" * 200000                  # 1.4 MiB
full = data1 + data2
sha = hashlib.sha256(full).hexdigest()
r = s.post(f"{BASE}/api/uploads/init", headers=H, json={
    "folderId": folder_id, "name": f"e2e-{TS}.bin", "size": len(full),
    "mimeType": "application/octet-stream", "chunkSize": 1048576}, timeout=20)
up = r.json()
check("upload init", "uploadId" in up and up.get("instantUpload") is False, r.text)
uid = up["uploadId"]
chunk = up["chunkSize"]
# Protocol: every chunk except the last is exactly chunkSize bytes.
n_chunks = math.ceil(len(full) / chunk)
ok_chunks = 0
for i in range(n_chunks):
    part = full[i * chunk:(i + 1) * chunk]
    r = s.put(f"{BASE}/api/uploads/{uid}/chunks/{i}", headers=H, data=part, timeout=60)
    if r.status_code == 204:
        ok_chunks += 1
check("chunks uploaded", ok_chunks == n_chunks, f"{ok_chunks}/{n_chunks}")
r = s.post(f"{BASE}/api/uploads/{uid}/complete", headers=H, timeout=60)
check("upload complete", r.status_code == 200 and r.json()["file"]["size"] == len(full), r.text)
file_id = r.json()["file"]["id"]

# 7. instant upload (same bytes, different name)
r = s.post(f"{BASE}/api/uploads/init", headers=H, json={
    "folderId": folder_id, "name": f"e2ecopy-{TS}.bin", "size": len(full), "sha256": sha,
    "mimeType": "application/octet-stream"}, timeout=20)
up2 = r.json()
check("instant upload", up2.get("instantUpload") is True and up2.get("file"), r.text)

# 8. download full + Range
r = s.get(f"{BASE}/api/files/{file_id}/content", headers=H, timeout=60)
check("download matches sha256", hashlib.sha256(r.content).hexdigest() == sha)
r = s.get(f"{BASE}/api/files/{file_id}/content", headers={**H, "Range": "bytes=0-99"}, timeout=30)
check("range 206", r.status_code == 206 and len(r.content) == 100, f"{r.status_code} len={len(r.content)}")
r = s.get(f"{BASE}/api/files/{file_id}/content", headers={**H, "Range": f"bytes={len(full)+10}-"}, timeout=30)
check("range 416", r.status_code == 416, str(r.status_code))

# 9. signed link
r = s.get(f"{BASE}/api/files/{file_id}/link", headers=H, timeout=15)
check("signed link", r.status_code == 200 and r.json()["url"].startswith("/api/files/"), r.text)
r = s.get(f"{BASE}{r.json()['url']}", timeout=30)
check("signed link fetches content", hashlib.sha256(r.content).hexdigest() == sha)

# 10. share link lifecycle
r = s.post(f"{BASE}/api/shares", headers=H, json={"fileId": file_id}, timeout=15)
check("share create", r.status_code == 201, r.text)
share_url = r.json()["url"]
share_id = r.json()["id"]
token = share_url.rsplit("/", 1)[-1]
r = s.get(f"{BASE}/api/public/shares/{token}", timeout=15)
check("public share info", r.status_code == 200 and r.json().get("name", "").startswith("e2e-"), r.text)
r = s.get(f"{BASE}/api/public/shares/{token}/files/{file_id}/content", timeout=60)
check("public share download", hashlib.sha256(r.content).hexdigest() == sha)
r = s.get(f"{BASE}/api/shares", headers=H, timeout=15)
check("share counters visible", r.json()["shares"][0]["viewCount"] >= 1, r.text[:200])
r = s.delete(f"{BASE}/api/shares/{share_id}", headers=H, timeout=15)
check("share revoke", r.status_code == 204, r.text)
r = s.get(f"{BASE}/api/public/shares/{token}", timeout=15)
check("revoked share API 404", r.status_code == 404, str(r.status_code))
r = s.get(f"{BASE}{share_url}", timeout=15)
check("share SPA page renders", r.status_code == 200 and "text/html" in r.headers.get("Content-Type", ""), f"{r.status_code}")

# 11. recent + album
r = s.get(f"{BASE}/api/files/recent", headers=H, timeout=15)
check("recent files", r.status_code == 200 and len(r.json()["files"]) >= 1, r.text[:200])

# 12. server stats (owner only: any signed-in account used to read the host profile)
r = s.get(f"{BASE}/api/server/stats", headers=H, timeout=15)
if IS_OWNER:
    check("server stats", r.status_code == 200 and r.json()["diskTotalBytes"] > 0, r.text[:200])
else:
    # Any signed-in account used to read the host profile, and registration was
    # open by default, so self-registering was enough to get it.
    check("server stats refuses a non-owner", r.status_code == 403, r.text[:200])

# 13. registration switch: close, verify register 403 + login hidden path, reopen
if not IS_OWNER:
    skip("registration switch round trip", "toggling it needs the owner account")
else:
    r = s.patch(f"{BASE}/api/settings/registration", headers=H, json={"open": False}, timeout=15)
    check("close registration", r.status_code == 200 and r.json()["open"] is False, r.text)
    r = s.get(f"{BASE}/api/settings/registration", timeout=15)
    check("status reflects closed", r.json()["open"] is False, r.text)
# A forwarded header disqualifies the loopback bootstrap slot: once something
# upstream annotated the request, the server cannot tell "from the host" apart
# from "from the internet", and the slot stays shut. Without the header this run
# WOULD be admitted on a fresh instance - it is on the host - which is exactly
# why the check has to send one.
r = s.post(f"{BASE}/api/auth/register", json={"username": "intruder9", "password": "password123"},
           headers={"X-Forwarded-For": "203.0.113.9"}, timeout=15)
check("register rejected 403 REGISTRATION_DISABLED",
      r.status_code == 403 and r.json()["error"]["code"] == "REGISTRATION_DISABLED", r.text)
r = s.post(f"{BASE}/api/auth/login", json={"username": USER, "password": PASS}, timeout=15)
check("existing user still logs in", r.status_code == 200, r.text)

# 14. refresh rotation still fine while closed
r = s.post(f"{BASE}/api/auth/refresh", json={"refreshToken": refresh}, timeout=15)
check("refresh works when registration closed", r.status_code == 200, r.text)
new_refresh = r.json()["refreshToken"]

# 15. put the switch back where this run found it, so a second run against the
# same instance starts from the same state instead of inheriting an open door.
if IS_OWNER:
    r = s.patch(f"{BASE}/api/settings/registration", headers=H, json={"open": INITIAL_OPEN}, timeout=15)
    check("restore registration switch", r.json()["open"] is INITIAL_OPEN, r.text)

# 16. cleanup: delete files + folder (blob refcount)
r = s.delete(f"{BASE}/api/files/{file_id}", headers=H, timeout=15)
check("delete file 1", r.status_code == 204, r.text)
file2 = up2["file"]["id"]
r = s.delete(f"{BASE}/api/files/{file2}", headers=H, timeout=15)
check("delete file 2", r.status_code == 204, r.text)
r = s.delete(f"{BASE}/api/folders/{folder_id}", headers=H, timeout=15)
check("delete folder", r.status_code == 204, r.text)

print(f"\n{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
