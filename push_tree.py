#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一次性把整个工程推成一个 commit（Git Data API）。
比 push_via_api.py 好在：Contents API 每传一个文件 = 一次 push = 一次 Actions 构建，
20 个文件就是 20 次构建。这里用 blobs -> tree -> commit -> update-ref，
只产生 1 个 commit，因此只触发 1 次构建。

用法：
    GITHUB_TOKEN=ghp_xxx python push_tree.py "commit message"
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

OWNER = "QualityLee"
REPO = "router-monitor"
BRANCH = "main"
API = "https://api.github.com"
ROOT = os.path.dirname(os.path.abspath(__file__))

FILES = [
    ".github/workflows/build.yml",
    ".gitignore",
    "README.md",
    "_test_parser.py",
    "_lint_kt.py",
    "push_tree.py",
    "ci.py",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "build.ps1",
    "build.bat",
    "push-to-github.ps1",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/workbuddy/routermon/MainActivity.kt",
    "app/src/main/java/com/workbuddy/routermon/RouterClient.kt",
    "app/src/main/java/com/workbuddy/routermon/SignalInfo.kt",
    "app/src/main/java/com/workbuddy/routermon/HtmlParser.kt",
    "app/src/main/java/com/workbuddy/routermon/WebViewActivity.kt",
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout/activity_webview.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/xml/network_security_config.xml",
]

TOKEN = os.environ.get("GITHUB_TOKEN", "").strip()


def req(method, path, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    r = urllib.request.Request(API + path, data=data, method=method)
    r.add_header("Authorization", f"token {TOKEN}")
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "workbuddy-push")
    if data:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, (json.loads(body) if body else {})
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            body = json.loads(body)
        except Exception:
            pass
        return e.code, body
    except Exception as e:
        return 0, str(e)


def main():
    if not TOKEN:
        print("ERROR: set GITHUB_TOKEN")
        return 1
    msg = sys.argv[1] if len(sys.argv) > 1 else "update"

    st, me = req("GET", "/user")
    if st != 200:
        print("token rejected:", st, me)
        return 1
    print(f"[ok] auth as {me.get('login')}")

    # 1) 当前分支头
    st, ref = req("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}")
    if st != 200:
        print("cannot read ref:", st, ref)
        return 1
    head_sha = ref["object"]["sha"]

    st, head_commit = req("GET", f"/repos/{OWNER}/{REPO}/git/commits/{head_sha}")
    if st != 200:
        print("cannot read head commit:", st, head_commit)
        return 1
    base_tree = head_commit["tree"]["sha"]
    print(f"[ok] head {head_sha[:8]}  base_tree {base_tree[:8]}")

    # 2) 上传每个文件为 blob
    entries = []
    for i, rel in enumerate(FILES, 1):
        abs_path = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(abs_path):
            print(f"  [{i}/{len(FILES)}] SKIP missing: {rel}")
            return 1
        with open(abs_path, "rb") as f:
            content = base64.b64encode(f.read()).decode("ascii")
        st, blob = req("POST", f"/repos/{OWNER}/{REPO}/git/blobs",
                       {"content": content, "encoding": "base64"})
        if st not in (200, 201):
            print(f"  [{i}/{len(FILES)}] BLOB FAIL {rel}: {st} {blob}")
            return 1
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"  [{i}/{len(FILES)}] blob ok  {rel}")

    # 3) 建树（基于 base_tree，只覆盖列出的文件）
    st, tree = req("POST", f"/repos/{OWNER}/{REPO}/git/trees",
                   {"base_tree": base_tree, "tree": entries})
    if st not in (200, 201):
        print("tree failed:", st, tree)
        return 1
    print(f"[ok] new tree {tree['sha'][:8]}")

    # 4) 建 commit
    st, commit = req("POST", f"/repos/{OWNER}/{REPO}/git/commits",
                     {"message": msg, "tree": tree["sha"], "parents": [head_sha]})
    if st not in (200, 201):
        print("commit failed:", st, commit)
        return 1
    print(f"[ok] new commit {commit['sha'][:8]}")

    # 5) 移动分支指针 —— 这一步才会触发 push 事件（只触发 1 次构建）
    st, upd = req("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
                  {"sha": commit["sha"], "force": False})
    if st != 200:
        print("update ref failed:", st, upd)
        return 1
    print(f"[ok] {BRANCH} -> {commit['sha'][:8]}")
    print()
    print(f"commit: https://github.com/{OWNER}/{REPO}/commit/{commit['sha']}")
    print(f"actions: https://github.com/{OWNER}/{REPO}/actions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
