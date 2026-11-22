# Testing

The passing tests are frozen (`GUIDELINES.md` §5): change code so they keep passing, don't weaken them.
"Last run" figures below are what the earlier author, the tester or the scribe recorded; each line says who ran it. The scribe counted the checks in the test files (32 and 12, matching) and does not run tests, except where a line says so.

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

Last run: **32 passed, 0 failed**. Re-run by the scribe on 2026-11-21 against the `android-ui-refresh` branch: **32 passed, 0 failed** (that branch's `server.py` was 680 lines). Re-run by the tester against the updated `server.py` (696 lines: 10 MB default, `/api/session` never delayed): **32 passed, 0 failed**. The scribe did not re-run it on the updated file.

Takes a while on purpose: every wrong key on a normal route costs the server a 0.6 s delay.

### Wrong keys on `/api/session`

`tests/test_session_counted.py` covers a wrong key on `GET /api/session`, the 10-miss limit, a 16-character key, and a server with no password. 13 checks. Last run by the tester: **13 passed, 0 failed**.

## Android HTTP client against a live server (optional, needs a JDK + Kotlin compiler)

`tests/jvm/ApiTest.kt` drives the app's real `StashApi.kt` against a running server on a normal JVM, with the server limited to 1 MB (`STASH_MAX_MB=1`). 12 checks: session parsed, `fetchAppKey` with a bad cookie (401) and a good one (48-char key), `exists` before and after an upload, upload with progress, wrong key (401), wrong checksum (422), file shorter than announced (`SourceException`), oversize upload (413 or a dropped connection, both accepted), server still reachable afterwards, unreachable server (`IOException`).

It needs `org.json` on the classpath (the Android SDK jar only has stubs) and the Kotlin compiler. Run it with `python3 tests/jvm/run_jvm_test.py` (server on port 18098). The script takes its paths from environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `STASH_KC` | `/home/claude/kc` (the original author's machine) | folder that holds `jvmout/`, `jsonout/` and `kotlinc/` |
| `STASH_JVM_CP` | built from `STASH_KC` | full java classpath; if set, `STASH_KC` is ignored |
| `STASH_JAVA` | `java` | java binary |

The classpath needs the compiled `ApiTest` classes, `org.json` and `kotlin-stdlib.jar`. The tester checked the variables with a fake java. If the java binary is missing, the script ends with a raw Python traceback instead of a message (see `TASKS.md`).

Last run: all 12 checks passed (the earlier author's record). The tester and the scribe have not run `ApiTest.kt`.

## Not run automatically

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) only builds the debug APK, and only on pushes to `main` or `master` (on any other branch, press "Run workflow" by hand). It does not run any test script. Run them by hand before pushing server or `StashApi.kt` changes.

## What is NOT tested

This is the lasting list. The handoff in `TASKS.md` only holds what the latest reports added, and points here for the rest.

**On the phone.** The owner built the debug APK and ran the backup on one phone (Infinix X6882, Android 14); the tester reports that over-limit phone files are now handled correctly there, and `adb` shows `ACCESS_MEDIA_LOCATION` granted. Everything else on the phone is unverified: other phones and Android versions, a phone where location access was denied, a geotagged photo compared with its backup, the upgrade from an old v1 `backup.db`, the restyled screens in light and dark mode, system-bar colours, the foreground notification, the folder dialog, `Downloader`, and WorkManager scheduling over days. The Kotlin sources compiled against the Android 34 SDK (`android.jar`); AndroidX classes were checked against hand-written stubs of the signatures used, and the Gradle build is the real test of those.

**On the server** (no check exists for these, so treat them as unverified):

- Wrong password at login (401), login rate limit (429), logout
- Upload errors: missing `Content-Length` (411), over the size limit (413; only exercised via the JVM test), low disk (507), client dropping mid-upload
- The 10 MB default and `STASH_MAX_MB` overrides: the tester checked them with throw-away scripts that are not in the repo
- `Range` edge cases (416, suffix ranges), `ETag`/`304`, `HEAD`
- Zip contents (only the `PK` header is checked), `ids=all`, the zip's temporary file
- Rename clashes and file-name cleaning (path parts, over-long names)
- Server restart behaviour: leftover `tmp/` files deleted, `STASH_API_KEY` override, password-less mode
- Anything on a slow or network-mounted `STASH_DATA`

**Front-end.** `index.html` has no automated tests: uploads, previews, selection, dialogs and the login screen are checked by hand only.

**Diagrams.** The architecture diagram scripts were syntax-checked but not rendered in a browser.
