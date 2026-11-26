#!/usr/bin/env python3
# Path: server.py
"""
Stash - a tiny self-hosted data vault.

Upload any kind of file, browse it in a neat web UI, and download it back
whenever you like. Python 3.8+ standard library only: no pip, no node, no
separate database server. Metadata lives in SQLite, file contents on disk.

    STASH_PASSWORD=choose-a-password python3 server.py

Settings (environment variables):
    STASH_PASSWORD  shared password. If unset, the server only listens on
                    127.0.0.1 (this machine) so it is never exposed by accident.
    STASH_HOST      bind address   (default 0.0.0.0 with a password, else 127.0.0.1)
    STASH_PORT      port           (default 8080)
    STASH_DATA      data directory (default ./data next to this file)
    STASH_MAX_MB    max size of one upload in MB (default 10)
    STASH_API_KEY   key the Android app (or scripts) send as "Authorization: Bearer <key>".
                    If unset and a password is set, one is generated on first start and
                    stored in <data>/api.key. Delete that file and restart to rotate it.
    STASH_CHATS_MAX_MB  max size of one WhatsApp chat database in MB (default 512; separate from STASH_MAX_MB)
    STASH_CHATS_KEEP    how many chat database snapshots to keep (default 3, 0 = keep all)

The WhatsApp chat vault lives in chats.py (next to this file).
Files carry an optional "source" tag (header X-Source on upload, e.g. "whatsapp" for chat media). It is only a label:
it never changes where a file is stored or how it is matched by hash.
"""
import hashlib
import hmac
import json
import mimetypes
import os
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import sys
import tempfile
import threading
import time
import traceback
import uuid
import zipfile
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

import chats

BASE = Path(__file__).resolve().parent
DATA = Path(os.environ.get("STASH_DATA", str(BASE / "data"))).resolve()
BLOBS = DATA / "blobs"
TMP = DATA / "tmp"
PASSWORD = os.environ.get("STASH_PASSWORD", "")
PORT = int(os.environ.get("STASH_PORT", "8080"))
HOST = os.environ.get("STASH_HOST") or ("0.0.0.0" if PASSWORD else "127.0.0.1")
MAX_BYTES = int(float(os.environ.get("STASH_MAX_MB", "10")) * 1024 * 1024)
CHATS_MAX_BYTES = int(float(os.environ.get("STASH_CHATS_MAX_MB", "512")) * 1024 * 1024)
CHATS_KEEP = int(os.environ.get("STASH_CHATS_KEEP", "3"))
RESERVE = 64 * 1024 * 1024  # never fill the disk completely
CHUNK = 256 * 1024
SESSION_SECS = 30 * 86400
COOKIE = "stash_session"
ID_RE = re.compile(r"[0-9a-f]{32}")
FILE_ROUTE = re.compile(r"/api/files/([0-9a-f]{32})(?:/(download|raw))?")
HASH_ROUTE = re.compile(r"/api/files/by-hash/([0-9a-fA-F]{64})")
CHAT_MSGS = re.compile(r"/api/chats/(\d{1,12})/messages")
CHAT_THUMB = re.compile(r"/api/chats/thumb/(\d{1,12})")
SHA_RE = re.compile(r"[0-9a-f]{64}")
SOURCE_RE = re.compile(r"[a-z0-9_-]{1,20}")
API_VERSION = 2  # 1 = original website API, 2 = adds API key, by-hash lookup, X-Sha256 check (chat routes, X-Source and chat media extend 2, see API.md)

INDEX_CSP = (
    "default-src 'self'; script-src 'self' 'unsafe-inline'; "
    "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
    "media-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; "
    "form-action 'self'"
)

for _ext, _type in {
    ".md": "text/markdown", ".yaml": "application/yaml", ".yml": "application/yaml",
    ".log": "text/plain", ".webp": "image/webp", ".avif": "image/avif",
    ".heic": "image/heic", ".opus": "audio/ogg", ".flac": "audio/flac",
    ".mkv": "video/x-matroska", ".ndjson": "application/x-ndjson",
    ".toml": "application/toml", ".sqlite": "application/vnd.sqlite3",
}.items():
    mimetypes.add_type(_type, _ext)

DB = None
DBLOCK = threading.Lock()
KEY = b""
API_KEY = ""
FAILS = {}  # ip -> [count, window_start]
FAIL_LOCK = threading.Lock()


# --------------------------------------------------------------------------
# setup
# --------------------------------------------------------------------------
def init():
    global DB, KEY, API_KEY
    for d in (DATA, BLOBS, TMP):
        d.mkdir(parents=True, exist_ok=True)
    chats.init(DATA, CHATS_KEEP, CHATS_MAX_BYTES)
    for leftover in TMP.glob("*"):  # unfinished uploads from a previous run
        try:
            leftover.unlink()
        except OSError:
            pass
    secret = DATA / "secret.key"
    if not secret.exists():
        secret.write_bytes(secrets.token_bytes(32))
        try:
            os.chmod(secret, 0o600)
        except OSError:
            pass
    KEY = hmac.new(secret.read_bytes(), PASSWORD.encode(), hashlib.sha256).digest()
    API_KEY = os.environ.get("STASH_API_KEY", "").strip()
    if API_KEY and len(API_KEY) < 16:
        sys.exit("STASH_API_KEY must be at least 16 characters")
    if not API_KEY and PASSWORD:
        kf = DATA / "api.key"
        if kf.exists():
            API_KEY = kf.read_text().strip()
        if len(API_KEY) < 16:
            API_KEY = secrets.token_hex(24)
            kf.write_text(API_KEY + "\n")
            try:
                os.chmod(kf, 0o600)
            except OSError:
                pass
    DB = sqlite3.connect(str(DATA / "stash.db"), check_same_thread=False)
    DB.row_factory = sqlite3.Row
    DB.execute("PRAGMA journal_mode=WAL")
    DB.execute(
        """CREATE TABLE IF NOT EXISTS files (
               id TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL,
               size INTEGER NOT NULL, sha256 TEXT NOT NULL,
               created REAL NOT NULL, modified REAL NOT NULL,
               source TEXT NOT NULL DEFAULT '')"""
    )
    if "source" not in {r[1] for r in DB.execute("PRAGMA table_info(files)")}:
        DB.execute("ALTER TABLE files ADD COLUMN source TEXT NOT NULL DEFAULT ''")  # database from before source tags
    DB.execute("CREATE INDEX IF NOT EXISTS files_sha256 ON files(sha256)")
    DB.commit()


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status, self.message = status, message


def clean_name(raw):
    name = raw.replace("\\", "/").split("/")[-1]
    name = re.sub(r"[\x00-\x1f\x7f]", "", name).strip()
    if name in ("", ".", ".."):
        name = "unnamed"
    if len(name) > 200:
        base, ext = os.path.splitext(name)
        name = base[: 200 - len(ext)] + ext
    return name


def split_ext(name):
    low = name.lower()
    for double in (".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst"):
        if low.endswith(double) and len(name) > len(double):
            return name[: -len(double)], name[-len(double):]
    return os.path.splitext(name)


def unique_name(name, exclude_id=None):
    """Caller must hold DBLOCK."""
    rows = DB.execute("SELECT name FROM files WHERE id != ?", (exclude_id or "",)).fetchall()
    taken = {r["name"] for r in rows}
    if name not in taken:
        return name
    base, ext = split_ext(name)
    n = 1
    while "%s (%d)%s" % (base, n, ext) in taken:
        n += 1
    return "%s (%d)%s" % (base, n, ext)


def guess_mime(name, client=None):
    m, _ = mimetypes.guess_type(name)
    if not m and client:
        client = client.split(";")[0].strip().lower()
        if client and client != "application/octet-stream":
            m = client
    return m or "application/octet-stream"


TEXTISH = {
    "application/json", "application/x-ndjson", "application/yaml", "application/x-yaml",
    "application/toml", "application/sql", "application/x-sh", "application/javascript",
    "application/xml", "application/xhtml+xml", "application/x-httpd-php",
}


def inline_type(mime):
    """(content-type to serve inline or None, needs_sandbox_csp)."""
    m = mime.lower()
    if m == "application/pdf" or m.startswith(("video/", "audio/")):
        return m, False
    if m == "image/svg+xml":
        return m, True
    if m.startswith("image/"):
        return m, False
    if m.startswith("text/") or m in TEXTISH or m.endswith(("+json", "+xml")):
        return "text/plain; charset=utf-8", False  # never let uploaded html/js run
    return None, False


def row_dict(r):
    return {k: r[k] for k in ("id", "name", "mime", "size", "sha256", "created", "modified", "source")}


def get_row(fid):
    with DBLOCK:
        return DB.execute("SELECT * FROM files WHERE id = ?", (fid,)).fetchone()


def vault_lookup(hexes):
    """For chats.messages(): {sha256 hex: {id, name, mime, size}} for the vault files with these hashes whose blob is
    on disk. Read-only: the chat code never touches the vault itself."""
    out = {}
    hexes = [h for h in hexes if SHA_RE.fullmatch(h)][:400]
    if not hexes:
        return out
    with DBLOCK:
        rows = DB.execute("SELECT id, name, mime, size, sha256 FROM files WHERE sha256 IN (%s)" % ",".join("?" * len(hexes)),
                          hexes).fetchall()
    for r in rows:
        if r["sha256"] not in out and (BLOBS / r["id"]).is_file():
            out[r["sha256"]] = {"id": r["id"], "name": r["name"], "mime": r["mime"], "size": r["size"]}
    return out


def make_token():
    exp = str(int(time.time()) + SESSION_SECS)
    return "%s.%s" % (exp, hmac.new(KEY, exp.encode(), hashlib.sha256).hexdigest())


def check_token(tok):
    try:
        exp, sig = tok.split(".", 1)
        good = hmac.new(KEY, exp.encode(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(sig, good) and int(exp) > time.time()
    except (ValueError, AttributeError):
        return False


def rate_limited(ip):
    with FAIL_LOCK:
        rec = FAILS.get(ip)
        if rec and time.time() - rec[1] > 300:
            FAILS.pop(ip, None)
            rec = None
        return bool(rec and rec[0] >= 10)


def note_fail(ip):
    with FAIL_LOCK:
        rec = FAILS.setdefault(ip, [0, time.time()])
        rec[0] += 1


def disk_info():
    u = shutil.disk_usage(str(DATA))
    return {"total": u.total, "free": u.free}


# --------------------------------------------------------------------------
# request handler
# --------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Stash"
    sys_version = ""
    timeout = 60  # drop stalled connections

    # ---- plumbing ----
    def log_message(self, fmt, *args):
        sys.stderr.write("%s  %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def _sec(self):
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "SAMEORIGIN")

    def _send(self, status, body=b"", ctype="application/json; charset=utf-8", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self._sec()
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, status, obj, headers=None):
        h = {"Cache-Control": "no-store"}
        h.update(headers or {})
        self._send(status, json.dumps(obj).encode("utf-8"), headers=h)

    def _guard(self, fn):
        try:
            fn()
        except ApiError as e:
            if self.command in ("POST", "PATCH"):
                self.close_connection = True  # request body may be unread
            self._json(e.status, {"error": e.message}, {"Connection": "close"} if self.close_connection else None)
        except (BrokenPipeError, ConnectionResetError, socket.timeout, TimeoutError):
            self.close_connection = True
        except Exception:
            traceback.print_exc()
            self.close_connection = True
            try:
                self._json(500, {"error": "Internal server error"})
            except Exception:
                pass

    def _authed(self, quiet=False):
        """quiet=True is for GET /api/session, which only reports the state: a wrong key there is
        not a login attempt, so it is never delayed or answered with 429 (it just says false). A wrong key is still
        counted, so the route cannot be used to guess keys without limit."""
        if not PASSWORD:
            return True
        auth = self.headers.get("Authorization", "")
        if auth[:7].lower() == "bearer ":
            return self._check_api_key(auth[7:].strip(), quiet)
        try:
            morsel = SimpleCookie(self.headers.get("Cookie", "")).get(COOKIE)
        except CookieError:
            return False
        return bool(morsel) and check_token(morsel.value)

    def _check_api_key(self, given, quiet=False):
        ip = self.client_address[0]
        limited = rate_limited(ip)
        if quiet:
            # Never delays and never answers 429, but a wrong key is still counted, so this route cannot be used to
            # guess keys faster than the others: after 10 misses the client is rate limited everywhere (and here the
            # key is no longer checked at all, so "authed" stays false until the window ends).
            if limited or not API_KEY:
                return False
            if hmac.compare_digest(given.encode("utf-8"), API_KEY.encode("utf-8")):
                return True
            note_fail(ip)
            return False
        if limited:
            raise ApiError(429, "Too many attempts. Try again in a few minutes.")
        if API_KEY and hmac.compare_digest(given.encode("utf-8"), API_KEY.encode("utf-8")):
            return True
        note_fail(ip)
        time.sleep(0.6)
        return False

    def _need_auth(self):
        if not self._authed():
            raise ApiError(401, "Login required")

    def _read_body(self, limit=65536):
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            n = -1
        if n < 0 or n > limit:
            raise ApiError(413, "Request body too large")
        return self.rfile.read(n) if n else b""

    def _read_json(self):
        try:
            data = json.loads(self._read_body().decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            raise ApiError(400, "Invalid JSON")
        if not isinstance(data, dict):
            raise ApiError(400, "Invalid JSON")
        return data

    def _cookie(self, value, max_age):
        parts = ["%s=%s" % (COOKIE, value), "Path=/", "HttpOnly", "SameSite=Strict", "Max-Age=%d" % max_age]
        if self.headers.get("X-Forwarded-Proto", "") == "https":
            parts.append("Secure")
        return "; ".join(parts)

    # ---- verbs ----
    def do_GET(self):
        self._guard(self._get)

    def do_HEAD(self):
        self._guard(self._get)

    def do_POST(self):
        self._guard(self._post)

    def do_PATCH(self):
        self._guard(self._patch)

    def do_DELETE(self):
        self._guard(self._delete)

    # ---- GET ----
    def _get(self):
        u = urlparse(self.path)
        p, qs = u.path, parse_qs(u.query, keep_blank_values=True)  # blank kept: ?source= means "untagged only"
        if p in ("/", "/index.html"):
            return self._page("index.html")
        if p in ("/chats", "/chats.html"):
            return self._page("chats.html")
        if p == "/api/session":
            return self._json(200, {"name": "stash", "api": API_VERSION, "authRequired": bool(PASSWORD),
                                    "authed": self._authed(quiet=True), "maxBytes": MAX_BYTES})
        if p == "/healthz":
            return self._json(200, {"ok": True})
        if not p.startswith("/api/"):
            raise ApiError(404, "Not found")
        self._need_auth()
        if p == "/api/files":
            return self._list(qs)
        if p == "/api/app-key":
            return self._json(200, {"key": API_KEY})
        if p == "/api/chats" or p.startswith("/api/chats/"):
            return self._chats_get(p, qs)
        m = HASH_ROUTE.fullmatch(p)
        if m:
            return self._by_hash(m.group(1).lower())
        m = FILE_ROUTE.fullmatch(p)
        if m and m.group(2):
            return self._file(m.group(1), m.group(2), qs)
        raise ApiError(404, "Not found")

    def _by_hash(self, digest):
        with DBLOCK:
            row = DB.execute("SELECT * FROM files WHERE sha256 = ? LIMIT 1", (digest,)).fetchall()
        # only count it if the bytes are really on disk (a row without a blob must be re-uploaded)
        hit = next((r for r in row if (BLOBS / r["id"]).is_file()), None)
        self._json(200, {"exists": hit is not None, "file": row_dict(hit) if hit else None})

    def _page(self, name):
        try:
            body = (BASE / name).read_bytes()
        except OSError:
            raise ApiError(500, "%s is missing next to server.py" % name)
        self._send(200, body, "text/html; charset=utf-8",
                   {"Content-Security-Policy": INDEX_CSP, "Cache-Control": "no-cache"})

    def _list(self, qs):
        with DBLOCK:
            rows = DB.execute("SELECT * FROM files ORDER BY created DESC").fetchall()
        everything = [row_dict(r) for r in rows]
        files = everything
        if "source" in qs:  # ?source=whatsapp = only that tag, ?source= = only untagged files
            want = qs["source"][0].strip().lower()
            files = [f for f in everything if f["source"] == want]
        self._json(200, {
            "files": files,
            "stats": {"count": len(everything), "bytes": sum(f["size"] for f in everything), **disk_info()},
        })

    def _chats_get(self, p, qs):
        try:
            if p == "/api/chats/status":
                return self._json(200, chats.status())
            if p == "/api/chats":
                return self._json(200, chats.overview())
            m = CHAT_THUMB.fullmatch(p)
            if m:
                data, ctype = chats.thumb(int(m.group(1)))
                return self._send(200, data, ctype, {"Cache-Control": "private, max-age=3600"})
            m = CHAT_MSGS.fullmatch(p)
            if m:
                try:
                    before = int(qs.get("before", ["0"])[0] or 0)
                    limit = int(qs.get("limit", ["100"])[0] or 100)
                except ValueError:
                    raise ApiError(400, "before and limit must be numbers")
                return self._json(200, chats.messages(int(m.group(1)), before, limit, qs.get("q", [""])[0][:100],
                                                      lookup=vault_lookup))
        except chats.ChatError as e:
            raise ApiError(e.status, e.message)
        raise ApiError(404, "Not found")

    def _file(self, fid, mode, qs):
        row = get_row(fid)
        if not row:
            raise ApiError(404, "File not found")
        path = BLOBS / fid
        try:
            size = path.stat().st_size
        except OSError:
            raise ApiError(404, "File data is missing on disk")

        ctype, disp, sandbox = row["mime"], "attachment", False
        if mode == "raw":
            if qs.get("as", [""])[0] == "text":
                ctype, disp = "text/plain; charset=utf-8", "inline"
            else:
                t, sandbox = inline_type(row["mime"])
                if t:
                    ctype, disp = t, "inline"
        fallback = re.sub(r'[^\x20-\x7e]|["\\]', "_", row["name"])
        headers = {
            "Content-Type": ctype,
            "Content-Disposition": "%s; filename=\"%s\"; filename*=UTF-8''%s" % (disp, fallback, quote(row["name"])),
            "Accept-Ranges": "bytes",
            "ETag": '"%s"' % row["sha256"][:24],
            "Cache-Control": "private, no-cache",
        }
        if sandbox:
            headers["Content-Security-Policy"] = "sandbox; default-src 'none'; style-src 'unsafe-inline'"

        rng = self.headers.get("Range")
        if not rng and self.headers.get("If-None-Match") == headers["ETag"]:
            self.send_response(304)
            self.send_header("ETag", headers["ETag"])
            self._sec()
            self.end_headers()
            return

        start, end, status = 0, size - 1, 200
        m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip()) if rng else None
        if m and (m.group(1) or m.group(2)) and size > 0:
            if m.group(1) == "":
                start, end = max(0, size - int(m.group(2))), size - 1
            else:
                start = int(m.group(1))
                end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
            if start >= size or start > end:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self._sec()
                self.end_headers()
                return
            status = 206
            headers["Content-Range"] = "bytes %d-%d/%d" % (start, end, size)

        length = end - start + 1 if size else 0
        self.send_response(status)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(length))
        self._sec()
        self.end_headers()
        if self.command == "HEAD" or not length:
            return
        with open(str(path), "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(CHUNK, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    # ---- POST ----
    def _post(self):
        p = urlparse(self.path).path
        if p == "/api/login":
            return self._login()
        if p == "/api/logout":
            return self._json(200, {"ok": True}, {"Set-Cookie": self._cookie("", 0)})
        self._need_auth()
        if p == "/api/upload":
            return self._upload()
        if p == "/api/chats/import":
            return self._chats_import()
        if p == "/api/zip":
            return self._zip()
        raise ApiError(404, "Not found")

    def _login(self):
        ip = self.client_address[0]
        if rate_limited(ip):
            raise ApiError(429, "Too many attempts. Try again in a few minutes.")
        data = self._read_json()
        if not PASSWORD:
            return self._json(200, {"ok": True})
        given = str(data.get("password", "")).encode("utf-8")
        if hmac.compare_digest(given, PASSWORD.encode("utf-8")):
            with FAIL_LOCK:
                FAILS.pop(ip, None)
            return self._json(200, {"ok": True}, {"Set-Cookie": self._cookie(make_token(), SESSION_SECS)})
        note_fail(ip)
        time.sleep(0.6)
        raise ApiError(401, "Wrong password")

    def _upload(self):
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise ApiError(411, "Content-Length header is required")
        if length < 0:
            raise ApiError(400, "Bad Content-Length")
        if length > MAX_BYTES:
            raise ApiError(413, "File is larger than the %d MB limit" % (MAX_BYTES // 1048576))
        if length + RESERVE > disk_info()["free"]:
            raise ApiError(507, "Not enough free disk space on the server")

        want = self.headers.get("X-Sha256", "").strip().lower()
        if want and not SHA_RE.fullmatch(want):
            raise ApiError(400, "X-Sha256 must be 64 hex characters")
        source = self.headers.get("X-Source", "").strip().lower()
        if source and not SOURCE_RE.fullmatch(source):
            raise ApiError(400, "X-Source must be 1-20 characters of a-z 0-9 _ -")

        name = clean_name(unquote(self.headers.get("X-Filename", "")))
        fid = uuid.uuid4().hex
        tmp = TMP / (fid + ".part")
        digest, remaining = hashlib.sha256(), length
        try:
            with open(str(tmp), "wb") as f:
                while remaining > 0:
                    chunk = self.rfile.read(min(CHUNK, remaining))
                    if not chunk:
                        raise ConnectionError("client went away mid-upload")
                    f.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
        except (ConnectionError, socket.timeout, TimeoutError):
            tmp.unlink() if tmp.exists() else None
            self.close_connection = True
            return
        except OSError:
            tmp.unlink() if tmp.exists() else None
            raise ApiError(507, "Could not write the file to disk")

        if want and digest.hexdigest() != want:
            tmp.unlink()
            raise ApiError(422, "Checksum mismatch: the upload was corrupted in transit, please retry")

        now = time.time()
        mime = guess_mime(name, self.headers.get("Content-Type"))
        with DBLOCK:
            final = unique_name(name)
            os.replace(str(tmp), str(BLOBS / fid))
            try:
                DB.execute("INSERT INTO files (id, name, mime, size, sha256, created, modified, source) VALUES (?,?,?,?,?,?,?,?)",
                           (fid, final, mime, length, digest.hexdigest(), now, now, source))
                DB.commit()
            except Exception:
                (BLOBS / fid).unlink()
                raise
            row = DB.execute("SELECT * FROM files WHERE id = ?", (fid,)).fetchone()
        self._json(201, row_dict(row))

    def _chats_import(self):
        """Raw body = a plain (already decrypted) WhatsApp msgstore.db. Same headers as /api/upload."""
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise ApiError(411, "Content-Length header is required")
        if length <= 0:
            raise ApiError(400, "Bad Content-Length")
        if length > CHATS_MAX_BYTES:
            raise ApiError(413, "The chat database is larger than the %d MB limit (STASH_CHATS_MAX_MB)" % (CHATS_MAX_BYTES // 1048576))
        if length + length // 4 + RESERVE > disk_info()["free"]:
            raise ApiError(507, "Not enough free disk space on the server")
        want = self.headers.get("X-Sha256", "").strip().lower()
        if want and not SHA_RE.fullmatch(want):
            raise ApiError(400, "X-Sha256 must be 64 hex characters")
        if not chats.IMPORTING.acquire(blocking=False):
            raise ApiError(409, "Another chat import is still running, try again later")
        try:
            tmp = TMP / (uuid.uuid4().hex + ".chat")
            try:
                digest = chats.receive(self.rfile, length, tmp)
            except (ConnectionError, socket.timeout, TimeoutError):
                self.close_connection = True
                return
            except OSError:
                raise ApiError(507, "Could not write the file to disk")
            if want and digest != want:
                chats._rm(tmp)
                raise ApiError(422, "Checksum mismatch: the upload was corrupted in transit, please retry")
            try:
                snap = chats.add_snapshot(tmp, digest)
            except chats.ChatError as e:
                raise ApiError(e.status, e.message)
            self._json(201, snap)
        finally:
            chats.IMPORTING.release()

    def _zip(self):
        form = parse_qs(self._read_body(1 << 20).decode("utf-8", "replace"))
        raw = form.get("ids", [""])[0]
        with DBLOCK:
            if raw == "all":
                rows = DB.execute("SELECT * FROM files ORDER BY name").fetchall()
            else:
                ids = [i for i in raw.split(",") if ID_RE.fullmatch(i)][:5000]
                rows = [r for r in (DB.execute("SELECT * FROM files WHERE id = ?", (i,)).fetchone() for i in ids) if r]
        if not rows:
            raise ApiError(404, "Nothing to download")
        if sum(r["size"] for r in rows) + RESERVE > disk_info()["free"]:
            raise ApiError(507, "Not enough free disk space to build the zip")
        tmp = tempfile.TemporaryFile(dir=str(TMP))
        try:
            with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
                for r in rows:
                    blob = BLOBS / r["id"]
                    if blob.exists():
                        z.write(str(blob), arcname=r["name"])
            size = tmp.seek(0, 2)
            tmp.seek(0)
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Disposition", 'attachment; filename="stash-%s.zip"' % time.strftime("%Y%m%d-%H%M%S"))
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self._sec()
            self.end_headers()
            shutil.copyfileobj(tmp, self.wfile, CHUNK)
        finally:
            tmp.close()

    # ---- PATCH / DELETE ----
    def _patch(self):
        self._need_auth()
        m = FILE_ROUTE.fullmatch(urlparse(self.path).path)
        if not m or m.group(2):
            raise ApiError(404, "Not found")
        name = clean_name(str(self._read_json().get("name", "")))
        with DBLOCK:
            if not DB.execute("SELECT 1 FROM files WHERE id = ?", (m.group(1),)).fetchone():
                raise ApiError(404, "File not found")
            DB.execute("UPDATE files SET name = ?, modified = ? WHERE id = ?",
                       (unique_name(name, m.group(1)), time.time(), m.group(1)))
            DB.commit()
            row = DB.execute("SELECT * FROM files WHERE id = ?", (m.group(1),)).fetchone()
        self._json(200, row_dict(row))

    def _delete(self):
        self._need_auth()
        m = FILE_ROUTE.fullmatch(urlparse(self.path).path)
        if not m or m.group(2):
            raise ApiError(404, "Not found")
        with DBLOCK:
            cur = DB.execute("DELETE FROM files WHERE id = ?", (m.group(1),))
            DB.commit()
        if not cur.rowcount:
            raise ApiError(404, "File not found")
        try:
            (BLOBS / m.group(1)).unlink()
        except OSError:
            pass
        self._json(200, {"ok": True})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "localhost"


def main():
    init()
    srv = Server((HOST, PORT), Handler)
    shown = lan_ip() if HOST == "0.0.0.0" else HOST
    print("Stash is running")
    print("  open      http://%s:%d" % (shown, PORT))
    print("  data in   %s" % DATA)
    print("  password  %s" % ("on" if PASSWORD else "OFF - listening on %s only" % HOST))
    if PASSWORD:
        src = "STASH_API_KEY" if os.environ.get("STASH_API_KEY") else str(DATA / "api.key")
        print("  app key   in %s (the Android app fetches it itself after you log in)" % src)
    if not PASSWORD and HOST not in ("127.0.0.1", "localhost", "::1"):
        print("  WARNING: no STASH_PASSWORD set but listening on %s - anyone who can reach it can use it." % HOST)
    signal.signal(signal.SIGTERM, lambda *a: (_ for _ in ()).throw(SystemExit(0)))
    try:
        srv.serve_forever()
    except (KeyboardInterrupt, SystemExit):
        print("\nShutting down")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
