#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitHub Actions 助手：
  1) 取消多余的排队构建（Contents API 每传一个文件就产生一次 push 事件）
  2) 等待指定 run 结束
  3) 下载 APK artifact

用法：
  GITHUB_TOKEN=ghp_xxx python ci.py wait   <run_id>
  GITHUB_TOKEN=ghp_xxx python ci.py cancel-stale
  GITHUB_TOKEN=ghp_xxx python ci.py apk    <run_id> <out_dir>
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile

OWNER = "QualityLee"
REPO = "router-monitor"
API = "https://api.github.com"
TOKEN = os.environ.get("GITHUB_TOKEN", "").strip()


def req(method, path_or_url, raw=False):
    url = path_or_url if path_or_url.startswith("http") else API + path_or_url
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", f"token {TOKEN}")
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "workbuddy-ci")
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            data = resp.read()
            return resp.status, (data if raw else json.loads(data or b"{}"))
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body or b"{}")
        except Exception:
            return e.code, body
    except Exception as e:
        return 0, str(e)


def runs(limit=60):
    st, d = req("GET", f"/repos/{OWNER}/{REPO}/actions/runs?per_page={limit}")
    if st != 200:
        print("list runs failed:", st, d)
        sys.exit(1)
    return d.get("workflow_runs", [])


def cancel_stale():
    rs = runs()
    if not rs:
        print("no runs")
        return
    newest = rs[0]
    keep_sha = newest["head_sha"]
    print(f"keep run {newest['id']} ({keep_sha[:8]})")
    n = 0
    for r in rs[1:]:
        if r["head_sha"] == keep_sha:
            continue
        if r["status"] in ("queued", "in_progress", "requested", "waiting", "pending"):
            st, _ = req("POST", f"/repos/{OWNER}/{REPO}/actions/runs/{r['id']}/cancel")
            print(f"  cancel {r['id']} ({r['head_sha'][:8]}) -> {st}")
            n += 1
    print(f"cancelled {n}")


def wait(run_id, timeout_min=45):
    t0 = time.time()
    last = None
    while True:
        st, d = req("GET", f"/repos/{OWNER}/{REPO}/actions/runs/{run_id}")
        if st != 200:
            print("poll failed:", st, d)
            time.sleep(10)
            continue
        s = f"{d['status']}/{d.get('conclusion')}"
        if s != last:
            print(f"[{int(time.time() - t0)}s] {s}")
            last = s
        if d["status"] == "completed":
            print("conclusion:", d.get("conclusion"))
            print("url:", d.get("html_url"))
            return d.get("conclusion")
        if time.time() - t0 > timeout_min * 60:
            print("timeout waiting")
            return "timeout"
        time.sleep(15)


def jobs(run_id):
    st, d = req("GET", f"/repos/{OWNER}/{REPO}/actions/runs/{run_id}/jobs")
    for j in d.get("jobs", []):
        print(f"job {j['name']}: {j['status']}/{j.get('conclusion')}")
        for s in j.get("steps", []):
            mark = "OK " if s.get("conclusion") == "success" else "!! "
            print(f"   {mark}{s['name']}: {s.get('conclusion')}")


def download_apk(run_id, out_dir):
    st, d = req("GET", f"/repos/{OWNER}/{REPO}/actions/runs/{run_id}/artifacts")
    arts = d.get("artifacts", [])
    if not arts:
        print("no artifacts:", d)
        return None
    art = arts[0]
    print("artifact:", art["name"], art["size_in_bytes"], "bytes")
    url = f"{API}/repos/{OWNER}/{REPO}/actions/artifacts/{art['id']}/zip"
    opener = urllib.request.build_opener(NoRedirect())
    r = urllib.request.Request(url)
    r.add_header("Authorization", f"token {TOKEN}")
    r.add_header("User-Agent", "workbuddy-ci")
    try:
        resp = opener.open(r, timeout=180)
        data = resp.read()
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            loc = e.headers.get("Location")
            print("following redirect -> signed URL")
            r2 = urllib.request.Request(loc)
            r2.add_header("User-Agent", "workbuddy-ci")
            with urllib.request.urlopen(r2, timeout=600) as resp2:
                data = resp2.read()
        else:
            print("download failed:", e.code, e.read()[:300])
            return None
    os.makedirs(out_dir, exist_ok=True)
    zip_path = os.path.join(out_dir, "_artifact.zip")
    with open(zip_path, "wb") as f:
        f.write(data)
    print("zip bytes:", len(data))
    apk_path = None
    with zipfile.ZipFile(zip_path) as z:
        for n in z.namelist():
            print("  in zip:", n)
            if n.lower().endswith(".apk"):
                apk_path = os.path.join(out_dir, "RouterMonitor-debug.apk")
                with open(apk_path, "wb") as f:
                    f.write(z.read(n))
    try:
        os.remove(zip_path)
    except Exception:
        pass
    if apk_path:
        print("APK =>", apk_path, os.path.getsize(apk_path), "bytes")
    return apk_path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def main():
    if not TOKEN:
        print("set GITHUB_TOKEN")
        return 1
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "cancel-stale":
        cancel_stale()
    elif cmd == "wait":
        c = wait(int(sys.argv[2]))
        jobs(int(sys.argv[2]))
        return 0 if c == "success" else 1
    elif cmd == "apk":
        p = download_apk(int(sys.argv[2]), sys.argv[3])
        return 0 if p else 1
    elif cmd == "jobs":
        jobs(int(sys.argv[2]))
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
