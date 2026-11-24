# Changelog

## Update v1
(current update, still in progress)
- WhatsApp chat vault added on the server (`chats.py`, `server.py` `/chats`, `/api/chats/status`, `/api/chats`, `/api/chats/<id>/messages`, `/api/chats/import`, `STASH_CHATS_MAX_MB` default 512, `STASH_CHATS_KEEP` default 3), with `tests/test_chats.py` (was: no chat backup or viewer)
- WhatsApp chat viewer (`chats.html`) rewritten with `index.html`'s colours and button press feedback, back chevron to `/`, narrow-screen list/chat switching, chat and in-chat search, "Load earlier messages", shared light/dark `theme`, and `window.stashBack()`; `index.html` header adds a Chats button (was: plain first-version desktop layout with no `index.html` link and no app back hook)
- On-device WhatsApp `crypt15` backup (alpha v1) added to the Android app (`Crypt15.kt`, `WhatsAppBackup.kt`, `StashApi.kt`, `Prefs.kt`, `BackupScheduler.kt`, `BackupActivity.kt`): 64-digit key decryption in 64 KB chunks, SAF WhatsApp folder picker, 6-hour schedule, "Back up chats now", and "Open chats" (was: photo and video backup only)
- `MainActivity.kt` updated with stepwise Back navigation (open `<dialog>` → `window.stashBack()` → non-files pages back to `/` → leave app), website file chooser switched to `ACTION_OPEN_DOCUMENT` starting at main storage so it does not reopen in the WhatsApp folder, `EXTRA_PATH` support (`/chats`), 8-second `StashApi.session` pre-flight check, and reconnect reload on resume when returning with the same address (was: Back always left the app, picker opened in the last-used WhatsApp folder, and the error screen stayed on resume)
- Backup screen and scheduler (`BackupActivity.kt`, `BackupScheduler.kt`) watch network state and report `"Waiting for Wi-Fi (Wi-Fi only is on)"` plus a queued toast when held back without Wi-Fi (was: status stayed idle / `"Starting…"` when WorkManager waited for Wi-Fi)
- Default upload limit is now 10 MB, in the code and in the `server.py` header comment (was: 2048 MB in the code, while `GUIDELINES.md` and the README said 10 MB)
- Phone files over the server's size limit are counted as "too big" in the backup status and are no longer recorded as done, so they are tried again once the limit is raised; `backup.db` upgrades to version 2 and forgets old "skipped" rows once (was: they were recorded as "skipped" and never tried again)
- `GET /api/session` with a wrong `Authorization: Bearer` key is still counted toward the 10-failure limit, but is no longer delayed by 0.6 s and never answers 429; `authed` stays false while that client is blocked (was: delayed 0.6 s and answered 429 once the client was rate limited)
- `tests/jvm/run_jvm_test.py` reads its paths from `STASH_KC`, `STASH_JVM_CP` and `STASH_JAVA` (was: hard-coded to `/home/claude/kc/...`)
- `tests/test_session_counted.py` added: wrong key on `/api/session`, the 10-miss limit, a 16-character key, a server with no password (was: no test for these)
- `server.py` starts with a `# Path:` header line (was: no header)
- File names in `GUIDELINES.md` and `RULES.md` fixed to the real ones (`RULES.md`, `TASKS.md`, `CHANGELOG.md`, `REPORTS.MD`), and the title of `REPORTS.MD` matches its file name (was: `rules.md`, `Tasks.md`, "the changelog" and `REPORTS.md`, which break on Linux and GitHub)
- `README.md`, `API.md`, `TESTING.md`, `android/README.md`, `stash-architecture.html`, and `stash-android-architecture.html` updated to match the code above (was: behind the `test2-dev` chat backup, `chats.html`, Wi-Fi wait, and `MainActivity` changes)

## Update v1.11.21
(finished update)
- Android app added: WebView plus automatic photo and video backup (was: website only)
- Server API version 2: API key, hash lookup, X-Sha256 check (was: API version 1)
