# -*- coding: utf-8 -*-
"""从网页模式报告里切出各业务 JS 源码，并定位 service.js:315 附近的语法错误。"""
import io, re, sys

SRC = r'F:\routermon_webview.txt'
OUT = r'C:\Users\yjl18\WorkBuddy\2026-09-15-14-14-15\android-router-monitor\_js'

with io.open(SRC, encoding='utf-8', errors='replace') as f:
    text = f.read()

lines = text.split('\n')

# 报告里每段 JS 的标题形如:
#   ----- http://192.168.1.1/js/service.js  (长度 12345) -----
hdr = re.compile(r'^-----\s+(http\S+)\s+\(长度\s+(\d+)\)\s+-----\s*$')

marks = []
for i, l in enumerate(lines):
    m = hdr.match(l.strip())
    if m:
        marks.append((i, m.group(1), int(m.group(2))))

print('找到 %d 段 JS 源码:' % len(marks))
for i, (idx, url, ln) in enumerate(marks):
    print('  %-60s 报告行 %-7d 长度 %d' % (url, idx + 1, ln))

# 抓取每段正文：从标题下一行开始，到下一个标题（或某个大分隔符）为止
import os
os.makedirs(OUT, exist_ok=True)
sections = {}
for i, (idx, url, ln) in enumerate(marks):
    end = marks[i + 1][0] if i + 1 < len(marks) else len(lines)
    body = lines[idx + 1:end]
    # 去掉尾部空行和后面的 "----- xxx -----" 之类
    while body and (body[-1].strip() == '' or body[-1].startswith('-----') or body[-1].startswith('=====')):
        body.pop()
    sections[url] = body
    name = url.rsplit('/', 1)[-1] or 'index.html'
    safe = re.sub(r'[^A-Za-z0-9._-]', '_', url.replace('http://192.168.1.1/', '').replace('/', '__'))
    with io.open(os.path.join(OUT, safe), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(body))
    print('  -> %s  (%d 行)' % (safe, len(body)))

# 定位 service.js 第 315 行
print()
print('=' * 60)
for url, body in sections.items():
    if 'service.js' in url:
        print('service.js 报告内共 %d 行' % len(body))
        lo, hi = 305, 325
        for n in range(lo, min(hi, len(body))):
            tag = ' <<<<< 报错行(315)' if n == 314 else ''
            print('%5d | %s%s' % (n + 1, body[n], tag))

print()
print('=' * 60)
print('所有段落 URL:')
for u in sections:
    print('  ', u)
