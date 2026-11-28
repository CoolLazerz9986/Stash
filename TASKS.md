# Tasks

## Open problems (Bugs)
Biggest first.
1. [normal] `server.py` `_guard()` only sets `self.close_connection = True` on `("POST", "PATCH")`, not `"PUT"`, which can leave unread request body bytes on a keep-alive HTTP/1.1 connection after an early `PUT` error.
2. [normal] `MainActivity.kt` shares one `errorBox` / `hasError` across `/` and all layered WebViews, so a failure on `/chats` makes "Try again" reload `/` and discard the open chat layers.
3. [normal] Switching to a different server in `SetupActivity.save()` leaves WhatsApp chat backup enabled and scheduled against the new server with an empty `apiKey`.
4. [minor] A duplicate `test_chats.py` sits at the repo root alongside `tests/test_chats.py` and fails if run.
5. [minor] `tests/jvm/Crypt15Test.kt` is referenced in `Crypt15.kt`, but the file is missing from the repository.
6. [minor] `MainActivity.kt` `moving` (and `dragging`) can get stuck `true` if a layered WebView is removed mid-drag or torn down before `c.post` runs, disabling Back and edge swipes until the next `load()`.
7. [minor] `MainActivity.kt` `errorText` is a plain `TextView` without `setTextColor(Ui.pal(this).ink)` (and on startup with `EXTRA_PATH == "/chats"` when `session()` fails, a hidden `/chats` layer stays beneath `errorBox`; `EXTRA_PATH` is also replayed after process death).
8. [minor] `index.html` resets `firstPaint = true` on logout, but immediately calls `render()`, which sets `firstPaint = false` so the fade-in never plays after the next login.
9. [minor] `chats.html` / `index.html` slide & swipe edge cases: a failed "Load earlier messages" appends its error at the bottom of `#msgs`; the 80 px swipe zone overlaps `#filter`/`#q` in browser mode; a touch within 300 ms of opening a chat can leave `body.peek` stuck; pressing Back within 260 ms of opening a chat hits `paneIn`; and `resetLeave()`'s 2.5 s timer can snap a slow-server page back mid-navigation.
10. [minor] Group sender labels in `chats.py` / `chats.html` prepend `+` to whatever precedes `@` in `sender_jid_row_id`, even if the ID is not a phone number.
11. [minor] Switching the WebView file chooser to `ACTION_OPEN_DOCUMENT` at main storage fixes reopening in the WhatsApp folder, but drops last-folder memory and may omit gallery apps that only support `GET_CONTENT`.
12. [minor] `"Back up now"` / `"Back up chats now"` / `"Back up older media…"` uses `ExistingWorkPolicy.KEEP`, so tapping it while work is waiting for Wi-Fi or running drops the new request (including `mediaDays`), and there is no `"Use mobile data this once"` option.
13. [minor] Sub-1 MB sizes and limits display as `"0 MB"` in backup status and server 413 messages (`BackupWorker.finish`, `ChatBackupWorker`, and `server.py` `_upload` / `_chats_import`).
14. [minor] A `413` where `server.py` hangs up before answering is counted as `"failed"` instead of `"too big"` in `BackupWorker` (same early socket-close pattern in `ChatBackupWorker`, and `tooBig` chat media files are not retried automatically until `msgstore.db` changes or `"Back up chats now"` is tapped).
15. [minor] Leaving `/chats` destroys its WebView (losing open chat/scroll/search and re-fetching `/api/chats`), and if the phone theme differs from the website's `localStorage` `theme`, sliding in a new WebView flashes the phone theme background.
16. [minor] If `ACCESS_MEDIA_LOCATION` is denied (possible below Android 13), `BackupActivity` says nothing and backed-up photos silently lose their GPS data.
17. [minor] The WhatsApp chats card in `BackupActivity.kt` is still in its plain, un-restyled first version.
18. [minor] `tests/jvm/run_jvm_test.py` crashes with a raw traceback when the `java` binary is missing.

### Context
1. In `server.py`, `do_PUT` calls `_guard(self._put)`. When `_need_auth()` (`401`) or `_put()` (`404`/`413`) raises `ApiError` before reading `Content-Length` bytes, `_guard` checks `if self.command in ("POST", "PATCH"):`, so `close_connection` stays `False` and `Connection: close` is not sent on HTTP/1.1. Adding `"PUT"` fixes it.
2. In `MainActivity.kt`, `onReceivedError` and `onPageStarted` share one `hasError` flag and `errorBox` across `web` and `layers`; `"Try again"` calls `load()` which calls `removeAllPages()` and loads `/`.
3. `SetupActivity.save()` sets `prefs.backupEnabled = false`, clears `prefs.apiKey` and `BackupDb`, and calls `BackupScheduler.apply(this)`, but does not set `prefs.chatEnabled = false`, clear `prefs.chatDone`, or call `BackupScheduler.applyChat(this)`. `ChatBackupWorker` remains scheduled and fails with `"Not connected: switch chat backup off and on again while logged in"` and an alert notification.
4. Root `test_chats.py` is identical to `tests/test_chats.py` (even its header says `# Path: tests/test_chats.py`). Running it from the root resolves `Path(__file__).resolve().parent.parent / "server.py"` one directory above the repo. Owner to delete the root copy.
5. `Crypt15.kt` header comment mentions `tests/jvm/Crypt15Test.kt`; the file is missing from the repository.
6. In `MainActivity.kt`, `Stack.onTouchEvent` returns `false` if `c == null` without resetting `moving`/`dragging`, and `openPage()` sets `moving = true` before `c.post { ... }`.
7. `MainActivity.kt` creates `errorText = TextView(this)` directly instead of `Ui.body(this)` while `errorBox` uses `Ui.themeBackground(this@MainActivity)`. Also `onCreate` calls `openPage("/chats", false)` right after `load("/", true)` before the async `session()` result returns.
8. In `index.html`, `#logout` click handler sets `firstPaint = true` and immediately calls `render()`, which consumes `firstPaint` while the login overlay is shown.
9. In `chats.html`: `load(true)` catch block appends `.empty` to `#msgs`; `EDGE = 80` intercepts drags starting on the left of `#filter`/`#q` in browser mode; `openChat()`'s 300 ms timer skips removing `peek` if `drag` is set; `paneIn` runs for 260 ms; and `goHome()`/`goChats()` reset the slide after 2.5 s.
10. `chats.py` `messages()` sets `item["sender"] = raw.partition("@")[0]` and `chats.html` renders `'+' + m.sender`. Non-phone JIDs (e.g. `@lid`) get a leading `+`, unlike `_chat_name()` which checks `user.isdigit()` first.
11. `MainActivity.kt` `startAtStorageRoot()` sets `ACTION_OPEN_DOCUMENT` and `EXTRA_INITIAL_URI` (`primary:`). Intended fix for WhatsApp folder stickiness, with the trade-off above; untested on Android 14.
12. `BackupScheduler.kt` `runNow` and `runChatNow` use `ExistingWorkPolicy.KEEP` and `constraints(p, false)` (which still enforces `wifiOnly`). Because `KEEP` discards new requests while `CHAT_NOW` is queued or running, `"Back up older media…"` (`mediaDays`) is ignored if a chat run is already active or waiting for Wi-Fi.
13. `BackupWorker.finish` (`${limit / 1048576} MB`), `ChatBackupWorker` (`${plainSize / 1048576} MB`, `${server.maxBytes / 1048576} MB`), `server.py` `_upload` (`MAX_BYTES // 1048576`), and `server.py` `_chats_import` (`CHATS_MAX_BYTES // 1048576`) all use integer division.
14. `StashApi.upload` turns an early `413` into `ApiException(413)` when it can read the response, but if the server closes the socket first, the client sees `IOException` and `BackupWorker` counts it as `"failed"` (and `ChatBackupWorker` retries).
15. `MainActivity.kt` `removePage()` destroys the WebView on close, and `newWebView()` paints `Ui.themeBackground(this)` while the HTML page reads `localStorage.getItem('theme')`.
16. `Perms.hasMediaLocation` is checked in `BackupWorker.open()`, and `adb` on Infinix X6882 (Android 14) showed `granted=true` (bundled with Photos & videos on Android 13+). A denial can only happen below Android 13 (`minSdk` 29) and is untested; owner to decide whether a UI warning or docs-only note is enough.
17. `BackupActivity.kt` comment notes: `// ---- WhatsApp chats (first version, plain on purpose: the screen will be restyled later) ----`.
18. `run_jvm_test.py` calls `subprocess.run([os.environ.get("STASH_JAVA", "java"), ...])` with no `try`/friendly error if `java` is missing.

## New features
Tasks for everyone: engineers, testers and scribes.
1. Owner: delete the duplicate `test_chats.py` at the repo root (keeping `tests/test_chats.py`), and decide whether `tests/jvm/Crypt15Test.kt` needs to be recreated or removed from `Crypt15.kt` comments.
2. Owner: decide whether the denied `ACCESS_MEDIA_LOCATION` case (problem 16) needs a warning on the backup screen or only the note in `android/README.md`, and plan post-v1 priorities (optional features such as contact names via `wa.db`, Telegram chats, folders/tags, per-user accounts, share to Stash).
3. Engineer: fix `server.py` `_guard()` to include `"PUT"` when closing connections on `ApiError` (problem 1).
4. Engineer: fix Android bugs (problems 2, 3, 6, 7, 12, 17: per-layer error handling in `MainActivity.kt`, reset `moving` safely, reset chat backup in `SetupActivity.save()`, set `errorText` colour, restyle the WhatsApp card in `BackupActivity.kt`, review `ExistingWorkPolicy.KEEP` and add `"Use mobile data this once"`).
5. Engineer: fix `index.html` / `chats.html` / formatting bugs (problems 8, 9, 10, 13, 14, 18: `logout` `firstPaint`, `"Load earlier"` error placement, search-box swipe zone, `peek`/`paneIn`/`resetLeave` edge cases, digit-only `+` prefix on `sender`, decimal sub-1 MB sizes/limits, early-hangup `413` handling, friendly `java` error in `run_jvm_test.py`).
6. Engineer: resumable uploads (needs a server route that accepts byte ranges, so `API.md` changes first).
7. Engineer: share to Stash from other Android apps (share sheet).
8. Engineer: handle system-bar insets before raising `targetSdk` from 34 to 35 (`Ui.page` sets bar colours with calls Android 15 ignores for apps targeting 35).
9. Engineer: per-user accounts and keys, folders and tags, share links, trash and old versions (planned growth, nothing decided).
10. Engineer: add the Python test scripts to `.github/workflows/build-apk.yml` (today it only builds the APK on `main`/`master`).
11. Engineer, only when the owner says "optimize": `.zip` downloads build a full temporary copy in `data/tmp/` first, and both photo backup and chat media backup read each file twice (once to hash, once to upload).
12. Tester: run all 5 Python test suites (`tests/test_server.py`, `tests/test_session_counted.py`, `tests/test_chats.py`, `tests/test_thumbs.py`, `tests/test_chat_media.py`) and `tests/jvm/ApiTest.kt` on the pre-merge `test2-dev` commit and report results; write `tests/jvm/Crypt15Test.kt` if needed.
13. Tester: test the new phone & web behaviour on Android 14 (Infinix X6882) and other devices: `ACTION_OPEN_DOCUMENT` upload picker & main-storage start, layered WebView navigation & left-edge swipe (`MainActivity` + `SwipeBackLayout`), `Thumbs.kt` + browser thumbnail generation, chat media pass (`MediaIndex`, `"Back up older media…"`, `"Stop"`), `"Waiting for Wi-Fi"` / network watching / mobile-data transitions, `SetupActivity` server switching with chat backup on, `index.html` phone layout, and light/dark mode across all screens.
14. Tester: add server tests for wrong password and login 429, 411/413/507, chat import 409/507/`STASH_CHATS_KEEP=0`, `Range` edge cases (416), `ETag`/304, zip contents and `ids=all`, rename clashes, file-name cleaning, restart behaviour.
15. Tester: test denied `ACCESS_MEDIA_LOCATION` (compare geotagged photo vs backup), `backup.db` v1 → v2 migration, and `STASH_DATA` on a network share (`GUIDELINES.md` §2).
16. Scribe: update `CHANGELOG.md`, `TASKS.md`, `TESTING.md`, `API.md`, and architecture diagrams as fixes and test reports land.

## Handoff (unfinished code)
**State:** finished
**Unfinished:** -
**Untested:**
- New from the `test2-dev` v1 review: formal runs of `tests/test_server.py`, `tests/test_session_counted.py`, `tests/test_chats.py`, `tests/test_thumbs.py`, `tests/test_chat_media.py`, and `tests/jvm/ApiTest.kt` on the final pre-merge commit (`test_thumbs.py` has never had a reported run); `Thumbs.kt` & browser thumbnail generation; `ChatBackupWorker` media pass (`MediaIndex`, `X-Source: whatsapp`, `"Back up older media…"`, `"Stop"`); native layered WebView sliding (`MainActivity` + `SwipeBackLayout`); `index.html` phone layout; `ACTION_OPEN_DOCUMENT` upload picker; `"Waiting for Wi-Fi"` / mobile-data behaviour; `SetupActivity` server switch with chat backup enabled; `PUT` early-error keep-alive behaviour in `server.py`; and light/dark styling across updated screens.
- Everything else that is untested is listed in `TESTING.md`.
