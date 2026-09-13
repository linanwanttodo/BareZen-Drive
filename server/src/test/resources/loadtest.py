#!/usr/bin/env python3
"""Load test for a BareZen-Drive deployment.

Simulates concurrent users doing register -> chunked upload -> download ->
share creation -> anonymous share download, then reports timings and any
failures. Reads the target base URL from argv. Run from any machine with
network access to the server; the load itself stays off the server CPU.

Usage: python3 loadtest.py http://144.24.63.79:8080 [concurrency] [files_per_user]
"""
import concurrent.futures
import hashlib
import io
import json
import os
import random
import string
import sys
import time
import urllib.request

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
CONCURRENCY = int(sys.argv[2]) if len(sys.argv) > 2 else 4
FILES_PER_USER = int(sys.argv[3]) if len(sys.argv) > 3 else 2
CHUNK = 5 * 1024 * 1024


def http(method: str, url: str, data: bytes | None = None, token: str | None = None, timeout: int = 120):
    req = urllib.request.Request(url, data=data, method=method)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if data is not None and token is None and method == "PUT":
        pass  # binary body without content-type is fine for chunk puts
    else:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read()


def rand_name(n=10):
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def one_user(user_idx: int) -> dict:
    stats = {"uploads": 0, "upload_bytes": 0, "downloads": 0, "shares": 0, "share_downloads": 0, "errors": []}
    user = f"lt{int(time.time())}{user_idx}{rand_name(4)}"
    try:
        status, _ = http("POST", f"{BASE}/api/auth/register", json.dumps({"username": user, "password": "password123"}).encode())
        if status not in (200, 201):
            stats["errors"].append(f"register {status}")
            return stats
        _, body = http("POST", f"{BASE}/api/auth/login", json.dumps({"username": user, "password": "password123"}).encode())
        token = json.loads(body)["accessToken"]

        for f in range(FILES_PER_USER):
            # Random payload between 1 and 12 MiB to cross chunk boundaries.
            size = random.randint(1 * 1024 * 1024, 12 * 1024 * 1024)
            payload = os.urandom(size)
            sha = hashlib.sha256(payload).hexdigest()
            name = f"lt-{user_idx}-{f}.bin"
            _, body = http("POST", f"{BASE}/api/uploads/init", json.dumps(
                {"name": name, "size": size, "sha256": sha, "chunkSize": CHUNK}).encode(), token)
            init = json.loads(body)
            if init.get("instantUpload"):
                stats["uploads"] += 1
                stats["upload_bytes"] += size
                file_id = init["file"]["id"]
            else:
                uid = init["uploadId"]
                off = 0
                i = 0
                ok = True
                while off < size:
                    end = min(off + CHUNK, size)
                    try:
                        status, _ = http("PUT", f"{BASE}/api/uploads/{uid}/chunks/{i}", payload[off:end], token)
                        if status != 204:
                            stats["errors"].append(f"chunk {status}")
                            ok = False
                            break
                    except Exception as e:
                        stats["errors"].append(f"chunk {type(e).__name__}: {e}")
                        ok = False
                        break
                    off = end
                    i += 1
                if ok:
                    status, body = http("POST", f"{BASE}/api/uploads/{uid}/complete", b"", token)
                    if status != 200:
                        stats["errors"].append(f"complete {status}")
                        continue
                    stats["uploads"] += 1
                    stats["upload_bytes"] += size
                    file_id = json.loads(body)["file"]["id"]

            # download round trip
            try:
                status, got = http("GET", f"{BASE}/api/files/{file_id}/content", None, token)
                if status == 200 and len(got) == size:
                    stats["downloads"] += 1
                else:
                    stats["errors"].append(f"download {status}")
            except Exception as e:
                stats["errors"].append(f"download {type(e).__name__}: {e}")

            # share + anonymous download
            try:
                status, body = http("POST", f"{BASE}/api/shares", json.dumps({"fileId": file_id}).encode(), token)
                if status == 201:
                    stats["shares"] += 1
                    token_url = json.loads(body)["url"]
                    token_part = token_url.removeprefix("/s/")
                    status, info = http("GET", f"{BASE}/api/public/shares/{token_part}")
                    if status == 200:
                        status, got = http("GET", f"{BASE}/api/public/shares/{token_part}/files/{file_id}/content")
                        if status == 200 and len(got) == size:
                            stats["share_downloads"] += 1
                        else:
                            stats["errors"].append(f"share dl {status}")
            except Exception as e:
                stats["errors"].append(f"share {type(e).__name__}: {e}")

            os.urandom(1)  # keep rng moving
    except Exception as e:
        stats["errors"].append(f"user {type(e).__name__}: {e}")
    return stats


def main():
    print(f"target={BASE} concurrency={CONCURRENCY} files_per_user={FILES_PER_USER}")
    start = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as ex:
        results = list(ex.map(one_user, range(CONCURRENCY)))
    elapsed = time.time() - start
    total_up = sum(r["uploads"] for r in results)
    total_bytes = sum(r["upload_bytes"] for r in results)
    total_dl = sum(r["downloads"] for r in results)
    total_shares = sum(r["shares"] for r in results)
    total_sdl = sum(r["share_downloads"] for r in results)
    errors = [e for r in results for e in r["errors"]]
    print(f"elapsed: {elapsed:.1f}s")
    print(f"uploads: {total_up} ({total_bytes / 1048576:.1f} MiB, {total_bytes / 1048576 / elapsed:.2f} MiB/s)")
    print(f"downloads: {total_dl}, shares: {total_shares}, anonymous share downloads: {total_sdl}")
    if errors:
        print(f"ERRORS ({len(errors)}):")
        for e in errors[:20]:
            print(f"  - {e}")
        sys.exit(1)
    print("ALL OK")


if __name__ == "__main__":
    main()
