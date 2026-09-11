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
   (armeabi-v7a / x86_64 variants sit next to it).
2. Open the APK on the phone and allow installation from an unknown source.
3. Enter `http://<server address>:8080` plus the account on the login screen.

All clients share one feature set: Home (server status + album + recent),
Files (upload/download/rename/move/delete), Album (monthly timeline; photos go
into `Album/<device name>`), Shares, Settings.

## 3. Web client

Nothing to install. Once the server is up, open
`http://<server address>:8080`; it is the same UI as Android.

## 4. How the update check works

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

## 5. How to release a new version

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

## 6. Desktop and iOS status (stated plainly)

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
