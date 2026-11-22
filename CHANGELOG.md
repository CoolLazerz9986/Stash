# Changelog

## Update v1
(current update, still in progress)
- Default upload limit is now 10 MB, in the code and in the `server.py` header comment (was: 2048 MB in the code, while `GUIDELINES.md` and the README said 10 MB)
- Phone files over the server's size limit are counted as "too big" in the backup status and are no longer recorded as done, so they are tried again once the limit is raised; `backup.db` upgrades to version 2 and forgets old "skipped" rows once (was: they were recorded as "skipped" and never tried again)
- `GET /api/session` with a wrong `Authorization: Bearer` key is still counted toward the 10-failure limit, but is no longer delayed by 0.6 s and never answers 429; `authed` stays false while that client is blocked (was: delayed 0.6 s and answered 429 once the client was rate limited)
- `tests/jvm/run_jvm_test.py` reads its paths from `STASH_KC`, `STASH_JVM_CP` and `STASH_JAVA` (was: hard-coded to `/home/claude/kc/...`)
- `tests/test_session_counted.py` added: wrong key on `/api/session`, the 10-miss limit, a 16-character key, a server with no password (was: no test for these)
- `server.py` starts with a `# Path:` header line (was: no header)
- File names in `GUIDELINES.md` and `RULES.md` fixed to the real ones (`RULES.md`, `TASKS.md`, `CHANGELOG.md`, `REPORTS.MD`), and the title of `REPORTS.MD` matches its file name (was: `rules.md`, `Tasks.md`, "the changelog" and `REPORTS.md`, which break on Linux and GitHub)
- `README.md`, `API.md`, `TESTING.md` and `android/README.md` updated to match the code above (was: the README said a wrong key on `/api/session` is not counted, `API.md` did not describe that route's key handling, `TESTING.md` said `run_jvm_test.py` has hard-coded paths, and `android/README.md` said the app was never tested on a real device)
- Android screens restyled to match the website: soft grey page, white rounded cards with a hairline border, indigo accent, rounded buttons with ripple, and a status and navigation bar that follow the page colour; follows the phone's light or dark setting (was: default Android widgets with no website styling)
- Phone backup screen rebuilt as four cards (server, schedule, what to back up, status) with "Back up now" as the main button (was: one plain list of switches and buttons)
- Server address screen restyled with a logo header, a card and a rounded input that gets an accent border when focused (was: plain text box and button)
- Folder picker dialog has rounded corners and accent-coloured buttons (was: stock dialog)
- Error screen ("Can't reach the server") takes its background from the same palette (was: the theme's background colour)

## Update v1.11.21
(finished update)
- Android app added: WebView plus automatic photo and video backup (was: website only)
- Server API version 2: API key, hash lookup, X-Sha256 check (was: API version 1)
