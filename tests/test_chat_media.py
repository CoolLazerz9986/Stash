# Path: tests/test_chat_media.py
"""WhatsApp chat media: a message only shows the vault file whose SHA-256 equals the hash WhatsApp recorded for it,
files uploaded with X-Source: whatsapp are tagged and filterable, previews come from message_thumbnail.
Runs the real server.py on a synthetic msgstore.db. Python 3.8+, stdlib only."""
import base64, hashlib, json, os, shutil, sqlite3, subprocess, sys, tempfile, time, urllib.error, urllib.request
from pathlib import Path

SERVER = str(Path(__file__).resolve().parent.parent / "server.py")
ok = fail = 0


def check(n, c, x=""):
    global ok, fail
    if c: ok += 1; print("PASS", n)
    else: fail += 1; print("FAIL", n, x)


def start(port, **e):
    d = tempfile.mkdtemp(prefix="stashm")
    env = {k: v for k, v in os.environ.items() if not k.startswith("STASH_")}
    env.update(STASH_DATA=d, STASH_PORT=str(port), STASH_HOST="127.0.0.1", STASH_PASSWORD="pw-media-test", **e)
    p = subprocess.Popen([sys.executable, SERVER], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    b = "http://127.0.0.1:%d" % port
    for _ in range(50):
        try: urllib.request.urlopen(b + "/healthz", timeout=1); break
        except Exception: time.sleep(0.1)
    return p, d, b


def req(b, m, path, body=None, h=None):
    r = urllib.request.Request(b + path, data=body, method=m, headers=h or {})
    try:
        x = urllib.request.urlopen(r, timeout=20); return x.status, x.read(), x.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers


PIC = b"\xff\xd8\xff\xe0" + b"picture-bytes-A" * 20      # the real photo of message 1
OTHER = b"\xff\xd8\xff\xe0" + b"some-other-photo" * 20    # a different file that happens to be in the vault
THUMB = b"\xff\xd8\xff\xdb" + b"tiny" * 10
sha = lambda b: hashlib.sha256(b).hexdigest()


def make_db(path):
    db = sqlite3.connect(path)
    db.executescript("""
      CREATE TABLE jid (_id INTEGER PRIMARY KEY, raw_string TEXT);
      CREATE TABLE chat (_id INTEGER PRIMARY KEY, jid_row_id INTEGER, subject TEXT);
      CREATE TABLE message (_id INTEGER PRIMARY KEY, chat_row_id INTEGER, from_me INTEGER, timestamp INTEGER,
                            message_type INTEGER, text_data TEXT, sender_jid_row_id INTEGER);
      CREATE TABLE message_media (message_row_id INTEGER, file_path TEXT, file_hash TEXT, mime_type TEXT, file_size INTEGER);
      CREATE TABLE message_thumbnail (message_row_id INTEGER, thumbnail BLOB);
      INSERT INTO jid VALUES (1,'6281234@s.whatsapp.net');
      INSERT INTO chat VALUES (10,1,NULL);
      INSERT INTO message VALUES (1,10,0,1700000001000,1,NULL,1);   -- photo, file is in the vault
      INSERT INTO message VALUES (2,10,0,1700000002000,1,NULL,1);   -- photo, file NOT in the vault, has a preview
      INSERT INTO message VALUES (3,10,0,1700000003000,1,NULL,1);   -- photo whose recorded hash matches nothing
      INSERT INTO message VALUES (4,10,1,1700000004000,0,'plain text',NULL);
    """)
    b64 = lambda data: base64.b64encode(hashlib.sha256(data).digest()).decode()
    db.execute("INSERT INTO message_media VALUES (1,'Media/WhatsApp Images/IMG-1.jpg',?,?,?)", (b64(PIC), "image/jpeg", len(PIC)))
    db.execute("INSERT INTO message_media VALUES (2,'Media/WhatsApp Images/IMG-2.jpg',?,?,?)", (b64(b"never uploaded"), "image/jpeg", 10))
    db.execute("INSERT INTO message_media VALUES (3,'Media/WhatsApp Images/IMG-3.jpg',?,?,?)", (b64(b"edited since"), "image/jpeg", 10))
    db.execute("INSERT INTO message_thumbnail VALUES (2,?)", (THUMB,))
    db.commit(); db.close()


p, d, b = start(18115)
tmpd = tempfile.mkdtemp(prefix="stashmdb")
try:
    A = {"Authorization": "Bearer " + (Path(d) / "api.key").read_text().strip()}
    db = os.path.join(tmpd, "a.db"); make_db(db); raw = open(db, "rb").read()
    check("chat import ok", req(b, "POST", "/api/chats/import", raw, dict(A, **{"Content-Length": str(len(raw))}))[0] == 201)

    def up(data, name, source=None):
        h = dict(A, **{"X-Filename": name, "X-Sha256": sha(data), "Content-Length": str(len(data))})
        if source is not None: h["X-Source"] = source
        return req(b, "POST", "/api/upload", data, h)

    def msgs():
        return {m["id"]: m for m in json.loads(req(b, "GET", "/api/chats/10/messages", h=A)[1])["messages"]}

    m = msgs()
    check("before any upload: message 1 has media info but no file", "media" in m[1] and "file" not in m[1]["media"], m[1])
    check("text message has no media key", "media" not in m[4])

    st, bb, _ = up(PIC, "IMG-1.jpg", "whatsapp")
    f = json.loads(bb)
    check("upload with X-Source -> 201 and tagged", st == 201 and f["source"] == "whatsapp", bb)
    check("untagged upload has empty source", json.loads(up(OTHER, "other.jpg")[1])["source"] == "")
    check("bad X-Source -> 400", up(b"zzz", "z.bin", "Bad Tag!")[0] == 400)

    m = msgs()
    check("message 1 now links its own file by hash", m[1]["media"].get("file", {}).get("id") == f["id"], m[1])
    check("linked file is served", req(b, "GET", "/api/files/%s/raw" % f["id"], h=A)[1] == PIC)
    check("message 2 (file not on server) has no file but a preview", "file" not in m[2]["media"] and m[2]["media"]["thumb"] is True, m[2])
    check("message 3 (hash matches nothing) does not borrow another file", "file" not in m[3]["media"], m[3])
    check("untagged OTHER file is never linked to any message", all(x.get("media", {}).get("file", {}).get("name") != "other.jpg" for x in m.values()))

    st, bb, hd = req(b, "GET", "/api/chats/thumb/2", h=A)
    check("preview served as an image", st == 200 and bb == THUMB and hd["Content-Type"] == "image/jpeg")
    check("no preview -> 404", req(b, "GET", "/api/chats/thumb/1", h=A)[0] == 404)
    check("preview needs auth", req(b, "GET", "/api/chats/thumb/2")[0] == 401)

    allf = json.loads(req(b, "GET", "/api/files", h=A)[1])
    wa = json.loads(req(b, "GET", "/api/files?source=whatsapp", h=A)[1])
    plain = json.loads(req(b, "GET", "/api/files?source=", h=A)[1])
    check("?source=whatsapp lists only tagged files", [x["id"] for x in wa["files"]] == [f["id"]], wa)
    check("?source= lists only untagged files", all(x["source"] == "" for x in plain["files"]) and len(plain["files"]) == 1)
    check("no filter lists everything, stats count everything", len(allf["files"]) == 2 and wa["stats"]["count"] == 2)

    # the same bytes uploaded again are still a duplicate-by-hash (the tag never changes matching)
    check("by-hash still finds the tagged file", json.loads(req(b, "GET", "/api/files/by-hash/" + sha(PIC), h=A)[1])["exists"] is True)
finally:
    p.terminate()
    try: p.wait(timeout=5)
    except Exception: p.kill()
    shutil.rmtree(d, ignore_errors=True); shutil.rmtree(tmpd, ignore_errors=True)

print("RESULT: %d passed, %d failed" % (ok, fail))
sys.exit(1 if fail else 0)
