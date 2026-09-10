# Installation and usage

[English](usage.en.md) | [简体中文](usage.md)

This guide covers how to get, install and use each BareZen-Drive component. For
what the project is, see [README.md](../README.md); for the HTTP API, see
[api.md](api.md).

## 1. Which form do you need

| Form | Use case | Artifact | How to get it |
|---|---|---|---|
| Server + Web | You have your own server (VPS / NAS / Raspberry Pi) | Docker image or server distribution | Pull from GHCR, or `BareZen-Drive-server.tar.gz` from Releases |
| Android client | Use it on a phone | `BareZen-Drive-android-*.apk` | Download from the Releases page |
| Web client | Use it in a browser, nothing to install | Served by the server | Open `http://<server>:8080` after deploying the server |
| Desktop client | Windows / macOS / Linux desktop | Reserved, see the last section | Not available yet |
| iOS client | iPhone / iPad | Reserved, see the last section | Not available yet |

Android and Web share one Compose Multiplatform UI and the same features. The
server is the only mandatory piece: it serves the Web client and the Android
client connects to its address.

## 2. Deploy the server (required)

The server provides the API, file storage and the Web client. Pick one of the
two paths.

### Option A: Docker Compose (recommended)

Simplest, and the image is already built on the GitHub Container Registry.

1. Prepare a machine with Docker and Docker Compose; 1 vCPU / 1 GB RAM is enough.
2. Fetch the two files (or clone the whole repository):

```bash
mkdir barezen && cd barezen
curl -fsSLO https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/docker-compose.yml
curl -fsSLO https://raw.githubusercontent.com/linanwanttodo/BareZen-Drive/master/.env.example
```

3. Generate the configuration and fill in the secrets:

```bash
cp .env.example .env
openssl rand -base64 48          # generate a JWT secret of at least 32 bytes
```

Put the output into `JWT_SECRET` in `.env` and set a strong
`POSTGRES_PASSWORD`:

```dotenv
JWT_SECRET=<the long random string from the step above>
POSTGRES_DB=barezen
POSTGRES_USER=barezen
POSTGRES_PASSWORD=<your strong password>
SERVER_PORT=8080
```

4. Start it:

```bash
docker compose up -d --build
```

If the machine cannot reach the registry, replace `build: .` in
`docker-compose.yml` with the prebuilt image:

```yaml
  server:
    image: ghcr.io/linanwanttodo/barezen-drive:latest
```

5. Verify:

```bash
curl -s localhost:8080/health          # {"status":"ok"}
curl -s localhost:8080/api/version     # version information
```

6. Open `http://<server address>:8080` in a browser: that is the Web client.
   Switch to the "Register" tab on the login screen to create the first account.

Upgrade the server:

```bash
docker compose pull          # or docker compose build --pull
docker compose up -d
```

After an upgrade, browser tabs that are already open prompt a reload; one click
loads the new version.

### Option B: Server distribution (no Docker)

For machines that have JDK 21 but no Docker. You provide your own PostgreSQL 16
(or compatible).

1. Download `BareZen-Drive-server.tar.gz` from the Releases page and unpack it:

```bash
mkdir -p /opt/barezen && tar -xzf BareZen-Drive-server.tar.gz -C /opt/barezen
```

2. Set the environment and start it (the distribution already embeds the
   compiled Web client):

```bash
export SERVER_PORT=8080
export JDBC_URL="jdbc:postgresql://localhost:5432/barezen"
export DB_USER=barezen
export DB_PASSWORD=<your database password>
export JWT_SECRET="<at least 32 bytes of random>"
export STORAGE_DIR=/opt/barezen/storage
/opt/barezen/server/bin/server
```

3. Optional, run it under systemd. Create `/etc/systemd/system/barezen.service`:

```ini
[Unit]
Description=BareZen-Drive server
After=network.target postgresql.service

[Service]
User=barezen
EnvironmentFile=/opt/barezen/barezen.env
ExecStart=/opt/barezen/server/bin/server
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Put the environment variables in `/opt/barezen/barezen.env` (one `KEY=value`
per line), then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now barezen
```

### Server environment variables

| Variable | Required | Meaning |
|---|---|---|
| `JWT_SECRET` | yes | JWT signing secret, at least 32 bytes |
| `JDBC_URL` | yes | PostgreSQL connection string |
| `DB_USER` / `DB_PASSWORD` | yes | Database credentials |
| `SERVER_PORT` | no | Listen port, default 8080 |
| `STORAGE_DIR` | no | Directory for files and thumbnails, default `./data/storage` |
| `MAX_FILE_SIZE` | no | Per-file limit, default 10 GiB |
| `UPDATE_REPO_URL` | no | Repository polled by the update check, default this project |
| `GITHUB_TOKEN` | no | Raises the GitHub release-check rate limit |

Data lives in two places: metadata in PostgreSQL, file content in `STORAGE_DIR`.
**Back up those two and you have everything.**

## 3. Install and use the Android client

1. From the Releases page download
   `BareZen-Drive-android-release-unsigned.apk` (release) or
   `BareZen-Drive-android-debug.apk` (debug).
2. Open the APK on the phone to install. The system asks to allow installation
   from an unknown source.
   - Note: the release APK is currently unsigned (`-unsigned`), so some systems
     may require signing it first; the debug APK installs directly. A signed
     production release needs a signing key configured by the maintainer.
3. Open the app and enter the server address and account on the login screen:
   - Server address is `http://<server IP>:8080`, for example
     `http://192.168.1.10:8080`.
   - Use the "Register" tab for the first account, then sign in with it.
   - The app allows cleartext HTTP for self-hosting on a LAN; for a public
     deployment add HTTPS at the reverse proxy.
4. Where things are:
   - Home: server status panel, album strip, recent files.
   - Files: browse folders, upload, download, rename, move, delete, create
     folders.
   - Album: a monthly photo timeline with in-app preview; photos uploaded here
     go into the `Album/<device name>` folder.
   - Settings: theme, language, wallpaper, **check for updates**, open-source
     notices, sign out.

## 4. Use the Web client

Nothing to install. Once the server is up, open
`http://<server address>:8080`; sign-in works the same as Android. The Web
client supports upload, download, preview of images/video/text/PDF (video and
PDF open in a new tab) and share management.

## 5. How the update check works

"Settings -> About -> Check for updates" on every client sends one request to
**the server you are connected to**, instead of each client calling GitHub:

- The server polls the newest upstream release and caches it; the client
  compares the server version with its own.
- Server upgrade: `docker compose pull && docker compose up -d`; open browser
  tabs are offered a reload.
- Android: when a newer version is reported, tap "Open release page" and install
  the new APK from Releases.
- A client on a network that cannot reach GitHub still gets a valid answer.

## 6. Artifact overview

| Component | File | Notes |
|---|---|---|
| Android | `BareZen-Drive-android-release-unsigned.apk` | Unsigned release build |
| Android | `BareZen-Drive-android-debug.apk` | Debug build, installs directly |
| Web | `BareZen-Drive-web.zip` | Static output; copy it into the server `resources/web`, or just use the version embedded in the server |
| Server | `BareZen-Drive-server.tar.gz` | Runnable distribution with the Web client embedded (needs JDK 21) |
| Container | `ghcr.io/linanwanttodo/barezen-drive:latest` | Docker image with server + Web |

## 7. Desktop and iOS status (stated plainly)

Both have platform interfaces and CI jobs reserved, but **cannot produce an
installable package yet**:

- **Desktop**: the `:app:desktopApp` module and entry point exist, but the
  target is not enabled in `settings.gradle.kts`. Enabling it needs the full set
  of `jvm` platform implementations (file picker, clipboard, wallpaper, media
  playback, PDF preview, preferences and so on; only `AppUpdate.jvm.kt` is
  pre-seeded today). Once those exist, the CI desktop job starts producing
  packages automatically.
- **iOS**: the Xcode project and `iosMain` entry point exist and the iOS
  `AppUpdate` actual is pending. There is also a hard prerequisite: the shared
  UI uses `io.github.kyant0:backdrop`, which publishes only android/js/jvm
  artifacts and **no Apple artifacts**, so the iOS target cannot compile until
  that library ships iOS klibs (or is replaced with an iOS-capable
  implementation). GitHub's macOS runners ship Xcode, so once both conditions
  are met the CI ios job can build the framework and the simulator app.

In short: what is actually deployable today is **server, Web and Android**;
desktop and iOS are scaffolded but do not produce installers yet.
