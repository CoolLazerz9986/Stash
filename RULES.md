# RULES.md

Rules for every AI (and human) working on Stash. Read this before touching anything.

## 1. Roles

| Role | Does | Writes |
|---|---|---|
| Engineer | Writes or changes code | Engineer report |
| Tester | Runs and writes tests, checks behaviour | Tester report |
| Scribe | Keeps documentation current | `CHANGELOG.md` and `TASKS.md` |

Everyone except the scribe ends their work with a short report. The scribe's output is the docs, which eventually go to the main branch.

## 2. Short reports

Keep reports short: a few lines per section, no essays. Be honest about what is untested. Never write "should work" as if it were tested.

## 3. Scribes

- Update docs (`README.md`, `API.md`, `TESTING.md`, architecture diagrams) so they match the code.
- Add an entry to `CHANGELOG.md` for every change that engineers or testers report.
- Keep `TASKS.md` current.
- Untested items live in two places on purpose: `TESTING.md` is the lasting list, and the `TASKS.md` handoff only holds what the latest reports added, plus a pointer to `TESTING.md`. Don't copy the whole list into the handoff. Details are in `REPORTS.MD` (Scribe rules).
- Scribes do not change code.
- they mainly proofread documents and code, but they do not test them, thats for the tester, and testers should also do more than just testing like intentionally finding bugs
- and if you noticed, that both scribes and testers write problems in their reports, but keep in mind that scribes strictly fact check the code and not test them, and also it would be nice if the scribe also would categorize the bugs found from big to small like how tester does it

## 4. Hard constraints

- Host: S905X, 4 cores @ 1.5 GHz, 2 GB RAM, 8 GB storage.
- Server is Python standard library only: no pip, no Node, no build step, no CDN.
- Stream files. Never load a whole file into RAM.
- Keep code lightweight. Optimize only when the task says "optimize".
- Android: no new dependency without a written reason.
- Backup is one-way. Never delete anything on the phone.
- Never remove or change existing API behaviour. Only add. Change `API.md` first, then the code.
- Every code file you write or change starts with a commented-out line saying where it lives in the repo. See section 7.

## 5. Session limits and handoff

The AI cannot see its token limit, so it must not leave work only half delivered.

- Send finished pieces as they are done instead of saving everything for the end.
- When a session may be running out, or about 2 minutes into a heavy session, stop at a clean point and send:
  1. the unfinished code,
  2. a short report of what was done and what still needs doing.
- Whatever was worked on must always be sent, even if incomplete.
- End that message with this tag:

`(forced sent, token limit is almost out)`

## 6. Report layout

Follow the report layout for your assigned role. The layouts live in [`REPORTS.MD`](REPORTS.MD).

## 7. File location header

Every code or config file you write or change must say where it belongs, so a file sent on its own can always be put back in the right place. The owner adds and pushes files by hand, so this matters.

**The rule**

- The file has a commented-out line at the top (or as near the top as the format allows) in this form, with the path written from the repo root, forward slashes, exact capitalisation:

  `Path: android/app/src/main/java/app/stash/android/Ui.kt`

- Put it in whatever comment syntax the file uses. It goes **after** anything that has to stay first, never before it.
- **Write or change:** before you send a file, check its header exists and the path is correct.
- **Move or rename:** update the header of the moved file in the same change. No stale paths.
- **Delete:** say the deleted path in your report (under **Changed**). Check that no other file's header still names it.
- **Folders** can't hold a comment. A folder's location is the path in the headers of the files inside it. Name a new, moved or deleted folder in your report.

**Where it goes, by file type**

| File | Header | Notes |
|---|---|---|
| `.py` | `# Path: server.py` | after the `#!` line, before the docstring |
| `.kt`, `.kts` | `// Path: …` | before `package` |
| `.html` | `<!-- Path: index.html -->` | after `<!doctype html>` |
| `.xml` | `<!-- Path: … -->` | after the `<?xml …?>` line, which must stay first |
| `.yml`, `.properties`, `.gitignore`, shell scripts | `# Path: …` | after a `#!` line if there is one |
| `.bat` | `@rem Path: …` | |

**Exceptions**

- Markdown docs (`.md`, `.MD`) are exempt. The documentation map in `README.md` says where they are.
- Files that can't hold a comment (`.json`, binaries such as `gradle-wrapper.jar`) get no header. Put their path in your report instead.
- Files that already exist without a header get one **when you next change them**. Don't touch files you aren't changing just to add it.
