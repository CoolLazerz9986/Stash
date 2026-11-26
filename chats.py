# Path: chats.py
"""
Stash chat vault: stores decrypted WhatsApp message databases (msgstore.db) and reads chats out of them.

The phone decrypts its WhatsApp backup and uploads the plain SQLite file to POST /api/chats/import (server.py).
This module checks it, adds one index, writes a small chat-list summary, and answers the read routes.
Python standard library only. Files are never loaded whole into memory; sqlite reads pages as needed.

Layout inside <data>/chats/:
    meta.json                      {"current": name, "snapshots": [{name, ts, size, sha256, chats, messages}, ...]}
    msgstore-<epoch>-<sha8>.db     one snapshot per accepted upload (a full copy of the phone's database)
    msgstore-<epoch>-<sha8>.db.json  chat list summary of that snapshot (built once, so listing chats is instant)
Only the newest snapshot is shown. Older ones are kept (STASH_CHATS_KEEP, default 3; 0 = keep all) because WhatsApp
forgets deleted messages and an older copy may still hold them.

Supports the current WhatsApp schema (tables chat, jid, message). Contact names live in a different WhatsApp
database (wa.db) that is not part of this backup, so chats show the phone number, or the group subject.
"""
import base64
import binascii
import json
import os
import sqlite3
import threading
import time
from pathlib import Path
from urllib.parse import quote

MAGIC = b"SQLite format 3\x00"
CHUNK = 256 * 1024
DIR = None
KEEP = 3
MAX_BYTES = 0
LOCK = threading.Lock()       # guards meta.json
IMPORTING = threading.Lock()  # one import at a time (indexing is heavy on a small machine)

NEED = {
    "jid": {"_id", "raw_string"},
    "chat": {"_id", "jid_row_id"},
    "message": {"_id", "chat_row_id", "from_me", "timestamp", "message_type", "text_data"},
}
LABELS = {1: "Photo", 2: "Voice message", 3: "Video", 4: "Contact", 5: "Location", 7: "System message",
          8: "Document", 9: "Document", 13: "GIF", 15: "Deleted message", 16: "Live location", 20: "Sticker"}


class ChatError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status, self.message = status, message


def init(data, keep, max_bytes):
    global DIR, KEEP, MAX_BYTES
    DIR = Path(data) / "chats"
    DIR.mkdir(parents=True, exist_ok=True)
    KEEP, MAX_BYTES = keep, max_bytes


# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------
def _open(path, write=False):
    uri = "file:%s?mode=%s" % (quote(str(path)), "rw" if write else "ro")
    return sqlite3.connect(uri, uri=True, timeout=30, isolation_level=None)


def _cols(db, table):
    return {r[1] for r in db.execute("PRAGMA table_info(%s)" % table)}


def _sec(t):
    t = t or 0
    return t // 1000 if t > 10 ** 11 else t  # WhatsApp stores milliseconds


def _meta():
    try:
        return json.loads((DIR / "meta.json").read_text())
    except (OSError, ValueError):
        return {"current": None, "snapshots": []}


def _current(m=None):
    m = m or _meta()
    return next((s for s in m["snapshots"] if s["name"] == m["current"]), None)


def _forget(snap):
    for suffix in ("", ".json"):
        try:
            (DIR / (snap["name"] + suffix)).unlink()
        except OSError:
            pass


def _preview(kind_no, text):
    label = LABELS.get(kind_no)
    text = text or ""
    if kind_no in (0, None) or (label is None and text):
        return text
    return ("[%s] %s" % (label or "Message", text)).strip()


def _chat_name(raw, subject):
    user, _, server = (raw or "").partition("@")
    if subject:
        return subject
    if server == "g.us":
        return "Group"
    return "+" + user if user.isdigit() else (user or raw or "?")


# --------------------------------------------------------------------------
# import
# --------------------------------------------------------------------------
def receive(rfile, length, tmp):
    """Stream [length] bytes of the request body into [tmp]; returns the SHA-256 hex digest."""
    import hashlib
    digest, remaining = hashlib.sha256(), length
    try:
        with open(str(tmp), "wb") as f:
            while remaining > 0:
                chunk = rfile.read(min(CHUNK, remaining))
                if not chunk:
                    raise ConnectionError("client went away mid-upload")
                f.write(chunk)
                digest.update(chunk)
                remaining -= len(chunk)
    except BaseException:
        _rm(tmp)
        raise
    return digest.hexdigest()


def _rm(path):
    try:
        Path(path).unlink()
    except OSError:
        pass


def _summary(db):
    subject = "c.subject" if "subject" in _cols(db, "chat") else "NULL"
    rows = db.execute(
        "SELECT c._id, j.raw_string, %s, s.n, s.last_id FROM chat c JOIN jid j ON j._id = c.jid_row_id "
        "JOIN (SELECT chat_row_id AS cid, COUNT(*) AS n, MAX(_id) AS last_id FROM message GROUP BY chat_row_id) s "
        "ON s.cid = c._id" % subject).fetchall()
    out = []
    for cid, raw, subj, n, last_id in rows:
        if (raw or "").startswith("status@") or not n:
            continue
        m = db.execute("SELECT from_me, timestamp, message_type, text_data FROM message WHERE _id = ?", (last_id,)).fetchone()
        out.append({
            "id": cid, "name": _chat_name(raw, subj), "jid": raw, "group": (raw or "").endswith("@g.us"), "count": n,
            "last": {"ts": _sec(m[1]), "me": bool(m[0]), "text": _preview(m[2], m[3])[:140]} if m else None,
        })
    out.sort(key=lambda c: c["last"]["ts"] if c["last"] else 0, reverse=True)
    return out


def add_snapshot(tmp, digest):
    """[tmp] is a finished upload (hash already checked by the caller). Validates, indexes, stores. Removes [tmp] on failure."""
    tmp = Path(tmp)
    try:
        with open(str(tmp), "rb") as f:
            if f.read(16) != MAGIC:
                raise ChatError(422, "That is not an SQLite database (wrong key, or not a WhatsApp backup)")
        cur = _current()
        if cur and cur["sha256"] == digest:
            _rm(tmp)
            return dict(cur, duplicate=True)
        try:
            db = _open(tmp, True)
            try:
                for table, need in NEED.items():
                    if not need <= _cols(db, table):
                        raise ChatError(422, "That database is not in the WhatsApp message format Stash understands (needs tables chat, jid, message)")
                db.execute("PRAGMA journal_mode=DELETE")  # a WAL-mode copy could not be opened read-only later
                db.execute("PRAGMA cache_size=-8192")     # 8 MB: gentle on a 2 GB machine
                db.execute("CREATE INDEX IF NOT EXISTS stash_msg_chat ON message(chat_row_id, _id)")
                chats = _summary(db)
            finally:
                db.close()
        except sqlite3.DatabaseError as e:
            raise ChatError(422, "The database could not be read: %s" % e)
        ts = int(time.time())
        name = "msgstore-%d-%s.db" % (ts, digest[:8])
        (DIR / (name + ".json")).write_text(json.dumps(chats))
        os.replace(str(tmp), str(DIR / name))
    except BaseException:
        _rm(tmp)
        raise
    snap = {"name": name, "ts": ts, "size": (DIR / name).stat().st_size, "sha256": digest,
            "chats": len(chats), "messages": sum(c["count"] for c in chats)}
    with LOCK:
        m = _meta()
        m["snapshots"].append(snap)
        m["current"] = name
        drop = []
        if KEEP > 0 and len(m["snapshots"]) > KEEP:
            drop, m["snapshots"] = m["snapshots"][:-KEEP], m["snapshots"][-KEEP:]
        t = DIR / "meta.json.tmp"
        t.write_text(json.dumps(m))
        os.replace(str(t), str(DIR / "meta.json"))
    for s in drop:
        _forget(s)
    return snap


# --------------------------------------------------------------------------
# read
# --------------------------------------------------------------------------
def status():
    return {"snapshot": _current(), "maxBytes": MAX_BYTES, "keep": KEEP}


def overview():
    cur = _current()
    if not cur:
        return {"snapshot": None, "chats": []}
    try:
        chats = json.loads((DIR / (cur["name"] + ".json")).read_text())
    except (OSError, ValueError):
        chats = []
    return {"snapshot": cur, "chats": chats}


def _tables(db):
    return {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}


def _hex_hash(v):
    """WhatsApp stores a media file's SHA-256 as base64 text (some versions: raw bytes). Returns 64 hex chars or None."""
    try:
        raw = bytes(v) if isinstance(v, (bytes, bytearray, memoryview)) else base64.b64decode((v or "").strip(), validate=True)
    except (binascii.Error, ValueError, TypeError):
        return None
    return raw.hex() if len(raw) == 32 else None


def _media(db, ids):
    """{message id: {"hash": hex|None, "mime", "name", "thumb": bool}} for the media messages among [ids]. Thumbnails are
    only checked for, never read here (the picture itself is sent by thumb())."""
    out = {}
    if not ids:
        return out
    tables = _tables(db)
    marks = ",".join("?" * len(ids))
    if "message_media" in tables:
        cols = _cols(db, "message_media")
        if {"message_row_id", "file_path", "file_hash"} <= cols:
            mime = "mime_type" if "mime_type" in cols else "NULL"
            for mid, path, h, mt in db.execute(
                    "SELECT message_row_id, file_path, file_hash, %s FROM message_media WHERE message_row_id IN (%s)" % (mime, marks), ids):
                out[mid] = {"hash": _hex_hash(h) if h else None, "mime": mt or "",
                            "name": (path or "").replace("\\", "/").rpartition("/")[2], "thumb": False}
    if "message_thumbnail" in tables and "message_row_id" in _cols(db, "message_thumbnail"):
        for (mid,) in db.execute("SELECT message_row_id FROM message_thumbnail WHERE message_row_id IN (%s)" % marks, ids):
            out.setdefault(mid, {"hash": None, "mime": "", "name": "", "thumb": False})["thumb"] = True
    return out


def thumb(message_id):
    """The small preview picture WhatsApp keeps for a media message: (bytes, content type). ChatError 404 if there is none."""
    cur = _current()
    if not cur:
        raise ChatError(404, "No chats have been backed up yet")
    try:
        db = _open(DIR / cur["name"])
    except sqlite3.Error:
        raise ChatError(404, "The chat database is missing on the server")
    try:
        if "message_thumbnail" not in _tables(db):
            raise ChatError(404, "No preview")
        row = db.execute("SELECT thumbnail FROM message_thumbnail WHERE message_row_id = ?", (message_id,)).fetchone()
    except sqlite3.Error as e:
        raise ChatError(500, "Could not read the chat database: %s" % e)
    finally:
        db.close()
    data = bytes(row[0]) if row and row[0] else b""
    if data[:3] == b"\xff\xd8\xff":
        return data, "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return data, "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return data, "image/webp"
    raise ChatError(404, "No preview")


def messages(chat_id, before=0, limit=100, q="", lookup=None):
    """Newest-first paging, returned oldest-first. [before] = only messages with a smaller id. [q] = text contains.
    [lookup] (server.py vault_lookup) = function(list of sha256 hex) -> {hex: {id, name, mime, size}} for files the vault
    really holds. A media message gets "media" = {file?, thumb?, mime, name}; "file" is set only when the vault holds a file
    whose SHA-256 equals the hash WhatsApp recorded for THAT message, so a message can never show somebody else's file."""
    cur = _current()
    if not cur:
        raise ChatError(404, "No chats have been backed up yet")
    limit = max(1, min(int(limit), 200))
    try:
        db = _open(DIR / cur["name"])
    except sqlite3.Error:
        raise ChatError(404, "The chat database is missing on the server")
    try:
        if not db.execute("SELECT 1 FROM chat WHERE _id = ?", (chat_id,)).fetchone():
            raise ChatError(404, "Chat not found")
        sender = "j.raw_string" if "sender_jid_row_id" in _cols(db, "message") else "NULL"
        join = "LEFT JOIN jid j ON j._id = m.sender_jid_row_id" if sender != "NULL" else ""
        sql = ("SELECT m._id, m.from_me, m.timestamp, m.message_type, m.text_data, %s FROM message m %s "
               "WHERE m.chat_row_id = ?" % (sender, join))
        args = [chat_id]
        if before:
            sql += " AND m._id < ?"
            args.append(before)
        if q:
            sql += " AND m.text_data LIKE ? ESCAPE '\\'"
            args.append("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        rows = db.execute(sql + " ORDER BY m._id DESC LIMIT ?", args + [limit + 1]).fetchall()
        media = _media(db, [r[0] for r in rows[:limit] if r[3] not in (0, None)])
    except sqlite3.Error as e:
        raise ChatError(500, "Could not read the chat database: %s" % e)
    finally:
        db.close()
    more = len(rows) > limit
    found = lookup([v["hash"] for v in media.values() if v["hash"]]) if lookup and media else {}
    out = []
    for mid, me, ts, kind, text, raw in reversed(rows[:limit]):
        item = {"id": mid, "me": bool(me), "ts": _sec(ts), "text": text or ""}
        if kind not in (0, None):
            item["label"] = LABELS.get(kind, "Message")
        if raw and not me:
            item["sender"] = raw.partition("@")[0]
        md = media.get(mid)
        if md and (md["hash"] or md["thumb"]):
            item["media"] = {"mime": md["mime"], "name": md["name"], "thumb": md["thumb"]}
            if md["hash"] and md["hash"] in found:
                item["media"]["file"] = found[md["hash"]]
        out.append(item)
    return {"messages": out, "more": more}