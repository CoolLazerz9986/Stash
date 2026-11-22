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

## Status codes at a glance

| Code | Meaning here |
|---|---|
| 200 / 201 | ok / file stored |
| 400 | bad JSON, bad `Content-Length`, malformed `X-Sha256` |
| 401 | no or wrong login / key |
| 404 | unknown route, unknown file id, blob missing on disk, bad hash length |
| 411 | upload without `Content-Length` |
| 413 | upload over `maxBytes`, or a request body over its limit (64 KB for JSON, 1 MB for the zip form) |
| 416 | `Range` outside the file |
| 422 | `X-Sha256` doesn't match the received bytes |
| 429 | rate limited (wrong password / key). Never sent by `GET /api/session` |
| 500 | server bug (see its log) |
| 507 | not enough free disk |

## Backup client algorithm (Android app v1)

For each photo/video not yet recorded as done: hash it (SHA-256) → `GET /api/files/by-hash/<sha>` → if `exists`, record and skip → else `POST /api/upload` with `X-Sha256` → on `201` record it. Files are matched to what was already done by (MediaStore URI, size, modified time). One-way: nothing is ever deleted on the phone or on the server by the backup.
A file bigger than `maxBytes` (from `GET /api/session`) is not hashed or uploaded and is **not** recorded, so it is picked up again once the limit is raised. The same goes for a `413` answer.

## Compatibility

`api` 1 = original website API. `api` 2 = adds API key auth, `/api/app-key`, `/api/files/by-hash/…`, `X-Sha256`, and the `name`/`api` fields in `/api/session`. Nothing from version 1 was removed or changed.
