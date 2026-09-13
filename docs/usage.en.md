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

- Open `http://<server address>:8080` in a browser for the Web client.
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
| `SERVER_PORT` | no | Public port, default 8080 |
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

Backups need exactly two things: the `./data/storage` directory (file content)
and the PostgreSQL volume (metadata).

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

Deployable today: **server, Web, and Android**.
