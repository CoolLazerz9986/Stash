# Stash for Android (v1)

Native Android app for the Stash server in the folder above. Kotlin, Android 10+ (minSdk 29), no UI libraries.
Only dependencies: `androidx.core`, `androidx.activity`, `androidx.work` (WorkManager).

## What it does

1. **First launch:** asks for the server address (e.g. `http://192.168.1.50:8080`) and checks it really is a Stash server (`GET /api/session`).
2. **Website in a WebView:** the page is served by your own server (`/` and `/chats`). The app adds what a browser tab can't:
   file chooser for uploads (via `ACTION_OPEN_DOCUMENT` starting at main storage so it does not reopen inside the WhatsApp folder), downloads into `Downloads/Stash` (single files *and* zips), a login that survives restarts, an 8-second pre-flight reachability check with reconnect on resume, native layered WebViews for `/chats` and narrow-screen solo chats with horizontal slide transitions and left-edge swipe back (plus Android 14+ predictive Back), stepwise Back navigation on `/` (open `<dialog>` → `window.stashBack()` → leave app), and a "Phone backup & app settings" button in the website header (it only exists inside the app).
3. **Automatic photo/video backup** (Phone backup screen): hourly WorkManager job, Wi-Fi only by default, optionally only while charging.
   Photos and/or videos, all folders or a chosen subset, with or without what is already on the phone. Generates and uploads small JPEG grid previews (`Thumbs`). A "Back up now" button. Watches network state to show `"Waiting for Wi-Fi (Wi-Fi only is on)"` when held back, shows status, last run and how many files are done.
4. **Automatic WhatsApp chat & media backup (v1)** (Phone backup screen): every 6 hours (plus "Back up chats now", "Back up older media…", and "Stop").
   Reads WhatsApp's `msgstore*.crypt15` from the folder you choose with Android's system folder picker, decrypts it on the phone in 64 KB chunks using your 64-digit end-to-end backup key (the key never leaves the phone), uploads the plain database to `POST /api/chats/import` if changed, and backs up SHA-256-matched chat media from the WhatsApp `Media` folder (last day by default, or 7–365 days via "Back up older media…") tagged `whatsapp` along with JPEG thumbnails. Includes an "Open chats" button that opens `/chats` in the app.

How both backup runs work is in [`../API.md`](../API.md) ("Backup client algorithm"). In short: hash → ask the server → upload with `X-Sha256` only if new or missing → upload a 320 px JPEG preview.
Both are one-way: nothing is modified or deleted on the phone or on the server.

## Build

You need Android Studio (Ladybug or newer) **or** a JDK 17 and the Android SDK (platform 35).

```bash
cd android
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk   (signed with the debug key: fine for your own phone)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the `android/` folder in Android Studio and press Run. The first sync downloads Gradle 8.9 and the libraries.
Enable "Install unknown apps" on the phone if you copy the APK over by hand.

## First run checklist

1. Run the **updated** `server.py` (with `chats.py`, `index.html`, and `chats.html` next to it; older servers lack API v2 or the `/api/chats/*` routes, and the app will tell you).
2. Open the app, type the server address, **log in to the website** inside the app.
3. **Photo/video backup:** tap the sliders icon in the header (or the 3-dot menu on a phone) → switch on **Back up this phone to Stash**, allow photo access (allow "all", not "selected photos").
   Also allow notifications and location-in-photos if asked: the first shows progress, the second keeps GPS data in the copied photos.
   On Android 13 and newer, location-in-photos is granted together with "Photos and videos", so you may see no separate prompt or toggle for it.
4. The first run uploads everything that is selected, so leave the phone on Wi-Fi and charging. Progress is in the notification and on the settings screen.
5. **WhatsApp chat backup (optional):** in WhatsApp go to *Settings > Chats > Chat backup > End-to-end encrypted backup*, turn it on with a 64-digit key, and run a local backup. In Stash's Phone backup screen, paste and save the 64-digit key, pick the WhatsApp folder (`Android/media/com.whatsapp/WhatsApp`, or `WhatsApp` on older phones), and switch on **Back up WhatsApp chats**.

## Files (app/src/main/java/app/stash/android)

| File | Job |
|---|---|
| `MainActivity` | WebView host (`/` plus native layered WebViews for `/chats` and solo chats), left-edge `Stack` swipe + predictive Back, `ACTION_OPEN_DOCUMENT` file chooser (storage root), download hook, `window.StashApp` bridge (`downloadZip`, `openSettings`, `openPage`, `closePage`), `EXTRA_PATH` routing, pre-flight `StashApi.session` check and error screen |
| `SetupActivity` | server address screen |
| `BackupActivity` | photo/video & WhatsApp chat backup settings (`SwipeBackLayout`), "Back up older media…", "Stop", network watcher, and status |
| `SwipeBack` | `SwipeBackLayout`: left-edge swipe to drag and close `BackupActivity` |
| `BackupWorker` | one photo/video backup pass (the core logic) |
| `WhatsAppBackup` | `WaSource` & `MediaIndex` (SAF lookup for `msgstore*.crypt15` and `Media` files) and `ChatBackupWorker` (database + SHA-256-matched chat media backup pass) |
| `Crypt15` | pure-JVM streaming AES-CTR + zlib decryptor for WhatsApp `crypt15` backups |
| `Thumbs` | creates ~320 px JPEG previews (<= 120 KB) for photos and videos |
| `BackupScheduler` | turns settings into WorkManager jobs (`apply`, `runNow`, `applyChat`, `runChatNow`, `stopChat`, `waitingForWifi` / `noteWifiWait`) |
| `BackupDb` | SQLite record of finished photo/video and chat media files (`backup.db`, schema v2) |
| `MediaSource` | lists photos/videos through MediaStore; permission helpers |
| `StashApi` | blocking HTTP client for the server (see `API.md`) |
| `Downloader` | saves downloads/zips into `Downloads/Stash` |
| `Prefs`, `Ui` | settings storage, tiny UI helpers |

The website changes for the app are the hidden settings button (`index.html`), the zip-download hook (`index.html`), native page open/close hooks (`index.html`, `chats.html`), and `window.stashBack()` (`index.html`, `chats.html`), active only inside the app.

## Known limits (v1)

- **No resumable uploads.** If a big video or chat database is interrupted (app killed, Wi-Fi lost), that file restarts from zero next run. Finished files are never repeated.
- **Files over the server's limit** (`STASH_MAX_MB`, 10 MB by default) are not uploaded. The backup status counts them as "too big" and they are not recorded as done, so they are picked up again after the limit is raised (for chat media, re-check runs when `msgstore.db` changes or you tap "Back up chats now").
- **Flat vault.** The server has no folders yet, so backed-up photos/videos and chat media land in one list (chat media is tagged `whatsapp` so you can filter it). Two different files with the same name get `name (1).ext`; identical ones are skipped by hash. A WhatsApp file already backed up earlier as a regular photo is reused in chat view, without carrying the `whatsapp` tag.
- **WhatsApp chats (v1):**
  - The WhatsApp chats card on `BackupActivity` is still in a plain un-restyled layout.
  - Contact names live in WhatsApp's `wa.db`, which is not backed up; chats show group subjects or `+<number>` (`+<digits>`).
  - Scheduled chat backups only scan media from messages sent in the last 1 day by default (by message date, so media downloaded today from an older message counts as old); use `"Back up older media…"` for 7–365 days (check server disk space first). Media files whose SHA-256 differs from WhatsApp's recorded `file_hash` are skipped as `"mismatch"`.
  - `Crypt15` streams with AES-CTR without verifying the trailing 16-byte GCM tag; corruption or a wrong key is caught by the zlib stream and the `SQLite format 3` header check.
  - Switching to a different server in `SetupActivity` currently clears photo backup settings, but leaves `chatEnabled` and the chat schedule active (tracked in `TASKS.md`).
- **One shared key** for all devices (the server has no per-user accounts).
- Cleartext `http://` is allowed everywhere (LAN servers have no certificate). Set `cleartextTrafficPermitted="false"` in `res/xml/network_security_config.xml` if you put it behind HTTPS.
- A hash is computed for every new file on the phone before upload (needed for de-duplication). Big videos take a while on a slow phone.
- **Upload picker:** using `ACTION_OPEN_DOCUMENT` starting at main storage keeps uploads out of the WhatsApp folder, but no longer remembers your last folder and may omit gallery apps that only appear for `GET_CONTENT`.
- **"Back up now" / "Back up chats now" / "Back up older media…"** uses `ExistingWorkPolicy.KEEP`, and there is no "Use mobile data this once" option when `"Wi-Fi only"` is on.
- Android may delay background jobs on phones with aggressive battery savers; exempt the app from battery optimisation if scheduled runs don't happen.
- Downloads show a toast, not a progress bar.
- If location-in-photos access is denied (possible below Android 13), the photos are copied without their GPS data and the backup screen does not say so.
- **Tested on one real phone so far** (Infinix X6882, Android 14): initial photo/video backup, `ACCESS_MEDIA_LOCATION`, and WhatsApp `crypt15` decryption + viewing ran over a Wi-Fi router. Later `test2-dev` additions (thumbnails, chat media pass, native layered WebViews, `SwipeBackLayout`, `index.html` phone layout, Wi-Fi-waiting/mobile-data behaviour, `ACTION_OPEN_DOCUMENT` uploads, light/dark styling on updated screens), other phones, and other Android versions have not had a formal test report.
