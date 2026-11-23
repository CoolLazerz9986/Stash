# Stash HTTP API (version 2)

Single source of truth for every client (website, Android app, scripts). `server.py` is the reference implementation.
All paths are relative to the server root, e.g. `http://192.168.1.50:8080`. JSON in, JSON out unless noted.
Errors are `{"error": "message"}` with a normal HTTP status. Version 2 is frozen: it may be extended, never broken.

## Authentication

If the server has no `STASH_PASSWORD`, nothing is protected (and it only listens on 127.0.0.1 by default).
Otherwise a request is authenticated by **either**:

| Method | How | Used by |
|---|---|---|
| Cookie | `POST /api/login` sets `stash_session` (HttpOnly, SameSite=Strict, 30 days, `Secure` when `X-Forwarded-Proto: https`) | website / WebView |
| API key | `Authorization: Bearer <key>` | Android background backup, scripts |

The key is `STASH_API_KEY`, or the contents of `<data>/api.key` (generated on first start). A logged-in session can read it with `GET /api/app-key`.
If an `Authorization: Bearer` header is present it is used and the cookie is ignored.
Wrong passwords and wrong keys share one rate limit: 10 failures per 5 minutes per client address, then `429` (even for the right key, until the window ends). Every wrong password or key is also delayed by 0.6 s.

`GET /api/session` is only a status check, not a login attempt, so it is the one route that never answers `429` and never delays: with a wrong `Bearer` key it answers `200` straight away with `"authed": false`. A wrong key there still counts toward the 10 failures, so the route can't be used to guess keys without limit. While a client address is blocked, `authed` is `false` there even for the right key, until the window ends.

Sessions are stateless: `POST /api/logout` only clears the cookie. Changing `STASH_PASSWORD` (or deleting `secret.key`) invalidates every session.

## Routes

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/` , `/index.html` | no | the website |
| GET | `/chats` , `/chats.html` | no | the WhatsApp chat viewer (the page itself holds no data; its `/api/chats` calls need login) |
| GET | `/healthz` | no | `{"ok": true}` |
| GET | `/api/session` | no | `{"name":"stash","api":2,"authRequired":bool,"authed":bool,"maxBytes":int}`. Clients use `name`/`api` to recognise a compatible server. `api` is `1` (field absent) on the original server. `maxBytes` is the upload limit (default 10 MB). A wrong `Bearer` key gives `authed:false` (see Authentication) |
| POST | `/api/login` | no | body `{"password": "..."}` → sets cookie. `401` wrong password, `429` too many tries |
| POST | `/api/logout` | no | clears the cookie |
| GET | `/api/app-key` | yes | `{"key": "<api key>"}` (empty string when the server has no password and no `STASH_API_KEY`) |
| GET | `/api/files` | yes | `{"files":[file…],"stats":{"count","bytes","total","free"}}`, newest first. `bytes` = total size of stored files; `total`/`free` = disk of the data folder |
| GET | `/api/files/by-hash/<sha256>` | yes | 64 hex chars, case-insensitive (anything else is `404`). `{"exists":bool,"file":file\|null}`. Only `true` if the blob really is on disk. |
| GET | `/api/files/<id>/download` | yes | attachment. Supports `Range`, `ETag` |
| GET | `/api/files/<id>/raw` | yes | inline preview. Text-like types are always `text/plain`; SVG is sandboxed; types with no safe inline form are sent as attachments. `?as=text` forces `text/plain` (used for the text/CSV previews) |
| POST | `/api/upload` | yes | see below. `201` + the file object |
| POST | `/api/zip` | yes | form body `ids=<id>,<id>,…` (max 5000) or `ids=all` → uncompressed zip. The server builds the whole zip in `<data>/tmp/` first, so it needs free space for a second copy (`507` if not) and nothing arrives until it is built |
| PATCH | `/api/files/<id>` | yes | body `{"name": "new name"}`. A clash becomes `name (1).ext` |
| DELETE | `/api/files/<id>` | yes | removes the row and the blob |
| GET | `/api/chats/status` | yes | `{"snapshot": snapshot\|null, "maxBytes": int, "keep": int}`. A `404` means the server has no chat support (too old) |
| GET | `/api/chats` | yes | `{"snapshot": snapshot\|null, "chats": [chat…]}`, newest conversation first |
| GET | `/api/chats/<chat id>/messages` | yes | `?limit=` (1–200, default 100) `&before=<message id>` `&q=<text>`. `{"messages":[message…],"more":bool}`. Messages are the newest `limit` ones (or the newest before `before`), returned oldest first. `q` keeps messages whose text contains it (case-insensitive, max 100 chars). `400` if `before`/`limit` are not numbers, `404` unknown chat or no snapshot |
| POST | `/api/chats/import` | yes | see "WhatsApp chats". `201` + the snapshot object |

`file` = `{id, name, mime, size, sha256, created, modified}` (`created`/`modified` are Unix seconds, `id` is 32 hex chars).

`download` and `raw` also answer `HEAD`, `Range` (`206`, or `416` if out of range) and `If-None-Match` (`304`). The `ETag` is the first 24 hex chars of the file's SHA-256.
When a password is set, any other path under `/api/` answers `401` before it answers `404`. Anything not under `/api/` and not listed above is `404`.

## Upload

`POST /api/upload`, body = the raw file bytes (not multipart).

| Header | Required | Meaning |
|---|---|---|
| `Content-Length` | yes | exact size (no chunked encoding). `411` if missing, `400` if negative, `413` if over `maxBytes`, `507` if the disk would be left with under 64 MB free |
| `X-Filename` | recommended | percent-encoded UTF-8 name (spaces as `%20`, not `+`). Path parts are stripped; names over 200 chars are shortened; empty becomes `unnamed`. A clash becomes `name (1).ext` |
| `Content-Type` | no | used if the extension doesn't give a type |
| `X-Sha256` | no | 64 hex chars. The server hashes what it received and answers `422` (storing nothing) if it differs. `400` if malformed |

The server may answer `413`/`507` and close the connection **before** the body is sent, so a client can see a broken pipe instead of the status. Check `maxBytes` first and treat a dropped upload connection as "ask the server if it is still reachable".
If the client disconnects mid-upload, the partial file is deleted and nothing is stored.

## WhatsApp chats

Added to version 2 without changing anything above. The phone decrypts WhatsApp's own end-to-end encrypted backup (`msgstore.db.crypt15`) with the user's 64-digit key **on the phone**; the server never sees the key. It receives the plain SQLite database, checks it, adds one index (`message(chat_row_id, _id)`) and keeps it as a *snapshot* in `<data>/chats/`. The chat database is never part of `/api/files`.

`POST /api/chats/import`: body = the raw bytes of the plain `msgstore.db`. Headers as for `/api/upload` (`Content-Length` required, `X-Sha256` optional but recommended; `X-Filename` and `Content-Type` are ignored).

| Status | Meaning |
|---|---|
| 201 | stored; body is the snapshot. If the server already held identical bytes as its newest snapshot, nothing new is stored and the snapshot has `"duplicate": true` |
| 400 / 411 | bad or missing `Content-Length`, malformed `X-Sha256` |
| 409 | another chat import is still running (the indexing is heavy); retry later |
| 413 | over `STASH_CHATS_MAX_MB` (default 512, separate from the file limit) |
| 422 | `X-Sha256` mismatch, not an SQLite file (usually a wrong key), or no WhatsApp tables (`jid`, `chat`, `message` with the columns Stash reads) |
| 507 | not enough free disk (needs the size plus 25 % plus 64 MB) |

The server keeps the newest `STASH_CHATS_KEEP` snapshots (default 3, `0` = all) and shows only the newest. Older ones are kept because WhatsApp forgets deleted messages.
Timestamps are converted to Unix seconds. Contact names are not in this database, so a chat is named by the group subject or the phone number (`+<digits>`).

Objects:

- `snapshot` = `{name, ts, size, sha256, chats, messages}`. `sha256` is the hash of the **bytes the phone uploaded** (not of the stored file, which has the extra index), so a client can compare its own hash with it to skip an upload.
- `chat` = `{id, name, jid, group, count, last}`, `last` = `{ts, me, text}` or `null`. Status broadcasts and empty chats are left out.
- `message` = `{id, me, ts, text, label?, sender?}`. `label` is set for non-text messages (`Photo`, `Voice message`, `Video`, …) and `text` may then be empty. `sender` (digits of the phone number) is set for messages from others, mainly useful in groups.

## Status codes at a glance

| Code | Meaning here |
|---|---|
| 200 / 201 | ok / file or snapshot stored |
| 400 | bad JSON, bad `Content-Length`, malformed `X-Sha256`, bad `before`/`limit` |
| 401 | no or wrong login / key |
| 404 | unknown route, unknown file or chat id, blob missing on disk, bad hash length, no chat snapshot yet |
| 409 | a chat import is already running |
| 411 | upload without `Content-Length` |
| 413 | upload over `maxBytes` (chats: over the chat limit), or a request body over its limit (64 KB for JSON, 1 MB for the zip form) |
| 416 | `Range` outside the file |
| 422 | `X-Sha256` doesn't match the received bytes; chat database not readable or not in the WhatsApp format |
| 429 | rate limited (wrong password / key). Never sent by `GET /api/session` |
| 500 | server bug (see its log) |
| 507 | not enough free disk |

## Backup client algorithm (Android app v1)

For each photo/video not yet recorded as done: hash it (SHA-256) → `GET /api/files/by-hash/<sha>` → if `exists`, record and skip → else `POST /api/upload` with `X-Sha256` → on `201` record it. Files are matched to what was already done by (MediaStore URI, size, modified time). One-way: nothing is ever deleted on the phone or on the server by the backup.
A file bigger than `maxBytes` (from `GET /api/session`) is not hashed or uploaded and is **not** recorded, so it is picked up again once the limit is raised. The same goes for a `413` answer.

**Chats:** `GET /api/chats/status` (remember `snapshot.sha256`, `maxBytes`) → find the newest `msgstore*.crypt15` in the folder the user picked → skip if its (server, name, size, modified time) equals the last one sent → decrypt into a temporary file in the app cache while hashing → skip if the hash equals `snapshot.sha256` → skip with a message if bigger than the chat `maxBytes` → `POST /api/chats/import` with `X-Sha256` (the answer can take minutes while the server indexes, so wait up to 15 minutes) → delete the temporary file. The WhatsApp folder is only read.

## Compatibility

`api` 1 = original website API. `api` 2 = adds API key auth, `/api/app-key`, `/api/files/by-hash/…`, `X-Sha256`, and the `name`/`api` fields in `/api/session`, plus the chat routes above (all additions, so the number stays 2). Nothing from version 1 was removed or changed.
