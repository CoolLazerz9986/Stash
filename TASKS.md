# Tasks

## Open problems (Bugs)
Biggest first. None is critical or normal today.
1. [minor] If location access is denied, the backup screen says nothing and the backed-up photos silently lose their GPS data.
2. [minor] A 413 where the server hangs up before answering is counted as "failed", not "too big".
3. [minor] The backup status shows "0 MB" when the server limit is under 1 MB, and the server's own 413 message does the same.
4. [minor] `tests/jvm/run_jvm_test.py` crashes with a raw traceback when the java binary is missing.

### Context
1. `Perms.hasMediaLocation` is checked in `BackupWorker.open()`: without it the photo is read in Android's redacted form, so the uploaded copy is not the original. `BackupActivity` shows nothing about it. The tester checked with `adb` on the owner's phone (Infinix X6882, Android 14): `ACCESS_MEDIA_LOCATION` is `granted=true`, because on Android 13 and newer it comes together with "Photos and videos", so a missing prompt is normal there. The tester's reading is that a denial can only happen below Android 13 (minSdk is 29). Nobody has tested a phone where it was actually denied. The owner decides whether a warning or a note in `android/README.md` is enough.
2. `StashApi.upload` turns an early 413 into `ApiException(413)` when it can still read the response, and `BackupWorker` then counts it as "too big". When the server has already closed the socket, the client sees an `IOException` instead, and `BackupWorker` counts that file as "failed" (the server is still reachable). Nothing is recorded in `BackupDb` either way, so the file is tried again next run. This only happens when the limit changed after `MediaStore` reported the size, because the worker skips files over the limit before uploading.
3. `BackupWorker.finish` writes `${limit / 1048576} MB` and `server.py` `_upload` writes `MAX_BYTES // 1048576`: both are integer divisions, so 0.5 MB shows as "0 MB". `API.md` does not specify the message text.
4. `run_jvm_test.py` calls `subprocess.run([...java...])` with no `try`. The paths now come from `STASH_KC`, `STASH_JVM_CP` and `STASH_JAVA` (see `TESTING.md`).

## New features
Tasks for everyone: engineers, testers and scribes.
1. Owner: decide what "back up chats and show them in a WhatsApp/Telegram-style UI" means (source of the chats, what the server stores, what the screen shows). Nothing exists yet. Scribe then writes the new routes into `API.md` before any code.
2. Owner: decide whether the denied-location-permission case (problem 1) needs a warning on the backup screen or only a note in the docs. If a warning, engineer builds it.
3. Engineer: problems 2 and 3 together: show the limit with decimals when it is under 1 MB (backup status and the server's 413 message), and count a 413 hang-up as "too big".
4. Engineer: print a clear message in `run_jvm_test.py` when java is missing (problem 4).
5. Engineer: resumable uploads (needs a server route that accepts byte ranges, so `API.md` changes first).
6. Engineer: share to Stash from other Android apps (share sheet).
7. Engineer: handle system-bar insets before raising `targetSdk` from 34 to 35. `Ui.page` sets the bar colours with calls that Android 15 ignores for apps targeting 35.
8. Engineer: per-user accounts and keys, folders and tags, share links, trash and old versions (planned growth, nothing decided).
9. Engineer: add the test scripts to the GitHub Actions workflow (today it only builds the APK, and only on pushes to `main` or `master`).
10. Engineer, only when the owner says "optimize": a `.zip` download builds a full temporary copy in `data/tmp/` first, and the phone reads every file twice (once to hash, once to upload).
11. Tester: add server tests for wrong password and login 429, 411/413/507, `Range` edge cases (416), `ETag`/304, zip contents and `ids=all`, rename clashes, file-name cleaning, restart behaviour.
12. Tester: check every screen in light and dark mode and report problems with severities. The debug APK has already been built and run on one phone (Infinix X6882, Android 14) by the owner; a screen-by-screen review is not reported yet.
13. Tester: on a phone with location access denied, back up a geotagged photo; then compare the copy with the original to prove the GPS data survives when it is granted and see what happens when it is not.
14. Tester: upgrade a phone from an old v1 `backup.db` to v2 (the migration that forgets "skipped" rows), and run `tests/jvm/ApiTest.kt` (needs a Kotlin compiler).
15. Tester: run the server with `STASH_DATA` on a network share, as planned in `GUIDELINES.md` §2.
16. Scribe: update `API.md`, `TESTING.md` and both diagrams after each feature above lands, and add a `CHANGELOG.md` entry for every engineer or tester report.

## Handoff (unfinished code)
**State:** finished
**Unfinished:** -
**Untested:**
- New from the latest reports: a phone where location access was actually denied; a geotagged photo compared with its backup; the upgrade from an old v1 `backup.db`; `tests/jvm/ApiTest.kt` (not run by the tester). Tasks 13 and 14 cover them.
- The updated `server.py` is not on the repo yet: the owner pushes it by hand.
- Everything else that is untested is listed in `TESTING.md`.
