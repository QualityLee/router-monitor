#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin 源码静态体检（编译前的第一道闸）。

起因：Kotlin 的块注释 **支持嵌套**，KDoc 里写「/cgi-bin/*.cgi」会多开一层注释，
      外层注释的 */ 被内层吃掉，导致「Unclosed comment」——整个文件后面的代码全被吞掉，
      并且编译器还会在别的文件里报一堆莫名其妙的 Unresolved reference。
      云上一次编译要几分钟，本地先扫一遍最划算。

检查项：
  1. 块注释是否平衡（含嵌套计数）
  2. 字符串 / 原始字符串 / 字符字面量是否闭合
  3. 单个文件里块注释内出现的嵌套 /* 数量（提示性输出）

用法： python _lint_kt.py
"""
import os
import sys

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "app", "src", "main", "java", "com", "workbuddy", "routermon")


def scan(path):
    src = open(path, encoding="utf-8").read()
    n = len(src)
    i = 0
    line = 1
    depth = 0
    depth_at = []
    nests = []          # 发生嵌套的位置（提示用）
    problems = []
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
            continue

        # 行注释
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue

        # 块注释（可嵌套）
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            if depth > 0:
                nests.append((line, depth))
            depth += 1
            depth_at.append(line)
            i += 2
            continue
        if c == "*" and i + 1 < n and src[i + 1] == "/":
            if depth == 0:
                problems.append("第 %d 行: 出现了没有对应 /* 的 */" % line)
            else:
                depth -= 1
                depth_at.pop()
            i += 2
            continue

        if depth > 0:
            i += 1
            continue

        # 原始字符串 """
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                problems.append("第 %d 行: 原始字符串 \"\"\" 未闭合" % line)
                break
            line += src.count("\n", i, j)
            i = j + 3
            continue

        # 普通字符串
        if c == '"':
            j = i + 1
            closed = False
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "\n":
                    break
                if src[j] == '"':
                    closed = True
                    break
                j += 1
            if not closed:
                problems.append("第 %d 行: 字符串未闭合" % line)
                break
            i = j + 1
            continue

        # 字符字面量
        if c == "'":
            j = i + 1
            closed = False
            while j < n and src[j] != "\n":
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    closed = True
                    break
                j += 1
            if closed:
                i = j + 1
                continue
            i += 1
            continue

        i += 1

    if depth > 0:
        problems.append("块注释未闭合：还有 %d 层没关，分别开在第 %s 行"
                        % (depth, ", ".join(str(x) for x in depth_at)))
    return problems, nests, line


def main():
    files = sorted(f for f in os.listdir(SRC) if f.endswith(".kt"))
    if not files:
        print("没找到 .kt 文件:", SRC)
        return 1
    bad = 0
    for f in files:
        p = os.path.join(SRC, f)
        problems, nests, lines = scan(p)
        if nests:
            print("[warn] %s: 块注释里还有 %d 处嵌套 /*（合法，但要留意是不是手滑）"
                  % (f, len(nests)))
            for ln, d in nests:
                print("        第 %d 行（嵌套层级 %d）" % (ln, d))
        if problems:
            bad += 1
            print("[FAIL] %s" % f)
            for x in problems:
                print("        " + x)
        else:
            print("[ok  ] %s（%d 行）" % (f, lines))
    print()
    print("检查 %d 个文件，%d 个有问题" % (len(files), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
