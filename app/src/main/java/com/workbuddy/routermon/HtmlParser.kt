package com.workbuddy.routermon

/**
 * 通用「标签 -> 值」提取器，不依赖任何特定固件的页面结构。
 *
 * 依次尝试 4 种策略：
 *  1) HTML 表格结构： <td>标签</td><td>值</td>
 *  2) 纯文本：       "信号强度：-97 dBm"
 *  3) 下一行：       <span>信号强度</span>\n<span>-97 dBm</span>
 *  4) 值本身形态：   -97 dBm / 89 开头的 19~20 位数字 / 15 位数字
 */
object HtmlParser {

    /** 已知字段标签，用于判断「取到的下一行其实是另一个标签」以及「值到哪里结束」 */
    private val ALL_LABELS = listOf(
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
        "nettype", "net_type", "network", "networktype", "mode", "uptime"
    )

    /** 标签「带了后缀」的情况：运营商信息 / 信号强度值 / 设备状态 … */
    private val LABEL_TAIL = Regex("^(信息|名称|状态|数值|值|号|地址|类型|模式)\\s*[:：]")

    /** 把 HTML 拍平成「一行一个语义单元」的纯文本 */
    fun toLines(html: String): String {
        var t = html
        t = Regex("(?is)<script.*?</script>").replace(t, "\n")
        t = Regex("(?is)<style.*?</style>").replace(t, "\n")
        t = Regex("(?is)<!--.*?-->").replace(t, "\n")
        t = Regex("(?is)<br\\s*/?>").replace(t, "\n")
        t = Regex("(?is)</(td|th|tr|div|p|li|h1|h2|h3|h4|h5|span|label|option|dt|dd|a|b|strong|font)>")
            .replace(t, "\n")
        t = Regex("(?is)<[^>]+>").replace(t, " ")
        t = decode(t)
        val out = ArrayList<String>()
        for (raw in t.split('\n')) {
            val line = raw.replace(Regex("[ \t\u00a0\u3000]+"), " ").trim()
            if (line.isNotEmpty()) out.add(line)
        }
        return out.joinToString("\n")
    }

    fun decode(s: String): String {
        var t = s
            .replace("&nbsp;", " ")
            .replace("&ldquo;", "\"").replace("&rdquo;", "\"")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&")
        t = Regex("&#(x?)([0-9a-fA-F]+);").replace(t) { m ->
            try {
                val hex = m.groupValues[1].isNotEmpty()
                val code = m.groupValues[2].toInt(if (hex) 16 else 10)
                String(Character.toChars(code))
            } catch (e: Exception) {
                m.value
            }
        }
        return t
    }

    /** 解析单个页面 */
    fun parsePage(raw: String, sourceUrl: String = ""): SignalInfo {
        val info = SignalInfo()
        info.sourceUrl = sourceUrl
        if (raw.isBlank()) return info

        val lines = toLines(raw)
        val flat = lines.replace('\n', ' ')

        fun pick(labels: List<String>, patterns: List<String>, minDigits: Int = -1): String? {
            val byLabel = clean(valueAfterLabel(lines, labels))
                ?: clean(inlineValue(flat, labels))
                ?: clean(valueAfterLabel(flatLines(flat), labels))
            if (byLabel != null && (minDigits < 0 || byLabel.count { it.isDigit() } >= minDigits)) {
                return byLabel
            }
            return regexValue(raw, patterns) ?: regexValue(lines, patterns)
        }

        pick(listOf("SIM卡卡号", "ICCID", "SIM卡号", "卡号", "iccid", "simcard"),
            listOf("""(89\d{17,18})""", """\b(89\d{15,18})\b""", """\b(\d{19,20})\b"""),
            minDigits = 15)
            ?.let { info.iccid = it.replace(" ", "") }

        pick(listOf("IMEI号", "IMEI", "imei"),
            listOf("""(?i)IMEI[^0-9]{0,12}(\d{14,16})"""),
            minDigits = 14)
            ?.let { info.imei = it.replace(" ", "") }

        pick(listOf("IMSI", "imsi"),
            listOf("""(?i)IMSI[^0-9]{0,12}(\d{14,16})"""),
            minDigits = 14)
            ?.let { info.imsi = it.replace(" ", "") }

        pick(listOf("信号强度", "信号质量", "信号值", "RSSI", "rssi", "signal", "signal_strength"),
            listOf("""(-?\d{2,3}(?:\.\d+)?)\s*dBm""",
                """(?i)RSSI[^0-9\-]{0,12}(-?\d{2,3})""",
                """(?i)RSSI\s*[:：]?\s*(-?\d{1,3})"""))
            ?.let { info.rssi = it }

        pick(listOf("RSRP", "rsrp"), listOf("""(?i)RSRP[^0-9\-]{0,12}(-?\d{2,3})"""))
            ?.let { info.rsrp = it }

        pick(listOf("RSRQ", "rsrq"), listOf("""(?i)RSRQ[^0-9\-]{0,12}(-?\d{1,3})"""))
            ?.let { info.rsrq = it }

        pick(listOf("SINR", "sinr", "信噪比"), listOf("""(?i)SINR[^0-9\-]{0,12}(-?\d{1,3})"""))
            ?.let { info.sinr = it }

        pick(listOf("网络制式", "网络类型", "网络模式", "当前网络", "制式", "nettype", "net_type", "模式", "mode"),
            listOf("""(?i)(LTE[- ]?FDD|FDD[- ]?LTE|LTE[- ]?TDD|TDD[- ]?LTE|NR[- ]?SA|NR[- ]?NSA|5G[- ]?NR)""",
                """(?i)\b(4G|3G|2G|LTE|WCDMA|TD-SCDMA|TDSCDMA|EVDO|GSM|CDMA)\b""",
                """(?i)(TDD|FDD)"""))
            ?.let { info.networkMode = it }

        pick(listOf("运营商信息", "运营商", "网络运营商", "服务商", "operator", "carrier", "isp"),
            listOf("""(中国(?:移动|联通|电信|广电|铁通|卫通|网通))""",
                """(China ?Mobile|China ?Unicom|China ?Telecom)""",
                """(?i)\b(CMCC|CUCC|CTCC|UNICOM|CMNET)\b"""))
            ?.let { info.operator = it }

        pick(listOf(
            "运行时间", "联网时间", "在线时长", "在线时间", "连接时间", "系统时间", "已连接",
            "uptime", "runtime", "connected"
        ),
            listOf("""(\d+\s*h\s*\d+\s*m(?:\s*\d+\s*s)?)""",
                """(\d+\s*days?\s*\d+\s*h(?:\s*\d+\s*m)?)""",
                """(\d+\s*(?:天|小时|时|分钟|分|秒)[^\s]{0,8})"""))
            ?.let { info.runTime = it }

        info.connected = info.hasData()
        return info
    }

    /** 合并：dst 里已有真值就不覆盖 */
    fun merge(dst: SignalInfo, src: SignalInfo) {
        if (!SignalInfo.isReal(dst.rssi) && SignalInfo.isReal(src.rssi)) dst.rssi = src.rssi
        if (!SignalInfo.isReal(dst.rsrp) && SignalInfo.isReal(src.rsrp)) dst.rsrp = src.rsrp
        if (!SignalInfo.isReal(dst.rsrq) && SignalInfo.isReal(src.rsrq)) dst.rsrq = src.rsrq
        if (!SignalInfo.isReal(dst.sinr) && SignalInfo.isReal(src.sinr)) dst.sinr = src.sinr
        if (!SignalInfo.isReal(dst.networkMode) && SignalInfo.isReal(src.networkMode)) dst.networkMode = src.networkMode
        if (!SignalInfo.isReal(dst.operator) && SignalInfo.isReal(src.operator)) dst.operator = src.operator
        if (!SignalInfo.isReal(dst.iccid) && SignalInfo.isReal(src.iccid)) dst.iccid = src.iccid
        if (!SignalInfo.isReal(dst.imei) && SignalInfo.isReal(src.imei)) dst.imei = src.imei
        if (!SignalInfo.isReal(dst.imsi) && SignalInfo.isReal(src.imsi)) dst.imsi = src.imsi
        if (!SignalInfo.isReal(dst.runTime) && SignalInfo.isReal(src.runTime)) dst.runTime = src.runTime
        if (dst.sourceUrl.isBlank() && src.sourceUrl.isNotBlank() && src.hasData()) dst.sourceUrl = src.sourceUrl
        dst.connected = dst.hasData()
    }

    // ------------------------------------------------------------------

    private fun flatLines(flat: String): String =
        flat.replace("  ", "\n").replace("|", "\n")

    /**
     * 值到下一个已知标签为止就截断。
     * 例："RSSI: 31 运营商信息: 中国家通 模式: FDD-LTE(4G)" 取 RSSI 时只要 "31"。
     */
    private fun cutAtNextLabel(s: String, exclude: String): String {
        if (s.isEmpty()) return s
        var end = s.length
        for (other in ALL_LABELS) {
            if (other == exclude || other.length < 2) continue
            val oi = s.indexOf(other)
            if (oi in 1 until end) end = oi
        }
        return s.substring(0, end).trim()
    }

    /** 「运营商信息: xxx」这种标签自带后缀，把「信息/名称/状态」等尾巴去掉 */
    private fun stripLabelTail(s: String): String {
        var t = s.trim()
        while (true) {
            val m = LABEL_TAIL.find(t)
            if (m == null) break
            t = stripLead(t.substring(m.value.length))
        }
        return t
    }

    /** 标签在单独一行，值在它后面 1~3 行内 */
    private fun valueAfterLabel(text: String, labels: List<String>): String? {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i]
            for (lb in labels) {
                val idx = line.indexOf(lb)
                if (idx < 0) continue
                val rest = stripLabelTail(cutAtNextLabel(stripLead(line.substring(idx + lb.length)), lb))
                if (rest.isNotEmpty() && !isLabelOnly(rest) && rest.length <= 60) return rest
                var k = i + 1
                var hop = 0
                while (k < lines.size && hop < 3) {
                    val nxt = stripLabelTail(cutAtNextLabel(stripLead(lines[k]), lb))
                    if (nxt.isEmpty()) { k++; hop++; continue }
                    if (isLabelOnly(nxt)) break
                    if (nxt.length <= 60) return nxt
                    break
                }
            }
        }
        return null
    }

    /** 标签和值在同一行 */
    private fun inlineValue(text: String, labels: List<String>): String? {
        for (lb in labels) {
            var from = 0
            while (true) {
                val idx = text.indexOf(lb, from)
                if (idx < 0) break
                from = idx + lb.length
                val rest = stripLabelTail(cutAtNextLabel(stripLead(text.substring(from)), lb))
                if (rest.isEmpty()) continue
                val v = rest.trimEnd(',', ';', '，', '。', '|', '"', '\'')
                if (v.isNotEmpty() && v.length <= 60) return v
            }
        }
        return null
    }

    private fun regexValue(text: String, patterns: List<String>): String? {
        for (p in patterns) {
            try {
                val m = Regex(p).find(text)
                if (m != null) {
                    val g = if (m.groupValues.size > 1) m.groupValues[1] else m.value
                    val v = g.trim()
                    if (v.isNotEmpty()) return v
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun stripLead(s: String): String {
        var t = s.trim()
        while (t.isNotEmpty() && (t[0] == ':' || t[0] == '：' || t[0] == '=' || t[0] == '>' ||
                    t[0] == '|' || t[0] == ' ' || t[0] == '"' || t[0] == '\'' || t[0] == ',')) {
            t = t.substring(1).trim()
        }
        return t
    }

    private fun isLabelOnly(s: String): Boolean {
        val t = stripLead(s).trimEnd(':', '：', '|').trim()
        return ALL_LABELS.any { it == t }
    }

    /** 出现这些片段的「值」其实是 CSS / JS 残渣，不是数据 */
    private val JUNK_MARKS = listOf(
        "{", "}", ";", "=>", "function", "document.", "window.", "style=",
        "px;", "px ", "var(", "javascript:", "display:", "position:", "@media",
        "data-bind", "data-trans", "http-equiv"
    )

    private fun clean(v: String?): String? {
        if (v == null) return null
        var s = decode(v).trim()
        s = Regex("(?is)<[^>]+>").replace(s, " ").trim()
        s = stripLead(s)
        s = s.trimEnd(',', ';', '，', '。', '|')
        if (s.isEmpty() || s.length > 60) return null
        if (isLabelOnly(s)) return null
        if (s == "--" || s == "-") return null
        // 过滤假值。真实场景：页面属性 style="display: none; position: relative;">
        // 被当成「运营商」的值取了出来。（见 2026-09-15 真机诊断日志）
        val low = s.lowercase()
        for (j in JUNK_MARKS) if (low.contains(j)) return null
        if (s.endsWith(">") || s.endsWith("\"") || s.endsWith("'") || s.endsWith("=")) return null
        if (s.count { it == ' ' } >= 8) return null
        // 两个以上冒号通常是 JS 片段（a?b:c:d），但 "14:30:00" 这种时间要放行
        if (s.count { it == ':' } >= 2 && !Regex("""^\d{1,2}:\d{2}(:\d{2})?$""").matches(s)) return null
        return s
    }
}
