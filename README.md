# Stash

A tiny self-hosted data vault. Upload any file from a browser or phone, see it in a clean
web UI, and download it back whenever you want.

- **Backend:** one Python file (`server.py`), standard library only. No `pip install`, no Node.
- **Frontend:** one HTML file (`index.html`), no build step, no CDN. Works on a LAN with no internet.
- **Storage:** files on disk (`data/blobs/`), metadata in SQLite (`data/stash.db`).
- **Android app:** `android/`: the website in a WebView plus automatic photo/video backup.

Built for a small machine (2 GB RAM, 4 × 1.5 GHz cores, 8 GB eMMC): files are streamed, never loaded whole into memory.
Status: **alpha**. Settled decisions are in [`GUIDELINES.md`](GUIDELINES.md); the rules for everyone working on the project are in [`RULES.md`](RULES.md).

## Documentation map

| File | What it is |
|---|---|
| `README.md` (this file) | how to run it and what you get |
| [`API.md`](API.md) | the server contract (every route, header and status code) |
| [`TESTING.md`](TESTING.md) | what is tested, how to run it, what is not |
| [`android/README.md`](android/README.md) | the Android app |
| [`GUIDELINES.md`](GUIDELINES.md) | project verdicts, hardware limits, how work is delivered |
| [`RULES.md`](RULES.md) | rules for every AI and human working on Stash: roles, hard constraints, handoff |
| [`REPORTS.MD`](REPORTS.MD) | report layout for each role (engineer, tester, scribe) |
| [`CHANGELOG.md`](CHANGELOG.md) | what changed, newest update first |
| [`TASKS.md`](TASKS.md) | open problems, planned work, handoff |
| `stash-architecture.html`, `stash-android-architecture.html` | interactive diagrams (open in a browser) |

## Run it

```bash
STASH_PASSWORD='pick-a-password' python3 server.py
```

Then open the address it prints (for example `http://192.168.1.50:8080`) from any device on your network.

Needs Python 3.8 or newer. Nothing else to install.

## Settings (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `STASH_PASSWORD` | *(none)* | Shared password for the whole vault. |
| `STASH_HOST` | `0.0.0.0` with a password, otherwise `127.0.0.1` | Address to listen on. |
| `STASH_PORT` | `8080` | Port. |
| `STASH_DATA` | `./data` | Where files and the database are kept. |
| `STASH_MAX_MB` | `10` | Largest single upload, in MB. Phone files above it are not uploaded; the backup screen counts them as "too big" and tries them again after you raise the limit. |
| `STASH_API_KEY` | *(generated)* | Key for the Android app / scripts (`Authorization: Bearer <key>`), at least 16 characters. If unset and a password is set, one is generated into `<data>/api.key` on first start. Delete that file and restart to rotate it. |

**Safety default:** with no password set, the server only listens on `127.0.0.1`, so it can't be reached from other machines by accident. Set a password before exposing it to a network.

## What you get

- Drag and drop files or whole folders; up to 3 uploads run at once with progress, speed and cancel/retry. Folders are flattened: only file names are kept, and two files with the same name become `name (1).ext`
- Grid and list views, search (press `/`; matches name and type), filter by type, sort by date, name or size. Theme, view and sort are remembered in the browser
- Preview in the browser: images (thumbnails for files under 8 MB, not HEIC/TIFF), video and audio (seekable), PDF, text, code, JSON, and CSV/TSV as a table. Text previews show the first 256 KB; tables show the first 300 rows. Arrow keys move between files in the viewer
- Download one file, or select several and download an uncompressed `.zip`
- Rename, delete, SHA-256 checksum for each file (click to copy), storage and free-disk stats
- Light and dark theme
- Android app with automatic backup of the phone's photos and videos (one-way, de-duplicated by SHA-256)

## Android app

The project in `android/` is a native Android app (Kotlin, no UI libraries) that:

- asks for your server address on first launch (saved; changeable later),
- shows the Stash website in a WebView, with working uploads and downloads (single files and zips go to `Downloads/Stash`),
- backs up photos and videos in the background, once an hour, Wi-Fi only by default.

Its own screens (server address, phone backup) are styled like the website and follow the phone's light or dark setting.

Build it with Android Studio (open the `android/` folder), `./gradlew assembleDebug`, or let GitHub Actions do it: every push to `main`/`master` (or "Run workflow" by hand) builds a debug APK, downloadable as the `stash-debug-apk` artifact. Details, permissions and limitations are in [`android/README.md`](android/README.md).

Backup talks to the server through the API key. You don't have to type it: log in to the website inside the app once, then switch backup on, and the app fetches the key itself (`GET /api/app-key`, which only works for a logged-in session). The key stays on the phone in the app's private storage.

> **Update the server too.** The backup needs the new routes in this `server.py` (`api` version 2). The app checks this and tells you if the server is too old.

## Keep it running (Linux with systemd, e.g. Armbian)

Create `/etc/systemd/system/stash.service`:

```ini
[Unit]
Description=Stash data vault
After=network.target

[Service]
WorkingDirectory=/opt/stash
EnvironmentFile=/etc/stash.env
Environment=STASH_DATA=/srv/stash-data
ExecStart=/usr/bin/python3 /opt/stash/server.py
Restart=on-failure
User=stash

[Install]
WantedBy=multi-user.target
```

Put the password in `/etc/stash.env` (one line, `STASH_PASSWORD=pick-a-password`) and lock it down with `sudo chmod 600 /etc/stash.env`. Unit files are readable by every user on the machine, so don't put the password in the unit itself.

Then `sudo systemctl daemon-reload && sudo systemctl enable --now stash`.

The `stash` user needs write access to `STASH_DATA`.

## Notes

- Uploads are streamed to disk in 256 KB chunks, so large files don't use much memory. Unfinished uploads sit in `data/tmp/` and are deleted on the next start.
- **Disk space:** the server refuses an upload that would leave under 64 MB free. A `.zip` download first builds a full temporary copy in `data/tmp/`, so it needs room for a second copy of the selected files.
- This speaks plain HTTP. To use it over the internet, put it behind a reverse proxy that adds HTTPS (Caddy or nginx); it honours `X-Forwarded-Proto` for secure cookies. Reverse proxies need their upload size limit raised to match `STASH_MAX_MB`.
- Back up the whole `data/` folder to back up everything. It includes `secret.key` and `api.key`; they are in `.gitignore` and must stay out of git.
- Uploaded files are never executed or rendered as web pages: HTML and scripts are shown as plain text, SVG runs sandboxed.
- Login is a single shared password with a rate limit (10 wrong tries per 5 minutes per address). It is not per-user accounts. The rate limit is shared by wrong passwords and wrong API keys, per client address. `GET /api/session` is only a status check: a wrong key there is never delayed and never answered with 429 (it just reports `"authed": false`), but it is still counted toward the 10, so it can't be used to guess keys. Once an address is blocked, `authed` stays false there even for the right key until the 5 minutes end.
- Sessions are stateless cookies lasting 30 days. "Log out" removes the cookie from that browser only; to cut off every logged-in device, change `STASH_PASSWORD` and restart.
- The API key is a second way in, as powerful as the password for everything under `/api`. Treat `api.key` like the password. Behind a reverse proxy, every client may look like the same address to the rate limiter.
- Every route and header is documented in [`API.md`](API.md).
