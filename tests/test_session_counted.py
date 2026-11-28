# Path: tests/test_session_counted.py
import json, os, subprocess, sys, tempfile, time, urllib.request, urllib.error, shutil
from pathlib import Path
SERVER = str(Path(__file__).resolve().parent.parent / "server.py")
ok = fail = 0
def check(n, c, x=""):
    global ok, fail
    if c: ok += 1; print("PASS", n)
    else: fail += 1; print("FAIL", n, x)
def start(port, **e):
    d = tempfile.mkdtemp(prefix="stashs")
    env = {k: v for k, v in os.environ.items() if not k.startswith("STASH_")}
    env.update(STASH_DATA=d, STASH_PORT=str(port), STASH_HOST="127.0.0.1", **e)
    p = subprocess.Popen([sys.executable, SERVER], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    b = "http://127.0.0.1:%d" % port
    for _ in range(50):
        try: urllib.request.urlopen(b + "/healthz", timeout=1); break
        except Exception: time.sleep(0.1)
    return p, d, b
def req(b, m, path, body=None, h=None):
    r = urllib.request.Request(b + path, data=body, method=m, headers=h or {})
    t = time.time()
    try:
        x = urllib.request.urlopen(r, timeout=10); return x.status, x.read(), x.headers, time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers, time.time() - t
def stop(p, d): p.terminate(); p.wait(timeout=5); shutil.rmtree(d, ignore_errors=True)

p, d, b = start(18111, STASH_PASSWORD="hunter2-pass")
try:
    key = (Path(d) / "api.key").read_text().strip()
    good = {"Authorization": "Bearer " + key}; bad = {"Authorization": "Bearer wrong-wrong-wrong-1"}
    t0 = time.time()
    res = [req(b, "GET", "/api/session", h=bad) for _ in range(9)]
    check("1 nine wrong keys: all 200, authed:false", all(r[0] == 200 and json.loads(r[1])["authed"] is False for r in res), [r[0] for r in res])
    check("2 ...no 0.6s delay", time.time() - t0 < 2, time.time() - t0)
    check("3 body keys unchanged", set(json.loads(res[0][1])) == {"name","api","authRequired","authed","maxBytes"})
    check("4 right key still authed:true after 9 misses", json.loads(req(b, "GET", "/api/session", h=good)[1])["authed"] is True)
    check("5 /api/files right key still 200 after 9 misses", req(b, "GET", "/api/files", h=good)[0] == 200)
    r = req(b, "GET", "/api/session", h=bad)  # 10th miss
    check("6 10th wrong key: 200, authed:false", r[0] == 200 and json.loads(r[1])["authed"] is False, r[0])
    r = req(b, "GET", "/api/session", h=good)
    check("7 now limited: right key on session -> 200, authed:false (key not checked)", r[0] == 200 and json.loads(r[1])["authed"] is False, (r[0], r[1]))
    check("8 limited: /api/files right key -> 429 (misses ARE counted now)", req(b, "GET", "/api/files", h=good)[0] == 429)
    check("9 limited: wrong key on session -> 200 not 429", req(b, "GET", "/api/session", h=bad)[0] == 200)
    check("10 limited: login blocked 429", req(b, "POST", "/api/login", json.dumps({"password":"hunter2-pass"}).encode(), {"Content-Type":"application/json"})[0] == 429)
    st, bb, h, _ = (None,)*4
finally: stop(p, d)

p, d, b = start(18112, STASH_PASSWORD="hunter2-pass", STASH_API_KEY="a"*16)
try:
    t0 = time.time(); codes = []
    for i in range(300):
        codes.append(req(b, "GET", "/api/session", h={"Authorization": "Bearer " + "b"*15 + "%x" % (i % 16)})[0])
    check("11 short key: 300 guesses all answered 200 (never 429)", set(codes) == {200}, set(codes))
    r = req(b, "GET", "/api/session", h={"Authorization": "Bearer " + "a"*16})
    check("12 short key: after 300 guesses even the RIGHT key says authed:false (guessing is capped)", json.loads(r[1])["authed"] is False)
finally: stop(p, d)

p, d, b = start(18113)
try:
    j = json.loads(req(b, "GET", "/api/session", h={"Authorization": "Bearer whatever-whatever"})[1])
    check("13 no password: authed:true", j["authed"] is True and j["authRequired"] is False)
finally: stop(p, d)
print("RESULT: %d passed, %d failed" % (ok, fail)); sys.exit(1 if fail else 0)
