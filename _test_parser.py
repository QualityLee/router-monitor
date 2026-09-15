#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HtmlParser.kt 的 Python 等价移植，用来在编译前验证「标签->值」提取逻辑。
只验证算法，不涉及 Kotlin 语法。

用法： python _test_parser.py
"""
import re

# 与 HtmlParser.kt 的 ALL_LABELS 保持一致
ALL_LABELS = [
    "信号强度", "信号质量", "信号值", "信号", "RSRP", "RSRQ", "SINR", "RSSI", "ECIO", "EC/IO",
    "SIM卡卡号", "ICCID", "SIM卡号", "SIM卡", "卡号", "IMSI", "IMEI号", "IMEI",
    "网络制式", "网络类型", "网络模式", "当前网络", "制式",
    "运营商信息", "运营商", "网络运营商", "服务商",
    "运行时间", "联网时间", "在线时长", "在线时间", "连接时间", "系统时间", "已连接",
    "硬件版本", "软件版本", "固件版本", "设备型号", "序列号", "设备名称",
    "IP地址", "子网掩码", "默认网关", "网关", "MAC地址", "DNS",
    "模式",
    "signal", "signal_strength", "rssi", "rsrp", "rsrq", "sinr",
    "iccid", "imei", "imsi", "sim", "simcard", "operator", "isp", "carrier",
    "nettype", "net_type", "network", "networktype", "mode", "uptime",
]

LABEL_TAIL = re.compile(r"^(信息|名称|状态|数值|值|号|地址|类型|模式)\s*[:：]")

LEAD = ":：=>| '\","          # strip_lead 会剥掉的开关字符


def decode(s):
    t = (s.replace("&nbsp;", " ").replace("&ldquo;", '"').replace("&rdquo;", '"')
          .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
          .replace("&quot;", '"').replace("&amp;", "&"))
    return t


def to_lines(html):
    t = html
    t = re.sub(r"(?is)<script.*?</script>", "\n", t)
    t = re.sub(r"(?is)<style.*?</style>", "\n", t)
    t = re.sub(r"(?is)<!--.*?-->", "\n", t)
    t = re.sub(r"(?is)<br\s*/?>", "\n", t)
    t = re.sub(
        r"(?is)</(td|th|tr|div|p|li|h1|h2|h3|h4|h5|span|label|option|dt|dd|a|b|strong|font)>",
        "\n", t)
    t = re.sub(r"(?is)<[^>]+>", " ", t)
    t = decode(t)
    out = []
    for raw in t.split("\n"):
        line = re.sub(r"[ \t\u00a0\u3000]+", " ", raw).strip()
        if line:
            out.append(line)
    return "\n".join(out)


def strip_lead(s):
    t = s.strip()
    while t and t[0] in LEAD:
        t = t[1:].strip()
    return t


def strip_label_tail(s):
    t = s.strip()
    while True:
        m = LABEL_TAIL.match(t)
        if not m:
            break
        t = strip_lead(t[m.end():])
    return t


def cut_at_next_label(s, exclude):
    if not s:
        return s
    end = len(s)
    for other in ALL_LABELS:
        if other == exclude or len(other) < 2:
            continue
        oi = s.find(other)
        if 1 <= oi < end:
            end = oi
    return s[:end].strip()


def is_label_only(s):
    t = strip_lead(s).strip().rstrip(":：|").strip()
    return t in ALL_LABELS


# 与 HtmlParser.kt 的 JUNK_MARKS 保持一致：
# 出现这些片段的「值」其实是 CSS / JS 残渣，不是数据
JUNK_MARKS = [
    "{", "}", ";", "=>", "function", "document.", "window.", "style=",
    "px;", "px ", "var(", "javascript:", "display:", "position:", "@media",
    "data-bind", "data-trans", "http-equiv",
]

TIME_ONLY = re.compile(r"^\d{1,2}:\d{2}(:\d{2})?$")


def clean(v):
    if v is None:
        return None
    s = decode(v).strip()
    s = re.sub(r"(?is)<[^>]+>", " ", s).strip()
    s = strip_lead(s)
    s = s.strip().rstrip(",;，。|")
    if not s or len(s) > 60:
        return None
    if is_label_only(s):
        return None
    if s in ("--", "-"):
        return None
    # 过滤假值。真实场景：SPA 页面里 data-bind="visible: x > 0" 这种属性值
    # 让正则 <[^>]+> 提前断开，剩下的 CSS 片段被当成「运营商」取了出来。
    # （见 2026-09-15 USR-G805 真机诊断日志）
    low = s.lower()
    for j in JUNK_MARKS:
        if j in low:
            return None
    if s.endswith((">", '"', "'", "=")):
        return None
    if s.count(" ") >= 8:
        return None
    # 两个以上冒号通常是 JS 片段（a?b:c:d），但 "14:30:00" 这种时间要放行
    if s.count(":") >= 2 and not TIME_ONLY.match(s):
        return None
    return s


def value_after_label(text, labels):
    lines = text.split("\n")
    for i, line in enumerate(lines):
        for lb in labels:
            idx = line.find(lb)
            if idx < 0:
                continue
            rest = strip_label_tail(cut_at_next_label(strip_lead(line[idx + len(lb):]), lb))
            if rest and not is_label_only(rest) and len(rest) <= 60:
                return rest
            k, hop = i + 1, 0
            while k < len(lines) and hop < 3:
                nxt = strip_label_tail(cut_at_next_label(strip_lead(lines[k]), lb))
                if not nxt:
                    k += 1
                    hop += 1
                    continue
                if is_label_only(nxt):
                    break
                if len(nxt) <= 60:
                    return nxt
                break
    return None


def inline_value(text, labels):
    for lb in labels:
        start = 0
        while True:
            idx = text.find(lb, start)
            if idx < 0:
                break
            start = idx + len(lb)
            rest = strip_label_tail(cut_at_next_label(strip_lead(text[start:]), lb))
            if not rest:
                continue
            v = rest.strip().rstrip(',;，。|"\'')
            if v and len(v) <= 60:
                return v
    return None


def regex_value(text, patterns):
    for p in patterns:
        m = re.search(p, text)
        if m:
            g = m.group(1) if m.groups() else m.group(0)
            v = g.strip()
            if v:
                return v
    return None


def parse_page(raw):
    info = {}
    if not raw.strip():
        return info
    lines = to_lines(raw)
    flat = lines.replace("\n", " ")
    flat2 = flat.replace("  ", "\n").replace("|", "\n")

    def pick(labels, patterns, min_digits=-1):
        by = (clean(value_after_label(lines, labels))
              or clean(inline_value(flat, labels))
              or clean(value_after_label(flat2, labels)))
        if by and (min_digits < 0 or sum(c.isdigit() for c in by) >= min_digits):
            return by
        return regex_value(raw, patterns) or regex_value(lines, patterns)

    v = pick(["SIM卡卡号", "ICCID", "SIM卡号", "卡号", "iccid", "simcard"],
             [r"(89\d{17,18})", r"\b(89\d{15,18})\b", r"\b(\d{19,20})\b"], min_digits=15)
    if v:
        info["iccid"] = v.replace(" ", "")
    v = pick(["IMEI号", "IMEI", "imei"], [r"(?i)IMEI[^0-9]{0,12}(\d{14,16})"], min_digits=14)
    if v:
        info["imei"] = v.replace(" ", "")
    v = pick(["IMSI", "imsi"], [r"(?i)IMSI[^0-9]{0,12}(\d{14,16})"], min_digits=14)
    if v:
        info["imsi"] = v.replace(" ", "")
    v = pick(["信号强度", "信号质量", "信号值", "RSSI", "rssi", "signal", "signal_strength"],
             [r"(-?\d{2,3}(?:\.\d+)?)\s*dBm",
              r"(?i)RSSI[^0-9\-]{0,12}(-?\d{2,3})",
              r"(?i)RSSI\s*[:：]?\s*(-?\d{1,3})"])
    if v:
        info["rssi"] = v
    v = pick(["RSRP", "rsrp"], [r"(?i)RSRP[^0-9\-]{0,12}(-?\d{2,3})"])
    if v:
        info["rsrp"] = v
    v = pick(["RSRQ", "rsrq"], [r"(?i)RSRQ[^0-9\-]{0,12}(-?\d{1,3})"])
    if v:
        info["rsrq"] = v
    v = pick(["SINR", "sinr", "信噪比"], [r"(?i)SINR[^0-9\-]{0,12}(-?\d{1,3})"])
    if v:
        info["sinr"] = v
    v = pick(["网络制式", "网络类型", "网络模式", "当前网络", "制式", "nettype", "net_type", "模式", "mode"],
             [r"(?i)(LTE[- ]?FDD|FDD[- ]?LTE|LTE[- ]?TDD|TDD[- ]?LTE|NR[- ]?SA|NR[- ]?NSA|5G[- ]?NR)",
              r"(?i)\b(4G|3G|2G|LTE|WCDMA|TD-SCDMA|TDSCDMA|EVDO|GSM|CDMA)\b",
              r"(?i)(TDD|FDD)"])
    if v:
        info["net"] = v
    v = pick(["运营商信息", "运营商", "网络运营商", "服务商", "operator", "carrier", "isp"],
             [r"(中国(?:移动|联通|电信|广电|铁通|卫通|网通))",
              r"(China ?Mobile|China ?Unicom|China ?Telecom)",
              r"(?i)\b(CMCC|CUCC|CTCC|UNICOM|CMNET)\b"])
    if v:
        info["op"] = v
    v = pick(["运行时间", "联网时间", "在线时长", "在线时间", "连接时间", "系统时间", "已连接",
              "uptime", "runtime", "connected"],
             [r"(\d+\s*h\s*\d+\s*m(?:\s*\d+\s*s)?)",
              r"(\d+\s*days?\s*\d+\s*h(?:\s*\d+\s*m)?)",
              r"(\d+\s*(?:天|小时|时|分钟|分|秒)[^\s]{0,8})"])
    if v:
        info["rt"] = v
    return info


# ----------------------------------------------------------------- 用例

CASES = [
    # ---- 1. 官方说明书里 USR-G805「状态总览」的真实内容（tab 分隔版）----
    ("G805状态总览(说明书原文)",
     "有人物联网工业物联网通信专家 TEST 状态总览 路由表> 服务> 网络> VPN> 防火墙> 系统> 退出\n"
     "内存可用数\t83824 kB / 125068 kB (67%)\n"
     "空间数\t49904 kB / 125068 kB (39%)\n"
     "已缓存\t26008 kB / 125068 kB (20%)\n"
     "已缓冲\t7912 kB / 125068 kB (6%)\n"
     "网络IPv4 WAN状态\t类型: dhcp\t地址: 10.138.154.20\t子网掩码: 255.255.255.248\t"
     "网关: 10.138.154.21\tDNS 1: 61.156.60.66\teth2 DNS 2: 61.179.49.66\t"
     "RSSI: 31\t运营商信息: 中国联通\t模式: FDD-LTE(4G)\t已连接: 0h 49m 24s\n"
     "IPv6 WAN状态\t地址: 2408:8417:309b42:b08bexdiff.fedfc503/64\n",
     {"rssi": "31", "net": "FDD-LTE(4G)", "op": "中国联通", "rt": "0h 49m 24s"}),

    # ---- 2. 同一页，但字段全挤在一行（HTML 被拍平后的样子）----
    ("G805状态总览(单行拍平)",
     "<div>状态总览</div><div>RSSI: 31 运营商信息: 中国联通 模式: FDD-LTE(4G) 已连接: 0h 49m 24s</div>",
     {"rssi": "31", "net": "FDD-LTE(4G)", "op": "中国联通", "rt": "0h 49m 24s"}),

    # ---- 3. G805 登录页（说明书原文），应当什么都取不到 ----
    ("G805登录页(应空)",
     "<html><body><div class='login'>有人物联网工业物联网通信专家 TEST English | 中文<br>"
     "需要授权<br>请输入用户名和密码。<br>用户名: <input value='admin'> 密码:"
     "<input type='password' name='password'><br>登录<br>复位<br>"
     "济南有人物联网技术有限公司 http://www.usr.cn/</div></body></html>",
     {}),

    # ---- 4. 老版 G805 登录页（只有密码框），应当什么都取不到 ----
    ("老版G805登录页(应空)",
     "<html><body><div class='login'>有人物联网工业物联网通信专家<br>需要授权<br>"
     "请输入密码!<br>密码: <input type='password' name='password'><br>登录<br>"
     "济南有人物联网技术有限公司</div></body></html>",
     {}),

    # ---- 5. 标准 td 表格（ICCID/IMEI 常见位置）----
    ("表格型",
     "<html><body><table>"
     "<tr><td>硬件版本</td><td>V1.0</td></tr>"
     "<tr><td>软件版本</td><td>V1.0.06</td></tr>"
     "<tr><td>信号强度</td><td>-97 dBm</td></tr>"
     "<tr><td>SIM卡卡号</td><td>898607B6151770265124</td></tr>"
     "<tr><td>IMEI</td><td>861074032352591</td></tr>"
     "<tr><td>IMSI</td><td>460081234567890</td></tr>"
     "<tr><td>网络制式</td><td>LTE-FDD</td></tr>"
     "<tr><td>运营商</td><td>中国联通</td></tr>"
     "<tr><td>运行时间</td><td>3天2小时15分</td></tr>"
     "</table></body></html>",
     {"iccid": "898607B6151770265124", "imei": "861074032352591",
      "imsi": "460081234567890", "rssi": "-97 dBm", "net": "LTE-FDD",
      "op": "中国联通", "rt": "3天2小时15分"}),

    # ---- 6. div + span 上下两列 ----
    ("span双列",
     "<html><div class='row'><span class='k'>信号强度</span><span class='v'>-83 dBm</span></div>"
     "<div class='row'><span class='k'>SIM卡卡号</span><span class='v'>898604A4123456789012</span></div>"
     "<div class='row'><span class='k'>RSRP</span><span class='v'>-105 dBm</span></div></html>",
     {"iccid": "898604A4123456789012", "rssi": "-83 dBm", "rsrp": "-105 dBm"}),

    # ---- 7. 纯文本冒号写法 ----
    ("冒号文本",
     "<html><body><pre>\n网络类型：LTE\n信号强度: -71dBm\n"
     "ICCID： 89860317888888888888\nIMEI： 861074032352591\n</pre></body></html>",
     {"iccid": "89860317888888888888", "imei": "861074032352591",
      "rssi": "-71dBm", "net": "LTE"}),

    # ---- 8. 标签独占一行，值在下一行 ----
    ("label独占行",
     "<html><body><div><label>信号强度</label></div><div><b>-99 dBm</b></div>"
     "<div><label>SIM卡卡号</label></div><div><b>89860712345678901234</b></div></body></html>",
     {"iccid": "89860712345678901234", "rssi": "-99 dBm"}),

    # ---- 9. JSON 接口返回 ----
    ("JSON",
     "<html><script>var d={\"signal\":\"-88dBm\",\"iccid\":\"89860111111111111111\","
     "\"imei\":\"861074032352591\"};</script></html>",
     {"iccid": "89860111111111111111", "imei": "861074032352591", "rssi": "-88"}),

    # ---- 10. select 下拉框里的制式 ----
    ("下拉框网络制式",
     "<html><td>网络制式</td><td><select><option selected>4G</option></select></td>"
     "<td>信号强度</td><td><font color='green'>-65 dBm</font></td></html>",
     {"rssi": "-65 dBm", "net": "4G"}),

    # ---- 11. 干扰项：版本号不能被当成 ICCID ----
    ("版本号干扰",
     "<html><td>软件版本</td><td>1.0.06.20240115</td>"
     "<td>IMEI</td><td>861074032352591</td></html>",
     {"imei": "861074032352591"}),

    # ---- 12. ★真机回归：USR-G805 新 webui（Knockout SPA）首页 ----
    # 静态 HTML 里只有 data-bind 占位，一个真值都没有，必须解析为空，
    # 绝不能把某个属性/CSS 片段当成数据（老版本把 logout 当登录态、
    # 把 CSS 片段当「运营商」）。
    ("G805 SPA首页(应空)",
     "<html><head><title>有人物联网工业物联网通信专家</title>"
     "<link rel='stylesheet' href='css/bootstrap.css'>"
     "<script src='js/jquery.js'></script><script src='js/knockout.js'></script>"
     "<style>#loading { display: none; position: relative; }</style></head>"
     "<body><div id='wrapper'>"
     "<div data-bind='text: networkType'></div>"
     "<div data-bind='text: networkOperator'></div>"
     "<div id='signal_strength' data-bind='text: rssi'></div>"
     "<a href='#home' data-trans='home'>首页</a>"
     "<a href='#network_details' data-trans='network_details'>网络</a>"
     "<a href='#password_management' data-trans='password_management'>密码</a>"
     "<a href='javascript:;' data-trans='logout'>退出</a>"
     "</div></body></html>",
     {}),

    # ---- 13. ★真机回归：SPA 属性里的 ">" 会让 <[^>]+> 提前断行 ----
    # 断行后残留的 CSS 片段紧跟在「运营商信息:」后面，
    # 新版的 JUNK_MARKS 必须把它拦下来（老版会产出 op=CSS 残渣）。
    ("G805属性断裂(不得产出CSS假值)",
     "<html><body><div class='info'>运营商信息: "
     "<span data-bind='visible: x > 1' style='display: none; position: relative;'></span>"
     "</div><div>#network_details</div></body></html>",
     {}),
]


# ------------------------------------------------------- clean() 单元用例
# (输入, 期望) —— 直接验证假值过滤，不依赖页面上下文
CLEAN_CASES = [
    # 真机日志里冒出来的那个假运营商值就是这一类
    ("lay: none; position: relative;\">", None),
    ("display: none; position: relative;", None),
    ("1' style='display: none; position: relative;'>", None),
    ("0' style='display: none; position: relative;'>", None),
    ("data-bind=\"text: networkOperator\"", None),
    ("function(){ return a; }", None),
    ("{a: 1}", None),
    ("", None),
    ("--", None),
    ("运营商", None),          # 纯标签不算值
    ("运营商信息:", None),      # 标签+后缀不算值
    # 正常值必须放行
    ("中国联通", "中国联通"),
    ("FDD-LTE(4G)", "FDD-LTE(4G)"),
    ("-97 dBm", "-97 dBm"),
    ("31", "31"),
    ("0h 49m 24s", "0h 49m 24s"),
    ("3天2小时15分", "3天2小时15分"),
    ("898607B6151770265124", "898607B6151770265124"),
    ("14:30:00", "14:30:00"),          # 时间格式不能因为冒号被判成 JS
    ("2026-09-15 14:30", "2026-09-15 14:30"),
]


def main():
    bad = 0
    for name, html, want in CASES:
        got = parse_page(html)
        ok = all(got.get(k) == v for k, v in want.items())
        # 额外：不希望出现的字段不能凭空冒出来（want 里没写的）
        extra = {k: v for k, v in got.items() if k not in want}
        if ok and extra:
            ok = False
        flag = "PASS" if ok else "FAIL"
        if not ok:
            bad += 1
        print("[%s] %s" % (flag, name))
        print("       got  = %s" % got)
        print("       want = %s" % want)
        if extra and ok is False and all(got.get(k) == v for k, v in want.items()):
            print("       多出: %s" % extra)
        print()

    print("---- clean() 假值过滤 ----")
    for raw, want in CLEAN_CASES:
        got = clean(raw)
        ok = got == want
        if not ok:
            bad += 1
        print("[%s] clean(%r) = %r  (want %r)" % ("PASS" if ok else "FAIL", raw, got, want))
    print()

    total = len(CASES) + len(CLEAN_CASES)
    print("通过 %d/%d" % (total - bad, total))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
