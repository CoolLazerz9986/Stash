# Tasks

## Open problems (Bugs)
Biggest first.
1. [normal] `chats.html` `load()` drops requests while another load is running, which can leave a chat blank.
2. [normal] Switching to a different server in `SetupActivity.save()` leaves WhatsApp chat backup enabled and scheduled against the new server with an empty `apiKey`.
3. [minor] A duplicate `test_chats.py` sits at the repo root alongside `tests/test_chats.py` and fails if run.
4. [minor] `tests/jvm/Crypt15Test.kt` is referenced in `Crypt15.kt`, but the file is missing from the repository.
5. [minor] On wide screens before any chat is chosen, `chats.html` shows an empty title bar and a live "Search in this chat" box (`#qrow`) that does nothing.
6. [minor] In `chats.html`, the date next to each chat name uses a long format without `white-space: nowrap`, so it may wrap on narrow phones.
7. [minor] Group sender labels in `chats.py` prepend `+` to whatever precedes `@` in `sender_jid_row_id`, even if the ID is not a phone number.
8. [minor] In `MainActivity.kt`, Back from a non-files page reloads `/` instead of navigating history, and Back on `/chats` while the error overlay is up leaves the app.
9. [minor] Switching the WebView file chooser to `ACTION_OPEN_DOCUMENT` at main storage fixes reopening in the WhatsApp folder, but drops last-folder memory and may omit gallery apps that only support `GET_CONTENT`.
10. [minor] `MainActivity.kt` `errorText` is a plain `TextView` without `setTextColor(Ui.pal(this).ink)`, so it may not adapt to dark mode against `errorBox`'s `Ui.themeBackground(...)`.
11. [minor] `"Back up now"` / `"Back up chats now"` uses `ExistingWorkPolicy.KEEP`, so tapping it while work is waiting for Wi-Fi does not replace/re-queue it, and there is no `"Use mobile data this once"` option.
12. [minor] Sub-1 MB sizes and limits display as `"0 MB"` in backup status and server 413 messages (`BackupWorker.finish`, `ChatBackupWorker`, and `server.py` `_upload` / `_chats_import`).
13. [minor] A `413` where `server.py` hangs up before answering is counted as `"failed"` instead of `"too big"` in `BackupWorker` (same early socket-close pattern in `ChatBackupWorker`).
14. [minor] If `ACCESS_MEDIA_LOCATION` is denied (possible below Android 13), `BackupActivity` says nothing and backed-up photos silently lose their GPS data.
15. [minor] The WhatsApp chats card in `BackupActivity.kt` is still in its plain, un-restyled first version.
16. [minor] `tests/jvm/run_jvm_test.py` crashes with a raw traceback when the `java` binary is missing.

### Context
1. `chats.html` `load(older)` begins with `if (loading || !cur) return;`. Tapping chat A then chat B before A finishes (or typing in `#q` while a load is running) skips B's request and discards A's response (`cur.id !== id`), leaving `#msgs` empty with no error. Suggested fixes from the reports: track a request counter so the newest request wins (keeping the `loading` guard only for "Load earlier"), or remember the latest pending request and run it when the current one ends. (Not reproduced on a device yet.)
2. `SetupActivity.save()` sets `prefs.backupEnabled = false`, clears `prefs.apiKey` and `BackupDb`, and calls `BackupScheduler.apply(this)`, but does not set `prefs.chatEnabled = false`, clear `prefs.chatDone`, or call `BackupScheduler.applyChat(this)`. `ChatBackupWorker` remains scheduled and fails with `"Not connected: switch chat backup off and on again while logged in"` and an alert notification.
3. Root `test_chats.py` is identical to `tests/test_chats.py` (even its header says `# Path: tests/test_chats.py`). Running it from the root resolves `Path(__file__).resolve().parent.parent / "server.py"` one directory above the repo. Owner to delete the root copy.
4. `Crypt15.kt` header comment mentions `tests/jvm/Crypt15Test.kt`; the owner notes it is missing / unknown where it went.
5. In `chats.html`, `#qrow` is always visible in `#pane`, even before a chat is opened (observed by static inspection).
6. In `chats.html`, `.chat .tm` has no `white-space: nowrap` and `fmtDay` uses weekday + year + month + day (from CSS inspection; not seen on a screen).
7. `chats.py` `messages()` sets `item["sender"] = raw.partition("@")[0]` and `chats.html` renders `'+' + m.sender`. Non-phone JIDs (e.g. `@lid` / other IDs) would get a leading `+` (possible only; not checked against real data).
8. `MainActivity.kt` calls `load("/")` when pressing Back on any non-files page, which discards `/` scroll/search/sort state and re-runs the 8-second `StashApi.session` check. When `hasError` is true on `/chats`, `!hasError` fails and `moveTaskToBack(true)` runs instead of returning to `/`.
9. `MainActivity.kt` `startAtStorageRoot()` sets `ACTION_OPEN_DOCUMENT` and `EXTRA_INITIAL_URI` (`primary:`). Intended fix for the WhatsApp folder stickiness, with the trade-off above; untested on Android 14.
10. `MainActivity.kt` creates `errorText = TextView(this)` directly instead of `Ui.body(this)` while `errorBox` uses `Ui.themeBackground(this@MainActivity)`.
11. `BackupScheduler.kt` `runNow` and `runChatNow` use `ExistingWorkPolicy.KEEP` and `constraints(p, false)` (which still enforces `wifiOnly`).
12. `BackupWorker.finish` (`${limit / 1048576} MB`), `ChatBackupWorker` (`${plainSize / 1048576} MB`, `${server.maxBytes / 1048576} MB`), `server.py` `_upload` (`MAX_BYTES // 1048576`), and `server.py` `_chats_import` (`CHATS_MAX_BYTES // 1048576`) all use integer division.
13. `StashApi.upload` turns an early `413` into `ApiException(413)` when it can read the response, but if the server closes the socket first, the client sees `IOException` and `BackupWorker` counts it as `"failed"` (and `ChatBackupWorker` retries).
14. `Perms.hasMediaLocation` is checked in `BackupWorker.open()`, and `adb` on Infinix X6882 (Android 14) showed `granted=true` (bundled with Photos & videos on Android 13+). A denial can only happen below Android 13 (`minSdk` 29) and is untested; owner to decide whether a UI warning or docs-only note is enough.
15. `BackupActivity.kt` comment notes: `// ---- WhatsApp chats (first version, plain on purpose: the screen will be restyled later) ----`.
16. `run_jvm_test.py` calls `subprocess.run([os.environ.get("STASH_JAVA", "java"), ...])` with no `try`/friendly error if `java` is missing.

## New features
Tasks for everyone: engineers, testers and scribes.
1. Owner: v1 (alpha) of WhatsApp chat backup and viewing is implemented (`chats.py`, `chats.html`, `Crypt15.kt`, `WhatsAppBackup.kt`). Decide what else is needed to graduate the headline feature from alpha (e.g., restyling the WhatsApp card in `BackupActivity.kt`, contact names via `wa.db`, chat media attachments, Telegram support) before changing the main version tag.
2. Owner: delete the duplicate `test_chats.py` at the repo root (keeping `tests/test_chats.py`), and decide whether `tests/jvm/Crypt15Test.kt` needs to be recreated or removed from `Crypt15.kt` comments.
3. Owner: decide whether the denied `ACCESS_MEDIA_LOCATION` case (problem 14) needs a warning on the backup screen or only the note in `android/README.md`.
4. Engineer: fix `chats.html` (problems 1, 5, 6: `load()` race, hide `#qrow` when no chat is open, keep `.chat .tm` on one line / concise date).
5. Engineer: fix Android bugs (problems 2, 10, 11: reset chat backup in `SetupActivity.save()`, set `errorText` colour in `MainActivity.kt`, restyle WhatsApp card in `BackupActivity.kt`, review `ExistingWorkPolicy.KEEP` and add `"Use mobile data this once"`).
6. Engineer: problems 12, 13, 16 together: format sub-1 MB limits/sizes with decimals in both server and Android messages, count an early-hangup `413` as `"too big"`, and print a clear error in `run_jvm_test.py` when `java` is missing.
7. Engineer: resumable uploads (needs a server route that accepts byte ranges, so `API.md` changes first).
8. Engineer: share to Stash from other Android apps (share sheet).
9. Engineer: handle system-bar insets before raising `targetSdk` from 34 to 35 (`Ui.page` sets bar colours with calls Android 15 ignores for apps targeting 35).
10. Engineer: per-user accounts and keys, folders and tags, share links, trash and old versions (planned growth, nothing decided).
11. Engineer: add the Python test scripts to `.github/workflows/build-apk.yml` (today it only builds the APK on `main`/`master`).
12. Engineer, only when the owner says "optimize": `.zip` downloads build a full temporary copy in `data/tmp/` first, and the phone reads every photo/video twice (once to hash, once to upload).
13. Tester: run `tests/test_chats.py`, `tests/test_server.py`, `tests/test_session_counted.py`, and `tests/jvm/ApiTest.kt` on the current `test2-dev` commit and report results; write `tests/jvm/Crypt15Test.kt` if needed.
14. Tester: test the new phone & `chats.html` behaviour on Android 14 (Infinix X6882) and other devices: `ACTION_OPEN_DOCUMENT` upload picker & main-storage start, stepwise Back navigation, `"Waiting for Wi-Fi"` / network watching / mobile-data transitions, `SetupActivity` server switching with chat backup on, and light/dark mode across all screens (`chats.html`, `BackupActivity`, `MainActivity` error overlay).
15. Tester: add server tests for wrong password and login 429, 411/413/507, chat import 409/507/`STASH_CHATS_KEEP=0`, `Range` edge cases (416), `ETag`/304, zip contents and `ids=all`, rename clashes, file-name cleaning, restart behaviour.
16. Tester: test denied `ACCESS_MEDIA_LOCATION` (compare geotagged photo vs backup), `backup.db` v1 → v2 migration, and `STASH_DATA` on a network share (`GUIDELINES.md` §2).
17. Scribe: update `CHANGELOG.md`, `TASKS.md`, `TESTING.md`, `API.md`, and architecture diagrams as fixes and test reports land.

## Handoff (unfinished code)
**State:** unfinished
**Unfinished:** WhatsApp chat backup & viewer is implemented as an alpha v1 (`BackupActivity.kt` chat card is still un-restyled); `"Use mobile data this once"` and the `KEEP` issue on `"Back up now"` are still open; duplicate root `test_chats.py` needs deleting; `tests/jvm/Crypt15Test.kt` is missing from the repo.
**Untested:**
- New from the latest reports and `test2-dev` review: `ACTION_OPEN_DOCUMENT` upload picker & main-storage start on Android 14, stepwise Back navigation on a phone, `chats.html` light/dark layout / `#qrow` / date wrapping, `"Waiting for Wi-Fi"` / network watching / mobile-data behaviour, `SetupActivity` server switch with chat backup enabled, and formal runs of `tests/test_chats.py`, `tests/test_server.py`, `tests/test_session_counted.py`, and `tests/jvm/ApiTest.kt` on `test2-dev`.
- Everything else that is untested is listed in `TESTING.md`.
