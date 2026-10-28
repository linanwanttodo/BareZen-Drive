# Installation and usage

[English](usage.en.md) | [简体中文](usage.md)

This guide covers how to get, install and use each BareZen-Drive component. For
what the project is, see [README.md](../README.md); for the HTTP API, see
[api.md](api.md).

## 1. One-command server deployment (recommended)

The server is the only mandatory piece: it provides the API, file storage, and
serves the Web client itself. Deploy with one command:

```bash
curl -fsSL https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/install.sh | bash
```

The script:

1. Checks Docker and Docker Compose.
2. Generates `.env` in `/opt/barezen` - asks for the port; the database password
   and JWT secret are auto-generated when left blank (or skip the prompts with
   `--dir`, `--port`, `--version`).
3. Pulls the `ghcr.io/linanwanttodo/barezen-drive` image (multi-arch: both
   amd64 and arm64).
4. Starts the PostgreSQL 16 and server containers, waits for the health check,
   and prints the access address.

After installation:

- Open `http://<server address>:8080` in a browser for the Web client. An install that chose
  "domain + HTTPS" opens at `https://<domain>` instead: that setup binds `SERVER_PORT` to
  `127.0.0.1` only, so the cleartext port stays shut and WebDAV never runs without TLS.
- **First thing to do: register your account, then close "Open registration"
  in Settings -> Server.** A private drive should not let everyone sign up;
  once closed, the register tab disappears from the login screen and the API
  rejects new sign-ups (error code `REGISTRATION_DISABLED`). Reopen it any time.

Common commands (run inside the install directory `/opt/barezen`):

```bash
docker compose pull && docker compose up -d   # upgrade to the latest image
docker compose logs -f server                 # logs
docker compose down                           # stop (data is kept)
```

### What goes into .env

| Variable | Required | Meaning |
|---|---|---|
| `JWT_SECRET` | yes | JWT signing secret, at least 32 bytes. `openssl rand -base64 48`; the script can generate it |
| `POSTGRES_PASSWORD` | yes | PostgreSQL password; the script can generate it |
| `POSTGRES_DB` / `POSTGRES_USER` | no | Defaults `barezen` / `barezen` |
| `SERVER_PORT` | no | Public port, default 8080; an HTTPS install binds it to 127.0.0.1 and Caddy serves 443 |
| `STORAGE_DIR` | no | In-container file directory; fixed to `/data/storage` in compose, bind-mounted to `./data/storage` |
| `MAX_FILE_SIZE` | no | Per-file limit, default 10 GiB |
| `UPDATE_MANIFEST_URL` | no | Update manifest URL; defaults to `update.json` on the repository's latest release |
| `GITHUB_TOKEN` | no | Optional, raises the GitHub rate limit |
| `STORAGE_BACKEND` | no | `local` (default, files under `STORAGE_DIR`) or `s3` (object storage) |
| `S3_BUCKET` / `S3_REGION` | required for s3 / no | Bucket name and region; region defaults to `us-east-1` |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | yes for s3 | Bucket credentials; keep them in `.env`, never commit them into compose |
| `S3_ENDPOINT` | no | Self-hosted gateway URL such as MinIO; blank uses the AWS region endpoint |
| `S3_PATH_STYLE` | no | `true` addresses the bucket in the URL path (MinIO needs this), default `false` |

`STORAGE_DIR` stays required with `STORAGE_BACKEND=s3`: chunk staging and merging happen on the local
disk, only finished blobs go to the bucket, so `./data/storage` holds nothing but `tmp/` and backups
move to the bucket (enable bucket versioning and cross-region replication if you need them). Nothing
is migrated for you: copy the existing `blobs/` and `thumbs/` prefixes into the bucket first, keys stay
identical, or already uploaded files stop resolving the moment you restart on s3.

### Backup and restore

A usable backup takes both halves, neither one is enough alone: file content lives in `./data/storage`
(`blobs/` finished objects, `thumbs/` previews, `tmp/` chunk staging), metadata lives in PostgreSQL
(the folder tree, versions, favorites, trash, shares, accounts). Back up only the database and every
file is gone; back up only the directory and the UI shows nothing.

Dump the database with `pg_dump` (already inside the container, no host install needed):

```bash
cd <install-dir>
docker compose exec -T db pg_dump -U barezen barezen | gzip > barezen-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

The role and database name come from `POSTGRES_USER` / `POSTGRES_DB` in `.env`, both `barezen` by
default. Before an upgrade `install.sh` runs this same dump for you into `<install-dir>/backups/`.

If the `server` does not come up after an upgrade (schema DDL throws, and `restart: unless-stopped`
keeps restarting it), work in this order: read the failing statement with
`docker compose logs --tail 100 server`, then move the image tag back to the version you ran before.
Schema evolution only adds columns, never rewrites them, so an older build reads the newer database
and the rollback touches no data:

```bash
bash install.sh --dir <install-dir> --version <older-version>   # answers left blank keep what .env holds
```

To go back to the data state itself, restore the dump from `<install-dir>/backups/` with the steps
below.

Archive the storage directory with `tar`; the server does not need to stop (an upload in progress sits
in `tmp/` and can be discarded on restore, finished files are content-addressed):

```bash
tar -czf barezen-storage-$(date -u +%Y%m%d-%H%M%S).tar.gz -C <install-dir> data/storage
```

To restore onto an empty machine, put `docker-compose.yml`, `.env` and `data/storage` in place first.
The database has to be loaded **before** the server starts, because startup recreates the admin account
on an empty schema:

```bash
docker compose up -d --wait db   # database only, block until it is ready
gunzip -c barezen-xxx.sql.gz | docker compose exec -T db psql -U barezen -d barezen
docker compose up -d             # then the server
```

Do not skip `--wait`: on an empty volume postgres runs initdb, starts a throwaway instance to finish
initialization, then restarts - loading a dump into that window fails with "the database system is
shutting down".

`docker compose up -d db` creates the target database from `POSTGRES_DB`. Restore into an empty volume:
keep the old `pgdata` around or drop it after `docker compose down`, otherwise the dump's `CREATE`
statements collide with the objects already there. Store backups off-box - a backup on the same disk as
the data is not a backup. Nightly at 01:00:

```
0 1 * * * cd <install-dir> && docker compose exec -T db pg_dump -U barezen barezen | gzip > /backup/barezen.sql.gz
```

With `STORAGE_BACKEND=s3`, `./data/storage` holds only `tmp/` and that responsibility moves to the
bucket (enable versioning); the database dump above stays exactly the same.

### Automatic upgrades (optional, on by default)

`install.sh` installs a host-side systemd timer that runs once a day at 04:15 with up to 45 minutes of
random delay (so a fleet of instances does not pull images at the same instant):

```bash
systemctl status barezen-update.timer            # installed? next run?
journalctl -u barezen-update.service -n 50       # what the last run did
sudo systemctl disable --now barezen-update.timer   # stop it
```

It follows **stable releases only**: it asks the local `/api/version`, which by default reads the
`update.json` asset on `releases/latest` (`UPDATE_MANIFEST_URL` overrides it; GitHub's `releases/latest`
API is only the fallback when that manifest cannot be read; the answer is cached for 10 minutes). The
manifest's `version` is not itself filtered for pre-releases - what keeps betas out is the next rule: the
version must be a plain `x.y.z`, and anything with a `-` or `+` suffix is skipped. When
an upgrade applies, the order is fixed: dump the database to
`<install-dir>/backups/auto-<version>-<time>.sql.gz` → retag the compose file → `docker compose pull
server` → `up -d server` → probe `/readyz`. An HTTPS install's compose command carries
`-f docker-compose.caddy.yml` (which keeps the plaintext port on loopback); that whole command is
recorded in `/etc/barezen-update.env` and replayed verbatim, so an upgrade does not put 8080 back on
every interface.

Three outcomes it handles by itself: a failed dump changes **nothing** and exits; a failed `pull` puts the
tag back; a `/readyz` that keeps answering 503 retags to the previous version, pulls and probes once more,
and logs the result (exit codes: 0 nothing to do or upgraded, 1 rolled back, 2 rollback also failed - needs
a human, 3 aborted before changing anything). A rollback swaps image tags only and touches no data,
because the schema only ever gains columns; the older image reads the newer database fine. **The day a
release changes a column type or adds a constraint, automatic upgrades must be turned off for that
release** - at that point a rollback is no longer free.

**It is an upgrader, not a watchdog.** If the instance is already down (crash, broken image) it cannot even
reach `/api/version`, so it logs `skipped (exit 0)` and tries again tomorrow - it will not bring the
service back. Keep-alive is compose's `restart: unless-stopped` plus monitoring of your own.

Re-running `install.sh` and answering "n" to that question removes the unit files (together with the
script and its env file); `AUTO_UPDATE` in `.env` is the remembered answer, so a non-interactive re-run
keeps whatever is there instead of changing it behind your back. Removal is conditional on
`/etc/barezen-update.env` still existing and still naming this install directory: that file is the
ownership evidence, and with it hand-deleted the wizard leaves the units alone rather than tearing down
another instance's timer.

On Android there is a second path: once signed in, the app checks once a day, downloads the APK of a new
stable release over whatever network is up and verifies it against the manifest's sha256 and size (both
are skipped only when the manifest omits them), then posts a notification; only tapping that
notification opens the system installer. Android owns the final confirmation and no app can skip it. When
the notification cannot be delivered (notification permission refused on Android 13+, or notifications for
the app/channel switched off in system settings) it does **not download at all**: a package nobody is told
about would be remembered as downloaded and the next day would skip that version, losing the upgrade hint
for good. Turn notifications back on and the next day's pass works normally.

### Without Docker

Download `BareZen-Drive-<version>-server.tar.gz` from Releases (the Web client
is embedded; architecture-independent; needs JDK 21), unpack it, set the
environment variables from the table above, and run `./bin/server`. Provide
your own PostgreSQL 16.

## 2. Install the Android client

1. From [Releases](https://github.com/linanwanttodo/BareZen-Drive/releases)
   download `BareZen-Drive-<version>-android-arm64-v8a.apk` for modern phones
  .
2. Open the APK on the phone and allow installation from an unknown source.
3. Enter `http://<server address>:8080` plus the account on the login screen.

All clients share one feature set: Home (server status + album + recent),
Files (upload/download/rename/move/delete), Album (monthly timeline; photos go
into `Album/<device name>`), Shares, Settings.

## 3. Web client

Nothing to install. Once the server is up, open
`http://<server address>:8080`; it is the same UI as Android.

## 4. Media library: auto backup, favorites, archive, versions and trash

**Automatic album backup (Android)**: turn on "Auto backup" at the top of the
transfer centre and the app uploads new or edited photos and videos into
`Album / <device> / <category>` on the server. A photo taken joins the queue
within seconds (the app watches the system gallery); the six-hour periodic pass
and the daily full sweep are only a backstop. "Wi-Fi only" keeps it off the
mobile plan, and "Only while charging" restricts backup to when the device is
plugged in - the platform refuses to run on low battery anyway. While it waits,
the status card names the actual reason (waiting for network / plug in / battery
too low / sign in first) instead of leaving a bare "waiting to back up: 300" on
screen. "Sync now" runs a pass immediately
and deliberately ignores those two rules, as a one-off override; "Backup albums"
lets you switch individual albums off (screenshots, app cache albums) - excluded
albums are not counted as pending, and switching one back on re-drives the queue
at once. Exclusions take effect immediately, even mid-pass: every queued item is
re-checked against the exclusion set right before it uploads, so an album you
just switched off is not carried to the end by a queue that was snapshotted
earlier. It runs as a system background job with a foreground progress
notification, so it needs the photo/video read permission and notification
permission. The queue survives a killed process and continues on the next run; a
single file retries up to 5 times (linear backoff, 10 minutes apart), and a
dropped connection stops the pass early instead of burning the retry budget. An
unmetered link uploads two files at a time; a metered one drops to one.

Switching the toggle on raises a "Review which albums to back up" prompt first:
it lists only the busiest few albums (so the multi-gigabyte WeChat or Telegram
cache albums do not leave the phone unnoticed), unchecking one excludes it
immediately, and "Back up all" restores every album on the list. Each device is
asked once; the complete list stays available under "Backup albums", whose top
bar has a "Back up all" action that restores every album in one tap. If the
media read permission was not granted, nothing can be listed and the prompt does
not count as answered - it comes back the next time the sync tab is opened.

**Favorites**: tap the heart in the album screen to show favorites only. To
favorite a photo use the "Favorite" action in the album multi-select bar (long
press), the file row menu, or the heart in the image viewer.

**Archive**: archiving is a flag, not a move. Archived files disappear from the
timeline, search and the default file listing, while existing links keep
working. Unarchive under Settings -> Library -> Archive. The entry points are
the same as for favorites.

**Trash**: deleting a single file is a soft delete; it moves to the trash for
30 days, where you can restore it, delete it forever, or empty the trash to
reclaim space right away. Rows older than the retention window are purged by
the server cleanup loop. Deleting a whole folder tree is still a permanent
physical delete. Trash lives under Settings -> Library -> Trash. Share links of
a trashed file are revoked at once; restoring the file does not republish them,
so sharing stays an explicit action.

**File versions**: uploading over a name that already exists in that folder asks
you first - "Overwrite" or "Skip". Overwriting loses nothing: the previous
content becomes a stored revision while the file keeps its name, share links,
favorite and archive flags. Open the file menu -> "Version history" to restore
or drop any revision; restoring is a swap, so the content you replaced goes
into history too. Each file keeps at most 20 revisions, older ones are trimmed
and their space reclaimed. Automatic backup never overwrites: a photo with a
clashing name but different bytes is reported as a failed transfer entry for you
to review instead of silently replacing anything.

## 5. How the update check works

"Settings -> About -> Check for updates" on every client asks **the server you
are connected to** instead of calling GitHub directly:

1. The server reads the release manifest `update.json` (a stable file attached
   to the release, no rate limit) and caches it for 10 minutes.
2. The client compares the server version with its own and, when newer, offers
   the per-platform action:
   - **Web**: "Reload" - the new bundle is served by the same server; a server
     upgrade reaches already-open pages through this prompt.
   - **Android**: "Download update" with a direct link to the APK from the
     manifest.
   - Desktop/iOS (reserved): will list the platform package the same way.
3. Every asset in the manifest carries sha256 and size; the Release page also
   provides `checksums.txt`.

This manual check always works. Server and Android automatic upgrades are
described under "Automatic upgrades" in section 1; when phone notifications are
switched off, the automatic path does nothing and this manual check is the only
way in.

The `update.json` shape (desktop and iOS entries are appended when enabled; no
schema change needed):

```json
{
  "name": "BareZen-Drive",
  "version": "0.0.2",
  "apiVersion": 1,
  "releaseNotesUrl": "https://github.com/linanwanttodo/BareZen-Drive/releases/tag/v0.0.2",
  "assets": [
    {"platform": "android", "arch": "any", "kind": "apk", "url": "...", "sha256": "...", "size": 0},
    {"platform": "web", "arch": "any", "kind": "zip", "url": "...", "sha256": "...", "size": 0},
    {"platform": "server", "arch": "any", "kind": "tar.gz", "url": "...", "sha256": "...", "size": 0}
  ],
  "docker": {"image": "ghcr.io/linanwanttodo/barezen-drive", "tags": ["0.0.2", "latest"], "platforms": ["linux/amd64", "linux/arm64"]}
}
```

## 6. How to release a new version

The version is defined in exactly one place: `version=` in
`gradle.properties` (Android `versionName`/`versionCode`, the server, and the
update manifest all derive from it). Releasing takes three steps:

```bash
# 1. edit gradle.properties: version=0.0.2
# 2. commit and tag
git tag v0.0.2 && git push origin master v0.0.2
# 3. wait for the GitHub Actions Pipeline: build, push image, publish release
```

The Pipeline automatically runs the tests, builds the web bundle / APK / server
distribution, pushes the amd64+arm64 image, generates `update.json` and
`checksums.txt`, and publishes the GitHub Release. If a single job fails,
"Re-run failed jobs" rebuilds only that part and reuses everything else.

## 7. Desktop and iOS status (stated plainly)

Both have platform interfaces and CI jobs reserved, but **no installable
packages are produced yet**:

- **Desktop**: the `:app:desktopApp` module exists; the target is not enabled
  in `settings.gradle.kts` and needs the full set of `jvm` platform
  implementations (only `AppUpdate.jvm.kt` exists today). The JVM target
  compiles and runs on Linux locally, so this is the easiest next step; once
  done, CI produces `.deb`/`.rpm` automatically.
- **iOS**: the Xcode project and `iosMain` entry exist; what remains is the iOS
  `actual` implementations, enabling the Apple targets, and a Darwin ktor
  engine. No dependency blocker (`backdrop` and `shapes` publish iOS
  artifacts), but Apple targets cannot be compiled on Linux - only the GitHub
  macOS runner can verify this end.

Deployable today: **server, Web, and Android**. What you would use a desktop
client for is available now over the network drive in the next section.

## 8. Mounting as a network drive (WebDAV)

Usable before any desktop client exists. Mount `/dav` as a network drive and
Finder, Explorer or Nautilus can read and write it like a local directory, which
means `:app:desktopApp` no longer gates desktop use.

**To set one up**: Web or Android client -> Settings -> WebDAV -> create a mount,
give it a name you will recognise later (e.g. "work iMac"), and you get a 64-character
app password.

**Why an app password and not the account password**: a mount authenticates on
*every single request*, and the account password is a bcrypt hash (cost 10,
~100ms), so opening one folder costs hundreds of those. The app password is 32
bytes of randomness stored as a SHA-256 hash and looked up by exact match (no
bcrypt) - high entropy has no offline guessing surface, which is what makes exact
matching sound here. It also cannot sign in to the Web UI or call any `/api/**`
endpoint, so a leak stays confined to the drive itself.

**One per device.** WebDAV clients keep the password in a plaintext config file or
the system keychain, so the only way to contain a leak is to cut one device off
without disturbing the rest. Revoke an individual mount from settings, or mark it
read-only so every write method answers 403 - that keeps a stray keystroke on the
mount from deleting the library.

**To mount** (the username is your account name, the password is the app password
you just generated):

- **macOS Finder**: Go -> Connect to Server -> `https://<your-domain>/dav`
- **Windows Explorer**: This PC -> Map network drive -> `https://<your-domain>/dav`
- **Linux**: `sudo mount -t davfs https://<your-domain>/dav /mnt/point`

**HTTPS is required.** HTTP Basic sends the credential in the clear, and Windows
clients disable Basic over plain HTTP by default. The Caddy mode of `install.sh`
already terminates TLS.

### Four limits, stated plainly

1. **No locking.** `LOCK`/`UNLOCK` answer 501 and are deliberately absent from
   `Allow` - advertising them makes clients expect lock semantics, and Explorer
   then declines to open a file it cannot lock while Office refuses to save.
   **The practical consequence: saving an Office document you opened from the
   mount may fail**, because Office locks before saving.
2. **An overwrite produces no version history.** Mount overwrites use WebDAV
   semantics and skip the version table. The replaced blob goes to the trash
   queue's grace period, so nothing is orphaned, but there is no history to consult
   - use the Web UI when you need that.
3. **No instant upload.** The protocol has no hook for a client to announce a hash
   first, so every WebDAV upload is a full one. Large files are slower here than
   through the Web client.
4. **Deleting a collection is not recoverable.** Deleting a *file* goes to the
   trash and can be restored; deleting a *folder* hard-deletes the whole
   subtree, because the data model has no soft-delete column for folders
   (see api.md). Move rather than delete when you want a way back - WebDAV
   clients usually show no confirmation dialog.
5. **Not verified against real clients.** This is verified only to "the protocol
   tests pass" and "a real-HTTP smoke script passes" (`scripts/dav_smoke.sh`). No
   usable Windows or macOS client was available while building it, so the claim
   "Explorer and Finder actually mount it" is untested. Try it in a scratch folder
   first.

For the method table, property set, status codes and the two deliberate deviations
from RFC 4918, see [the WebDAV chapter of api.md](api.md).

