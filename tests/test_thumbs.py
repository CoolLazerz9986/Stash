# Path: tests/test_thumbs.py
"""Previews: PUT /api/files/by-hash/<sha>/thumb, GET /api/files/<id>/thumb, the `thumb` flag in the list, cleanup on delete.
Runs the real server.py. Python 3.8+, stdlib only."""
import hashlib, json, os, shutil, subprocess, sys, tempfile, time, urllib.error, urllib.request
from pathlib import Path

SERVER = str(Path(__file__).resolve().parent.parent / "server.py")
ok = fail = 0


def check(n, c, x=""):
    global ok, fail
    if c: ok += 1; print("PASS", n)
    else: fail += 1; print("FAIL", n, x)


def req(b, m, path, body=None, h=None):
    r = urllib.request.Request(b + path, data=body, method=m, headers=h or {})
    try:
        x = urllib.request.urlopen(r, timeout=20); return x.status, x.read(), x.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers


d = tempfile.mkdtemp(prefix="stasht")
env = {k: v for k, v in os.environ.items() if not k.startswith("STASH_")}
env.update(STASH_DATA=d, STASH_PORT="18116", STASH_HOST="127.0.0.1", STASH_PASSWORD="pw-thumb-test")
p = subprocess.Popen([sys.executable, SERVER], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
b = "http://127.0.0.1:18116"
try:
    for _ in range(50):
        try: urllib.request.urlopen(b + "/healthz", timeout=1); break
        except Exception: time.sleep(0.1)
    A = {"Authorization": "Bearer " + (Path(d) / "api.key").read_text().strip()}
    data = b"\xff\xd8\xff\xe0" + b"photo" * 50
    sha = hashlib.sha256(data).hexdigest()
    st, bb, _ = req(b, "POST", "/api/upload", data, dict(A, **{"X-Filename": "a.jpg", "X-Sha256": sha, "Content-Length": str(len(data))}))
    fid = json.loads(bb)["id"]
    th = b"\xff\xd8\xff\xdb" + b"t" * 100
    P = "/api/files/by-hash/%s/thumb" % sha
    check("no preview yet: GET 404", req(b, "GET", "/api/files/%s/thumb" % fid, h=A)[0] == 404)
    check("list says thumb=false", json.loads(req(b, "GET", "/api/files", h=A)[1])["files"][0]["thumb"] is False)
    check("PUT needs auth", req(b, "PUT", P, th, {"Content-Length": str(len(th))})[0] == 401)
    check("PUT stores a JPEG -> 201", req(b, "PUT", P, th, dict(A, **{"Content-Length": str(len(th))}))[0] == 201)
    st, bb, hd = req(b, "GET", "/api/files/%s/thumb" % fid, h=A)
    check("GET returns the same bytes as image/jpeg", st == 200 and bb == th and hd["Content-Type"] == "image/jpeg", st)
    check("list says thumb=true", json.loads(req(b, "GET", "/api/files", h=A)[1])["files"][0]["thumb"] is True)
    check("not a JPEG -> 422", req(b, "PUT", P, b"GIF89a....", dict(A, **{"Content-Length": "10"}))[0] == 422)
    big = b"\xff\xd8\xff" + b"x" * (129 * 1024)
    check("over 128 KB -> 413", req(b, "PUT", P, big, dict(A, **{"Content-Length": str(len(big))}))[0] == 413)
    other = "/api/files/by-hash/%s/thumb" % ("a" * 64)
    check("unknown hash -> 404 and nothing stored", req(b, "PUT", other, th, dict(A, **{"Content-Length": str(len(th))}))[0] == 404
          and not (Path(d) / "thumbs" / ("a" * 64 + ".jpg")).exists())
    check("thumb of unknown file -> 404", req(b, "GET", "/api/files/%s/thumb" % ("b" * 32), h=A)[0] == 404)
    check("no leftovers in tmp/", not list((Path(d) / "tmp").glob("*")))
    check("delete file -> 200", req(b, "DELETE", "/api/files/" + fid, h=A)[0] == 200)
    check("preview removed with the last file", not (Path(d) / "thumbs" / (sha + ".jpg")).exists())
finally:
    p.terminate()
    try: p.wait(timeout=5)
    except Exception: p.kill()
    shutil.rmtree(d, ignore_errors=True)
print("RESULT: %d passed, %d failed" % (ok, fail))
sys.exit(1 if fail else 0)
