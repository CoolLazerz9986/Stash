# Testing

The passing tests are frozen (`GUIDELINES.md` §5): change code so they keep passing, don't weaken them.
"Last run" figures below are what the earlier author, the tester, the owner, or the scribe recorded; each line says who ran it. The scribe counted the checks in the test files (32, 13, 31, and 12) and does not run tests, except where a line says so.

## Server (needs only Python 3.8+)

```bash
python3 tests/test_server.py
```

Starts a throw-away server on port 18099 with a temp data folder and password, then runs 32 checks:

- **Session:** the `name`/`api` fields, `authRequired`/`authed`, the old `maxBytes` field.
- **Auth:** `/api/files`, `/api/files/by-hash/…` and `/api/app-key` refuse requests with no login; `api.key` is generated (48 chars, mode 600); cookie login works; `/api/app-key` returns the key to a logged-in session; bearer auth works on `/api/files` and `/api/session`.
- **Hash lookup:** hit, miss, upper-case hash, bad length (404), blob missing on disk (`exists: false`).
- **Checksum on upload:** `X-Sha256` correct (201), wrong (422, nothing stored, `tmp/` empty, only the good blob on disk), malformed (400).
- **Old behaviour still works:** cookie upload without checksum, duplicate names (`a (1).txt`), rename, `Range` (206), zip, delete, index page served.
- **Rate limiting:** wrong keys give 401, then 429; the right key is also refused while blocked.

Last run: **32 passed, 0 failed**. Re-run by the scribe on 2026-11-21 against the `android-ui-refresh` branch: **32 passed, 0 failed** (that branch's `server.py` was 680 lines). Re-run by the tester against the previous updated `server.py` (696 lines: 10 MB default, `/api/session` never delayed): **32 passed, 0 failed**. Not yet formally re-run on `test2-dev` after `chats.py` was wired in (owner believes it passes; unverified).

Takes a while on purpose: every wrong key on a normal route costs the server a 0.6 s delay.

### Wrong keys on `/api/session`

`tests/test_session_counted.py` covers a wrong key on `GET /api/session`, the 10-miss limit, a 16-character key, and a server with no password. 13 checks. Last run by the tester on the previous `server.py`: **13 passed, 0 failed**. Not yet formally re-run on `test2-dev`.

### WhatsApp chat vault (`tests/test_chats.py`)

```bash
python3 tests/test_chats.py
```

Starts throw-away servers on ports 18114 (`STASH_CHATS_KEEP=2`) and 18115 (`STASH_CHATS_MAX_MB=0.001`) with synthetic `msgstore.db` SQLite databases, then runs 31 checks:

- **Routing & auth:** `/chats` served (200); `/api/chats` and `/api/chats/status` require auth (401); empty status/overview before any import; `/api/chats/10/messages` returns 404 before import.
- **Import & summary:** `POST /api/chats/import` returns 201 with chat/message counts (`status@broadcast` excluded); `tmp/` empty afterwards; `/api/chats/status` shows the uploaded SHA-256; chat names (`Family`, `+6281234`), newest-first sorting, and ms-to-s timestamp conversion.
- **Messages & paging:** newest 100 returned oldest-first with `more: true`; `before` cursor fetches remaining 150 with `more: false`; `q` search escapes `%` literally; group `sender`, `me` flag, and media `label`; unknown chat (404) and bad `before` (400).
- **Validation & pruning:** re-importing identical bytes returns `201` with `duplicate: true` and stores no extra snapshot; wrong `X-Sha256` (422), non-SQLite (422), SQLite missing WhatsApp tables (422), missing `Content-Length` (411/400), and `tmp/` clean after failures; newer snapshots replace the active view; `STASH_CHATS_KEEP=2` prunes the oldest `.db` and `.json`; `/api/files` and `/api/session` stay untouched.
- **Chat size limit:** over `STASH_CHATS_MAX_MB` returns `413`; `/api/chats/status` reports `maxBytes`.

**Last run:** not yet formally run/reported on `test2-dev` (owner believes it passes). Note: an identical duplicate `test_chats.py` is currently sitting at the repo root (`test_chats.py`); running that root copy fails because `parent.parent / "server.py"` resolves outside the repo (tracked in `TASKS.md`).

## Android HTTP client against a live server (optional, needs a JDK + Kotlin compiler)

`tests/jvm/ApiTest.kt` drives the app's real `StashApi.kt` against a running server on a normal JVM, with the server limited to 1 MB (`STASH_MAX_MB=1`). 12 checks: session parsed, `fetchAppKey` with a bad cookie (401) and a good one (48-char key), `exists` before and after an upload, upload with progress, wrong key (401), wrong checksum (422), file shorter than announced (`SourceException`), oversize upload (413 or a dropped connection, both accepted), server still reachable afterwards, unreachable server (`IOException`).

It needs `org.json` on the classpath (the Android SDK jar only has stubs) and the Kotlin compiler. Run it with `python3 tests/jvm/run_jvm_test.py` (server on port 18098). The script takes its paths from environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `STASH_KC` | `/home/claude/kc` (the original author's machine) | folder that holds `jvmout/`, `jsonout/` and `kotlinc/` |
| `STASH_JVM_CP` | built from `STASH_KC` | full java classpath; if set, `STASH_KC` is ignored |
| `STASH_JAVA` | `java` | java binary |

The classpath needs the compiled `ApiTest` classes, `org.json` and `kotlin-stdlib.jar`. The tester checked the variables with a fake java. If the java binary is missing, the script ends with a raw Python traceback instead of a message (see `TASKS.md`).

Last run: all 12 checks passed (the earlier author's record, before `StashApi.chatStatus` and chat `upload` parameters were added). The tester and the scribe have not run `ApiTest.kt`.

`Crypt15.kt` also mentions `tests/jvm/Crypt15Test.kt` in its header comment, but `tests/jvm/Crypt15Test.kt` is missing from the repository.

## Not run automatically

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) only builds the debug APK, and only on pushes to `main` or `master` (on any other branch, press "Run workflow" by hand). It does not run any test script. Run them by hand before pushing server or `StashApi.kt` changes.

*(Note: `.github/workflows/build-apk.yml`, `AndroidManifest.xml`, and `android/app/src/main/res/...` were not part of the `test2-dev` files reviewed by the scribe.)*

## What is NOT tested

This is the lasting list. The handoff in `TASKS.md` only holds what the latest reports added, and points here for the rest.

**On the phone.** The owner built the debug APK and ran photo/video backup, `ACCESS_MEDIA_LOCATION` check, and WhatsApp chat backup + viewing on one phone (Infinix X6882, Android 14) over a Wi-Fi router. Everything else on the phone is unverified:
- Other phones and Android versions
- `ACTION_OPEN_DOCUMENT` WebView uploads on Android 14 (single and multiple files, opening at main storage after picking the WhatsApp folder, and whether any needed gallery apps are missing vs `GET_CONTENT`)
- Stepwise Back navigation on a phone (viewer → open chat → chat list → files → leave app; and Back on the error overlay)
- The `"Waiting for Wi-Fi (Wi-Fi only is on)"` status, `ConnectivityManager.NetworkCallback` refresh, queued Wi-Fi toast, and mobile-data behaviour
- `SetupActivity` switching to a different server while WhatsApp chat backup is enabled
- Light and dark mode on the updated `BackupActivity` cards (including the un-restyled WhatsApp card), `MainActivity` error overlay text colour, and `chats.html` header/search rows
- A phone where `ACCESS_MEDIA_LOCATION` was denied, and a geotagged photo compared with its backup
- Upgrading from an old v1 `backup.db` to v2
- Edge cases of `Crypt15.kt` decryption on real `msgstore.db.crypt15` files (`tests/jvm/Crypt15Test.kt` is missing), `WaSource` across WhatsApp vs WhatsApp Business folders, the foreground notification, `Downloader`, and WorkManager scheduling over days
- The Kotlin sources were statically reviewed by the tester for `MainActivity.kt` only; `Crypt15.kt`, `WhatsAppBackup.kt`, `BackupActivity.kt` (chat/network additions), `BackupScheduler.kt`, `Prefs.kt`, and `StashApi.kt` have not had a formal compile/test report on `test2-dev`

**On the server** (unverified or not formally re-run on `test2-dev`):
- Formal re-run of `tests/test_server.py`, `tests/test_session_counted.py`, `tests/test_chats.py`, and `tests/jvm/ApiTest.kt` against the current `test2-dev` commit
- Wrong password at login (401), login rate limit (429), logout
- Upload errors: missing `Content-Length` (411), over the size limit (413; only exercised via the JVM test), low disk (507), client dropping mid-upload
- Chat import edge cases: concurrent `409` lock, `507` disk-full check, `STASH_CHATS_KEEP=0`, missing optional columns (`chat.subject`, `message.sender_jid_row_id`), `STASH_CHATS_MAX_MB` / `STASH_CHATS_KEEP` defaults on `server.py`
- `Range` edge cases (416, suffix ranges), `ETag`/304, `HEAD`
- Zip contents (only the `PK` header is checked), `ids=all`, the zip's temporary file
- Rename clashes and file-name cleaning (path parts, over-long names)
- Server restart behaviour: leftover `tmp/` files deleted, `STASH_API_KEY` override, password-less mode
- Anything on a slow or network-mounted `STASH_DATA`

**Front-end.** Neither `index.html` nor `chats.html` has automated tests: uploads, previews, selection, dialogs, the login screen, `chats.html` concurrent `load()` race, `#qrow` visibility before opening a chat, and narrow-phone date wrapping are checked by inspection or by hand only.

**Diagrams.** `stash-architecture.html` and `stash-android-architecture.html` were updated by the scribe to match `test2-dev` and were not rendered in a browser.
