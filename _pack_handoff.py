#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打包「源码交付包」给安卓工程师。

用法:
    python _pack_handoff.py [输出目录]

产物:
    <输出目录>/RouterMonitor-源码交付.zip

打包内容 = 整个工程，但**排除**：
  - 版本控制 / IDE / 编译产物  (.git  build  .gradle  .idea)
  - 本机相关                  (local.properties —— 里面是本机 SDK 路径)
  - 二进制/临时               (*.apk *.aab *.zip *.log __pycache__)
"""

import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))

# 目录名 → 整棵子树都不要
SKIP_DIRS = {
    ".git", "build", ".gradle", ".idea", "__pycache__",
    ".externalNativeBuild", ".cxx", "captures",
}

# 目录名 → 要的下，但只留指定后缀（_js 是测试语料，全留）
KEEP_ALL = {"_js"}

# 文件名 → 直接跳过
SKIP_FILES = {
    "local.properties",   # 本机 SDK 路径，别人用不上
}

# 后缀 → 跳过
SKIP_EXTS = {".apk", ".aab", ".zip", ".log", ".jks", ".keystore", ".iml"}

# 交付包里额外说明的置顶顺序（zip 里排前面）
TOP_ORDER = ["INTEGRATION.md", "README.md"]


def should_skip(rel: str) -> bool:
    parts = rel.replace("\\", "/").split("/")
    for p in parts[:-1]:
        if p in SKIP_DIRS:
            return True
    name = parts[-1]
    if name in SKIP_FILES:
        return True
    if os.path.splitext(name)[1].lower() in SKIP_EXTS:
        return True
    if name.endswith(".pyc"):
        return True
    return False


def collect() -> list:
    out = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        # 原地裁剪，避免走进去
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]

        rel_dir = os.path.relpath(dirpath, ROOT)
        rel_dir = "" if rel_dir == "." else rel_dir.replace("\\", "/")

        for fn in sorted(filenames):
            rel = fn if not rel_dir else rel_dir + "/" + fn
            if should_skip(rel):
                continue
            out.append(rel)

    # 顶层文件排前面，其余按路径
    def sort_key(r: str):
        if r in TOP_ORDER:
            return (0, TOP_ORDER.index(r), r)
        return (1, 0, r)

    return sorted(out, key=sort_key)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(ROOT), "outputs")
    os.makedirs(out_dir, exist_ok=True)
    zip_path = os.path.join(out_dir, "RouterMonitor-源码交付.zip")

    files = collect()

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in files:
            z.write(os.path.join(ROOT, rel.replace("/", os.sep)), rel)

    total_src = sum(os.path.getsize(os.path.join(ROOT, f.replace("/", os.sep)))
                    for f in files)
    size = os.path.getsize(zip_path)

    print("=== 源码交付包已生成 ===")
    print("文件: %s" % zip_path)
    print("条目: %d 个" % len(files))
    print("原始: %.1f KB" % (total_src / 1024.0))
    print("压缩后: %.1f KB" % (size / 1024.0))
    print()
    print("--- 内容清单 ---")
    for rel in files:
        print("  " + rel)
    print()
    print("--- 已排除 ---")
    print("  目录: " + "  ".join(sorted(SKIP_DIRS)))
    print("  文件: " + "  ".join(sorted(SKIP_FILES)))
    print("  后缀: " + "  ".join(sorted(SKIP_EXTS)))

    # 自检：必须包含的关键文件
    need = [
        "INTEGRATION.md",
        "README.md",
        "app/src/main/java/com/workbuddy/routermon/RouterClient.kt",
        "app/src/main/java/com/workbuddy/routermon/HtmlParser.kt",
        "app/src/main/java/com/workbuddy/routermon/SignalInfo.kt",
        "app/src/main/java/com/workbuddy/routermon/Es5Fix.kt",
        "app/src/main/java/com/workbuddy/routermon/WebViewActivity.kt",
    ]
    print()
    missing = [n for n in need if n not in files]
    if missing:
        print("!! 缺少关键文件: %s" % missing)
        return 1
    print("关键文件自检: 全部在内 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
