#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验 GitHub Actions 产出的 APK 是否真的带上了本版改动。

用法：
    python _verify_apk.py <apk_path>

关键点（踩过的坑）：
  * APK 里可能有 6 个 dex（classes.dex ~ classes6.dex）。只搜 classes.dex 会漏掉
    后来编译进去的类 → 误报「符号缺失」。必须合并全部 dex 再搜。
  * AndroidManifest.xml 在 APK 里是二进制 XML，字符串池可能是 UTF-16 也可能是
    UTF-8，所以两种编码都试一遍，任一命中即通过。
  * 没开 minify 时，自定义方法名会原样保留在 dex 里，可直接按名字搜。
"""
import os
import re
import sys
import zipfile

# ---- 期望在 dex 里出现的字符串 ----
EXPECT_DEX = [
    "com/workbuddy/routermon/MainActivity",
    "com/workbuddy/routermon/WebViewActivity",
    "com/workbuddy/routermon/Es5Fix",              # v1.4 新增类
    "interceptJsFile",                             # v1.4 子资源拦截
    "interceptDoc",                                # v1.3 主文档注入
    "proc_get",                                    # v1.4 原生直连实测
    "reqproc",
    "shouldInterceptRequest",
    "okhttp3",                                     # 依赖
    "org/jsoup",                                   # 依赖
    "kotlin",                                      # 运行时
]

# ---- 期望在 manifest 里出现的字符串 ----
EXPECT_MANIFEST = [
    "com.workbuddy.routermon.MainActivity",
    "com.workbuddy.routermon.WebViewActivity",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    # 第三项权限是 WRITE_EXTERNAL_STORAGE（maxSdkVersion=28），用于把诊断报告
    # 落到 /sdcard。不是 ACCESS_WIFI_STATE —— 我们读的是路由器 HTTP 接口，
    # 不碰本机 WiFi 状态。
    "android.permission.WRITE_EXTERNAL_STORAGE",
]

EXPECT_VERSION = "1.5"
STALE_VERSION = "1.4"


def has_str(blob: bytes, s: str) -> bool:
    """在二进制块里找字符串，UTF-8 / UTF-16LE 任一命中即可。"""
    if s.encode("utf-8") in blob:
        return True
    if s.encode("utf-16-le") in blob:
        return True
    return False


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: python _verify_apk.py <apk_path>")
        return 2
    apk = sys.argv[1]
    if not os.path.isfile(apk):
        print(f"ERROR: not found: {apk}")
        return 2

    size = os.path.getsize(apk)
    print(f"APK: {apk}")
    print(f"大小: {size:,} 字节 ({size/1024/1024:.2f} MB)")
    print()

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        print(f"zip 条目数: {len(names)}")

        dex_names = sorted(
            [n for n in names if re.fullmatch(r"classes\d*\.dex", n)],
            key=lambda n: (len(n), n),
        )
        print(f"dex 文件 ({len(dex_names)} 个): {', '.join(dex_names)}")

        dex_blob = b""
        for n in dex_names:
            dex_blob += z.read(n)
        print(f"dex 合并后: {len(dex_blob):,} 字节")
        print()

        fail = 0

        # ---- 1) dex 符号 ----
        print("== dex 符号 ==")
        for s in EXPECT_DEX:
            ok = has_str(dex_blob, s)
            if not ok:
                fail += 1
            print(f"  [{'ok ' if ok else 'MISS'}] {s}")

        # ---- 2) manifest ----
        print()
        print("== AndroidManifest.xml ==")
        manifest = z.read("AndroidManifest.xml")
        print(f"  manifest {len(manifest):,} 字节")
        for s in EXPECT_MANIFEST:
            ok = has_str(manifest, s)
            if not ok:
                fail += 1
            print(f"  [{'ok ' if ok else 'MISS'}] {s}")

        # versionName —— 字符串池里是独立的 "1.4"
        ok_new = has_str(manifest, EXPECT_VERSION)
        ok_old = has_str(manifest, STALE_VERSION)
        print(f"  [{'ok ' if ok_new else 'MISS'}] versionName 含 {EXPECT_VERSION}")
        if ok_old:
            fail += 1
            print(f"  [BAD ] manifest 里仍残留旧版本号 {STALE_VERSION} —— 版本没升上去？")

        # ---- 3) minify 判定 ----
        print()
        print("== minify 判定 ==")
        # 没混淆时自定义方法名 intercepJsFile 一定还在；被混淆则搜不到
        obfuscated = not has_str(dex_blob, "interceptJsFile")
        print(f"  [{'BAD ' if obfuscated else 'ok '}] "
              f"{'看起来被混淆了（自定义方法名消失）' if obfuscated else '未混淆，方法名原样保留'}")
        if obfuscated:
            fail += 1

    print()
    if fail == 0:
        print("★ 全部校验通过")
        return 0
    print(f"✗ 有 {fail} 项未通过")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
