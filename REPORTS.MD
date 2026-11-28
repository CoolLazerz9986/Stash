# REPORTS.MD

Report layout for each role. Find your assigned role and follow its layout.

| Role | Standard |
|---|---|
| Engineer | Loose: write it however you want, as long as it covers the three parts |
| Tester | Loose: write it however you want, as long as it covers the three parts |
| Scribe | Strict: exact formats below, because these documents eventually go to the main branch |

**Empty parts:** if there is nothing important to write in a part or section, write `-` or leave it out entirely. Never fill it with filler. This applies to every role. Example: `**Untested:** -`

---

## Engineer

Cover these three parts:

- **Previous:** how it behaved or looked before your change
- **Changed:** what you changed now (files and what the change does)
- **Untested:** what you did not run or could not verify, and how to test it

Example:

```markdown
## Report: zip streaming
**Previous:** /api/zip built the whole zip in tmp/ before sending.
**Changed:** server.py now streams the zip entry by entry; no temp file.
**Untested:** zips over 1 GB on the real device. Test: select 20 large files, download .zip, watch disk.
```

---

## Tester

Cover these three parts:

- **Tested:** what you ran or checked
- **Results:** what happened (pass / fail, numbers, output)
- **Bugs:** anything wrong, each tagged with a severity:
  - **minor:** cosmetic or rare, nothing breaks
  - **normal:** a real bug with a workaround, or a clear annoyance
  - **critical:** data loss, security hole, crash, or the feature does not work

Example:

```markdown
## Report: zip streaming
**Tested:** tests/test_server.py, plus a 300 MB zip of 5 files.
**Results:** 33 passed, 0 failed. Zip opened fine.
**Problems:**
- [normal] Cancelling a download mid-zip leaves the connection open for 60 s.
- [minor] Zip name has no timezone.
```

---

## Scribe

Scribes write documents that eventually go to the main branch, so these formats are fixed. Do not change the headings or the order.

### CHANGELOG.md

Newest update on top. The update being worked on right now is headed with the main version only. When its features are finished, rename the heading to the dated version and start a new current update above it.

```markdown
# Changelog

## Update v1
(current update, still in progress)
- <what changed> (was: <how it was before>)
- ...

## Update v1.11.21
(finished update)
- <what changed> (was: <how it was before>)
- ...
```

**Example:**

```markdown
# Changelog

## Update v1
(current update, still in progress)
- Share links added to the file viewer (was: no way to share a single file)
- Zip download streams entry by entry (was: whole zip built in tmp/ first)

## Update v1.11.22
(finished update)
- Rename now keeps the file extension selected in the dialog (was: whole name selected)
- Upload queue shows speed per file (was: only total progress)

## Update v1.11.21
(finished update)
- Android app added: WebView plus automatic photo and video backup (was: website only)
- Server API version 2: API key, hash lookup, X-Sha256 check (was: API version 1)
```

**Version naming scheme:** `vX.(month).(day)`

- `X` is the main version number.
- `.(month).(day)` is the date of the update or upgrade.
- Month and day always use two digits, with a leading zero: `v1.12.03` is December 3rd.
- Examples: `v1.11.21` is November 21st, `v1.11.22` is November 22nd.
- The main version number never goes up unless the project owner says so.

### TASKS.md

```markdown
# Tasks

## Open problems (Bugs)
1. <what the problem is>
2. <what the problem is>
3. <what the problem is>

### Context
1. <extra information to help solve problem 1>
2. <extra information to help solve problem 2>
3. <extra information to help solve problem 3>

## New features
Tasks for everyone: engineers, testers and scribes.
1. <task>
2. <task>
3. <task>

## Handoff (unfinished code)
**State:** finished | unfinished
**Unfinished:** what the unfinished parts are
**Untested:** what to test, etc.
```

**Example:**

```markdown
# Tasks

## Open problems (Bugs)
1. Cancelling a zip download leaves the connection open for about 60 seconds.
2. A backup interrupted halfway through a large video restarts that video from zero.
3. The app has never been run on a real phone.

### Context
1. The zip is built in tmp/ before sending, see _zip in server.py. The handler only notices the client left when it writes.
2. The server has no resumable uploads. A fix needs a new route that accepts byte ranges, so API.md changes first.
3. TESTING.md lists what was and was not tested. The first Gradle build is the first real test of the AndroidX code.

## New features
Tasks for everyone: engineers, testers and scribes.
1. Engineer: share links for single files.
2. Engineer: lower the default STASH_MAX_MB, the disk is only 8 GB.
3. Tester: run the Gradle build and try backup on a phone, report problems.
4. Scribe: update API.md and the architecture diagrams once share links land.

## Handoff (unfinished code)
**State:** unfinished
**Unfinished:** share links. The route in server.py is written, the button in index.html is not.
**Untested:** the new route with a wrong id, and with a file deleted after the link was made.
```

- Context numbers match the problem numbers above them.

### Scribe rules

- Every `CHANGELOG.md` entry comes from an engineer or tester report. Do not invent changes.
- If a report is missing a part, ask for it instead of guessing.
- Put problems from reports under **Open problems (Bugs)**, with the details under **Context**.
- Put untested items from reports under **Handoff, Untested**, but only the new ones from the latest reports. `TESTING.md` ("What is NOT tested") is the one lasting list of everything untested: add each item there too, and end the handoff with a pointer to it instead of copying the list. Remove an item from the handoff once it has been checked; it stays in `TESTING.md` only while it is still untested. If nothing new is untested, write `-` plus the pointer.
