#!/usr/bin/env python3
#!/usr/bin/env python3
"""End-to-end functional test against a live BareZen-Drive server.

Usage: BASE=http://host:8080 USER=alice PASS=password123 python3 scripts/e2e.py
Covers: health/version/manifest, registration switch, chunked upload, instant
upload, downloads with Range, signed links, share lifecycle, server stats and
cleanup. Requires python3 with requests.
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

passed, failed = 0, 0

def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS {name}")
    else:
        failed += 1
        print(f"  FAIL {name}  {detail}")

s = requests.Session()

# 1. health + version
r = s.get(f"{BASE}/health", timeout=15)
check("health", r.json().get("status") == "ok", r.text)
r = s.get(f"{BASE}/api/version", timeout=30)
v = r.json()
check("version endpoint", v.get("serverVersion") == "0.0.2", r.text)
check("version carries assets", len(v.get("assets", [])) >= 3, r.text[:200])
check("version updateAvailable false (same ver)", v.get("updateAvailable") is False, r.text[:200])

# 2. registration status default open
r = s.get(f"{BASE}/api/settings/registration", timeout=15)
check("registration default open", r.json().get("open") is True, r.text)

# 3. register + login
r = s.post(f"{BASE}/api/auth/register", json={"username": USER, "password": PASS}, timeout=20)
check("register owner (or exists)", r.status_code in (201, 409), r.text)
r = s.post(f"{BASE}/api/auth/login", json={"username": USER, "password": PASS}, timeout=20)
check("login", r.status_code == 200, r.text)
tok = r.json()["accessToken"]
refresh = r.json()["refreshToken"]
H = {"Authorization": f"Bearer {tok}"}

# 4. me
r = s.get(f"{BASE}/api/me", headers=H, timeout=15)
check("me returns username", r.json().get("username") == USER, r.text)

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

# 12. server stats
r = s.get(f"{BASE}/api/server/stats", headers=H, timeout=15)
check("server stats", r.status_code == 200 and r.json()["diskTotalBytes"] > 0, r.text[:200])

# 13. registration switch: close, verify register 403 + login hidden path, reopen
r = s.patch(f"{BASE}/api/settings/registration", headers=H, json={"open": False}, timeout=15)
check("close registration", r.status_code == 200 and r.json()["open"] is False, r.text)
r = s.get(f"{BASE}/api/settings/registration", timeout=15)
check("status reflects closed", r.json()["open"] is False, r.text)
r = s.post(f"{BASE}/api/auth/register", json={"username": "intruder9", "password": "password123"}, timeout=15)
check("register rejected 403 REGISTRATION_DISABLED",
      r.status_code == 403 and r.json()["error"]["code"] == "REGISTRATION_DISABLED", r.text)
r = s.post(f"{BASE}/api/auth/login", json={"username": USER, "password": PASS}, timeout=15)
check("existing user still logs in", r.status_code == 200, r.text)

# 14. refresh rotation still fine while closed
r = s.post(f"{BASE}/api/auth/refresh", json={"refreshToken": refresh}, timeout=15)
check("refresh works when registration closed", r.status_code == 200, r.text)
new_refresh = r.json()["refreshToken"]

# 15. reopen for the real owner
r = s.patch(f"{BASE}/api/settings/registration", headers=H, json={"open": True}, timeout=15)
check("reopen registration", r.json()["open"] is True, r.text)

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
