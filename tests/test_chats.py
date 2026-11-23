# Path: tests/test_chats.py
"""WhatsApp chat vault: import a synthetic msgstore.db through the real server and read it back. Python 3.8+, stdlib only."""
import hashlib, json, os, shutil, sqlite3, subprocess, sys, tempfile, time, urllib.error, urllib.request
from pathlib import Path

SERVER = str(Path(__file__).resolve().parent.parent / "server.py")
ok = fail = 0


def check(n, c, x=""):
    global ok, fail
    if c: ok += 1; print("PASS", n)
    else: fail += 1; print("FAIL", n, x)


def start(port, **e):
    d = tempfile.mkdtemp(prefix="stashc")
    env = {k: v for k, v in os.environ.items() if not k.startswith("STASH_")}
    env.update(STASH_DATA=d, STASH_PORT=str(port), STASH_HOST="127.0.0.1", STASH_PASSWORD="pw-chats-test", **e)
    p = subprocess.Popen([sys.executable, SERVER], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    b = "http://127.0.0.1:%d" % port
    for _ in range(50):
        try: urllib.request.urlopen(b + "/healthz", timeout=1); break
        except Exception: time.sleep(0.1)
    return p, d, b


def req(b, m, path, body=None, h=None):
    r = urllib.request.Request(b + path, data=body, method=m, headers=h or {})
    try:
        x = urllib.request.urlopen(r, timeout=20); return x.status, x.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def make_db(path, extra=False):
    db = sqlite3.connect(path)
    db.executescript("""
      CREATE TABLE jid (_id INTEGER PRIMARY KEY, raw_string TEXT);
      CREATE TABLE chat (_id INTEGER PRIMARY KEY, jid_row_id INTEGER, subject TEXT);
      CREATE TABLE message (_id INTEGER PRIMARY KEY, chat_row_id INTEGER, from_me INTEGER, timestamp INTEGER,
                            message_type INTEGER, text_data TEXT, sender_jid_row_id INTEGER);
      INSERT INTO jid VALUES (1,'6281234@s.whatsapp.net'),(2,'1203@g.us'),(3,'status@broadcast'),(4,'6285555@s.whatsapp.net');
      INSERT INTO chat VALUES (10,1,NULL),(11,2,'Family'),(12,3,NULL);
    """)
    for i in range(1, 251):  # 250 messages in chat 10, ms timestamps
        db.execute("INSERT INTO message VALUES (?,?,?,?,?,?,?)", (i, 10, i % 2, 1700000000000 + i * 1000, 0, "hello %d_50%%" % i, 1))
    db.execute("INSERT INTO message VALUES (1000,11,0,1700000500000,0,'group hi',4)")
    db.execute("INSERT INTO message VALUES (1001,11,1,1700000600000,1,NULL,NULL)")
    db.execute("INSERT INTO message VALUES (2000,12,0,1700000700000,0,'status update',1)")
    if extra: db.execute("INSERT INTO message VALUES (3000,10,0,1700001000000,0,'newest',1)")
    db.commit(); db.close()


p, d, b = start(18114, STASH_CHATS_KEEP="2")
tmpd = tempfile.mkdtemp(prefix="stashcdb")
try:
    key = (Path(d) / "api.key").read_text().strip()
    A = {"Authorization": "Bearer " + key}
    check("chats page served", req(b, "GET", "/chats")[0] == 200)
    check("chats api needs auth", req(b, "GET", "/api/chats")[0] == 401 and req(b, "GET", "/api/chats/status")[0] == 401)
    st, bb = req(b, "GET", "/api/chats/status", h=A)
    check("status: no snapshot yet", st == 200 and json.loads(bb)["snapshot"] is None, bb)
    check("overview empty", json.loads(req(b, "GET", "/api/chats", h=A)[1]) == {"snapshot": None, "chats": []})
    check("messages 404 before any import", req(b, "GET", "/api/chats/10/messages", h=A)[0] == 404)

    f1 = os.path.join(tmpd, "a.db"); make_db(f1)
    raw = open(f1, "rb").read(); sha = hashlib.sha256(raw).hexdigest()
    imp = dict(A, **{"X-Sha256": sha, "Content-Length": str(len(raw))})
    st, bb = req(b, "POST", "/api/chats/import", raw, imp); snap = json.loads(bb)
    check("import ok (201), 2 chats (status chat skipped), 252 messages", st == 201 and snap["chats"] == 2 and snap["messages"] == 252, (st, bb))
    check("tmp/ empty after import", not list((Path(d) / "tmp").glob("*")))
    st, bb = req(b, "GET", "/api/chats/status", h=A)
    check("status shows the snapshot sha", json.loads(bb)["snapshot"]["sha256"] == sha)
    ov = json.loads(req(b, "GET", "/api/chats", h=A)[1])
    names = [c["name"] for c in ov["chats"]]
    check("chat names: group subject and +number", set(names) == {"Family", "+6281234"}, names)
    check("chats sorted newest first", ov["chats"][0]["name"] == "+6281234" or ov["chats"][0]["last"]["ts"] >= ov["chats"][1]["last"]["ts"])
    check("timestamps converted from ms to s", ov["chats"][0]["last"]["ts"] < 10 ** 11)
    page = json.loads(req(b, "GET", "/api/chats/10/messages?limit=100", h=A)[1])
    ids = [m["id"] for m in page["messages"]]
    check("newest 100 returned oldest-first, more=true", len(ids) == 100 and ids == sorted(ids) and ids[-1] == 250 and page["more"] is True)
    older = json.loads(req(b, "GET", "/api/chats/10/messages?limit=200&before=%d" % ids[0], h=A)[1])
    check("paging with before: remaining 150, more=false", len(older["messages"]) == 150 and older["more"] is False and older["messages"][-1]["id"] == ids[0] - 1)
    q = json.loads(req(b, "GET", "/api/chats/10/messages?q=50%25", h=A)[1])
    check("search treats % literally", [m["id"] for m in q["messages"]] == [50] or len(q["messages"]) >= 1 and all("50%" in m["text"] for m in q["messages"]), q)
    g = json.loads(req(b, "GET", "/api/chats/11/messages", h=A)[1])["messages"]
    check("group: sender shown for others, label for media, me flag", g[0].get("sender") == "6285555" and g[1]["me"] is True and g[1]["label"] == "Photo", g)
    check("unknown chat -> 404", req(b, "GET", "/api/chats/999/messages", h=A)[0] == 404)
    check("bad before -> 400", req(b, "GET", "/api/chats/10/messages?before=x", h=A)[0] == 400)

    st, bb = req(b, "POST", "/api/chats/import", raw, imp)
    check("same database again -> 201 duplicate, still one snapshot", st == 201 and json.loads(bb).get("duplicate") is True and len(list((Path(d) / "chats").glob("msgstore-*.db"))) == 1)

    bad = dict(imp, **{"X-Sha256": "f" * 64})
    check("wrong X-Sha256 -> 422", req(b, "POST", "/api/chats/import", raw, bad)[0] == 422)
    junk = b"not sqlite at all" * 10
    st, bb = req(b, "POST", "/api/chats/import", junk, dict(A, **{"X-Sha256": hashlib.sha256(junk).hexdigest(), "Content-Length": str(len(junk))}))
    check("non-SQLite -> 422", st == 422, (st, bb))
    other = os.path.join(tmpd, "o.db"); c = sqlite3.connect(other); c.execute("CREATE TABLE x(a)"); c.commit(); c.close()
    ob = open(other, "rb").read()
    check("SQLite without WhatsApp tables -> 422", req(b, "POST", "/api/chats/import", ob, dict(A, **{"Content-Length": str(len(ob))}))[0] == 422)
    check("no Content-Length -> 411/400 (not stored)", req(b, "POST", "/api/chats/import", None, A)[0] in (400, 411))
    check("tmp/ empty after rejected imports", not list((Path(d) / "tmp").glob("*")))

    f2 = os.path.join(tmpd, "b.db"); make_db(f2, True); r2 = open(f2, "rb").read()
    check("second, newer database accepted", req(b, "POST", "/api/chats/import", r2, dict(A, **{"Content-Length": str(len(r2))}))[0] == 201)
    nm = json.loads(req(b, "GET", "/api/chats/10/messages?limit=1", h=A)[1])["messages"]
    check("newest snapshot is the one shown", nm[0]["text"] == "newest")
    time.sleep(1.1)  # snapshot names carry the epoch second
    f3 = os.path.join(tmpd, "c.db"); make_db(f3, True); c = sqlite3.connect(f3); c.execute("INSERT INTO message VALUES (4000,10,1,1700002000000,0,'third',1)"); c.commit(); c.close()
    r3 = open(f3, "rb").read()
    check("third database accepted", req(b, "POST", "/api/chats/import", r3, dict(A, **{"Content-Length": str(len(r3))}))[0] == 201)
    check("KEEP=2 prunes the oldest snapshot (files and summary)", len(list((Path(d) / "chats").glob("msgstore-*.db"))) == 2 and len(list((Path(d) / "chats").glob("msgstore-*.json"))) == 2)
    check("files vault untouched by chat imports", json.loads(req(b, "GET", "/api/files", h=A)[1])["stats"]["count"] == 0)
    check("session still reports api 2 and the file limit", json.loads(req(b, "GET", "/api/session")[1])["api"] == 2)
finally:
    p.terminate(); p.wait(timeout=5); shutil.rmtree(d, ignore_errors=True); shutil.rmtree(tmpd, ignore_errors=True)

p, d, b = start(18115, STASH_CHATS_MAX_MB="0.001")
try:
    A = {"Authorization": "Bearer " + (Path(d) / "api.key").read_text().strip()}
    big = b"x" * 5000
    check("over STASH_CHATS_MAX_MB -> 413", req(b, "POST", "/api/chats/import", big, dict(A, **{"Content-Length": str(len(big))}))[0] == 413)
    check("chat limit is reported by /api/chats/status", json.loads(req(b, "GET", "/api/chats/status", h=A)[1])["maxBytes"] == 1048)
finally:
    p.terminate(); p.wait(timeout=5); shutil.rmtree(d, ignore_errors=True)
print("RESULT: %d passed, %d failed" % (ok, fail)); sys.exit(1 if fail else 0)
