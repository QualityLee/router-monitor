#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HtmlParser.kt 里 extractG805Json 的 Python 等价移植 + 真机回归用例。

这个测试**不是测 Kotlin 编译**，而是测解析逻辑（正则 + 字段映射）。
用例全是从 2026-09-16 routermon_webview.txt 报告里抠出来的真机响应，
保证改完解析逻辑后这些 case 仍然稳。

用法： python _test_g805json.py
"""
import re

# 与 HtmlParser.kt extractG805Json() 严格对齐
def extract_g805_json(raw):
    out = {}
    start = raw.find("{")
    if start < 0:
        return out
    end = raw.find("}", start + 1)
    if end < 0:
        return out
    body = raw[start:end + 1]

    def jstr(key):
        pat = '"' + re.escape(key) + '"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"'
        m = re.search(pat, body)
        if not m:
            return None
        v = m.group(1).replace('\\"', '"').strip()
        return v if v and len(v) <= 60 else None

    z = jstr("ziccid")
    if z: out["iccid"] = z
    im = jstr("imei")
    if im: out["imei"] = im
    sim_imsi = jstr("sim_imsi")
    if sim_imsi:
        out["imsi"] = sim_imsi
    else:
        imsi = jstr("imsi")
        if imsi: out["imsi"] = imsi
    rssi = jstr("rssi")
    if rssi: out["rssi"] = rssi
    lte = jstr("lte_rsrp")
    if lte: out["rsrp"] = lte
    else:
        rscp = jstr("rscp")
        if rscp: out["rsrp"] = rscp
    nt = jstr("network_type")
    if nt:
        snt = jstr("sub_network_type")
        out["network_mode"] = (nt + "(" + snt + ")") if snt else nt
    op = jstr("network_provider")
    if op: out["operator"] = op
    up = jstr("uptime")
    if up: out["run_time"] = up
    return out


G805_REAL_RESPONSE = (
    '{"wifi_coverage":"long_mode","m_ssid_enable":"0","sn":"01500326060200003460\n",'
    '"imei":"862790076742140","network_type":"LTE","sub_network_type":"FDD_LTE",'
    '"rssi":"-77","rscp":"","lte_rsrp":"-77","imsi":"","sim_imsi":"460113938676490",'
    '"cr_version":"WH_G405tf_SE_FLASH_V1.0.0B03P18_200108",'
    '"cr_inner_version":"V3.0.16.000000.0000","hw_version":"V1.1.0","MAX_Access_num":"10",'
    '"SSID1":"USR_G805_B252","AuthMode":"WPA2PSK","WPAPSK1_encode":"d3d3LnVzci5jbg==",'
    '"m_SSID":"USR_G805_B252_2","m_AuthMode":"WPA2PSK","m_HideSSID":"0",'
    '"m_WPAPSK1_encode":"d3d3LnVzci5jbg==","m_MAX_Access_num":"0","lan_ipaddr":"192.168.1.1",'
    '"mac_address":"D4AD20FFB250",'
    '"ziccid":"89861126208093092119",'
    '"LocalDomain":"m.home","wan_ipaddr":"10.82.128.242",'
    '"static_wan_ipaddr":"0.0.0.0","ipv6_wan_ipaddr":"","ipv6_pdp_type":"",'
    '"pdp_type":"IP","ppp_status":"ppp_connected","sta_ip_status":"","rj45_state":"",'
    '"ethwan_mode":"auto","uptime":" 0h 11m 22s",'
    # ▼ 含 network_provider 的另一条响应（#13，cmd 含 signalbar/network_provider ...）
    '"signalbar":"5","network_provider":"China Telecom","simcard_roam":"Home"}'
)

G805_EMPTY_RESPONSE = '{"wifi_coverage,m_ssid_enable,sn,imei,...,":""}'

CASES = [
    ("G805 真机响应（已加 multi_data=1）",
     G805_REAL_RESPONSE,
     {
         "iccid": "89861126208093092119",
         "imei": "862790076742140",
         "imsi": "460113938676490",
         "rssi": "-77",
         "rsrp": "-77",
         "network_mode": "LTE(FDD_LTE)",
         "operator": "China Telecom",
         "run_time": "0h 11m 22s",
     }),

    ("v1.4 整串当 key 的空响应（无 multi_data=1）",
     G805_EMPTY_RESPONSE,
     {}),

    ("裸 ICCID 字符串（必须被 {} 包）",
     '{"ziccid":"89861126208093092119"}',
     {"iccid": "89861126208093092119"}),

    ("裸 IMEI + IMSI 字符串",
     '{"imei":"862790076742140","sim_imsi":"460113938676490"}',
     {"imei": "862790076742140", "imsi": "460113938676490"}),

    ("空 JSON 对象",
     '{}',
     {}),

    ("无 JSON（普通 HTML）",
     '<html><body>SIM卡卡号: 89861126208093092119</body></html>',
     {}),

    ("空字段值（应全部跳过）",
     '{"ziccid":"","imei":"","rssi":""}',
     {}),

    ("转义引号在 value 里",
     '{"ziccid":"12345\\"67890"}',
     {"iccid": '12345"67890'}),
]


def main():
    bad = 0
    for name, raw, want in CASES:
        got = extract_g805_json(raw)
        ok = got == want
        if not ok:
            bad += 1
        print("[%s] %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            print("       got:  %r" % got)
            print("       want: %r" % want)
    print()
    print("通过 %d/%d" % (len(CASES) - bad, len(CASES)))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())