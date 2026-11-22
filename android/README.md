# Stash for Android (v1)

Native Android app for the Stash server in the folder above. Kotlin, Android 10+ (minSdk 29), no UI libraries.
Only dependencies: `androidx.core`, `androidx.activity`, `androidx.work` (WorkManager).

## What it does

1. **First launch:** asks for the server address (e.g. `http://192.168.1.50:8080`) and checks it really is a Stash server (`GET /api/session`).
2. **Website in a WebView:** the page is served by your own server. The app adds what a browser tab can't:
   file chooser for uploads, downloads into `Downloads/Stash` (single files *and* zips), a login that survives restarts, the Back button closes the viewer first, and a "Phone backup & app settings" button in the website header (it only exists inside the app).
3. **Automatic backup** (Phone backup screen): hourly WorkManager job, Wi-Fi only by default, optionally only while charging.
   Photos and/or videos, all folders or a chosen subset, with or without what is already on the phone. A "Back up now" button. Shows status, last run and how many files are done.

How a backup run works is in [`../API.md`](../API.md) ("Backup client algorithm"). In short: hash → ask the server by hash → upload with `X-Sha256` only if missing.
It is one-way: nothing is deleted from the phone or the server.

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

1. Run the **updated** `server.py` (the old one has no API key or hash lookup; the app will tell you).
2. Open the app, type the server address, **log in to the website** inside the app.
3. Tap the sliders icon in the header → switch on **Back up this phone to Stash**, allow photo access (allow "all", not "selected photos").
   Also allow notifications and location-in-photos if asked: the first shows progress, the second keeps GPS data in the copied photos.
   On Android 13 and newer, location-in-photos is granted together with "Photos and videos", so you may see no separate prompt or toggle for it.
4. The first run uploads everything that is selected, so leave the phone on Wi-Fi and charging. Progress is in the notification and on the settings screen.

## Files (app/src/main/java/app/stash/android)

| File | Job |
|---|---|
| `MainActivity` | WebView host, file chooser, download hook, `window.StashApp` bridge (`downloadZip`, `openSettings`), error screen |
| `SetupActivity` | server address screen |
| `BackupActivity` | backup settings and status |
| `BackupWorker` | one backup pass (the core logic) |
| `BackupScheduler` | turns settings into WorkManager jobs |
| `BackupDb` | SQLite record of finished files (`backup.db`) |
| `MediaSource` | lists photos/videos through MediaStore; permission helpers |
| `StashApi` | blocking HTTP client for the server (see `API.md`) |
| `Downloader` | saves downloads/zips into `Downloads/Stash` |
| `Prefs`, `Ui` | settings storage, tiny UI helpers |

The only change to the website itself (`../index.html`) is a hidden header button and a zip-download hook, both active only when `window.StashApp` exists.

## Known limits (v1)

- **No resumable uploads.** If a big video is interrupted (app killed, Wi-Fi lost), that file restarts from zero next run. Finished files are never repeated.
- **Files over the server's limit** (`STASH_MAX_MB`, 10 MB by default) are not uploaded. The backup status counts them as "too big" and they are not recorded as done, so they are picked up again after the limit is raised.
- **Flat vault.** The server has no folders yet, so backed-up files land in one list. Two different photos with the same name get `name (1).jpg`; identical ones are skipped by hash.
- **One shared key** for all devices (the server has no per-user accounts).
- Cleartext `http://` is allowed everywhere (LAN servers have no certificate). Set `cleartextTrafficPermitted="false"` in `res/xml/network_security_config.xml` if you put it behind HTTPS.
- A hash is computed for every new file on the phone before upload (needed for de-duplication). Big videos take a while on a slow phone.
- Android may delay background jobs on phones with aggressive battery savers; exempt the app from battery optimisation if the hourly run doesn't happen.
- Downloads show a toast, not a progress bar.
- If location-in-photos access is denied (possible below Android 13), the photos are copied without their GPS data and the backup screen does not say so.
- Tested on one real phone so far (Infinix X6882, Android 14: backup run and permissions). Other phones and Android versions are untested. The code compiles against the Android 34 SDK; the HTTP client and the server were tested together (see `../TESTING.md`).
