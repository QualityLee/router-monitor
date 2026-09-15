package com.workbuddy.routermon

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 4G 路由器 webUI 客户端（不再依赖特定固件结构）
 *
 * 设计思路：绝大多数路由器 webUI 的登录都是「打开首页 -> 表单 POST -> 跳转到内页」，
 * 所以这里不写死接口：
 *   1. 先 GET / 拿到登录页，用 Jsoup 自动发现 <form> 的 action / method / 字段名
 *   2. 用发现的字段名构造 POST（密码框填密码，用户/账号框填 admin，隐藏域原样带回）
 *   3. 若首页没有表单，再退回到一批常见 CGI 接口逐一尝试
 *   4. 登录是否成功不靠 Set-Cookie 名字判断，而是「再 GET 首页，看是不是还是登录页」
 *   5. 抓数据时按 BFS 爬取同源页面（含 frame/iframe）+ JS 里的接口，再通用解析
 */
class RouterClient(hostInput: String, private val password: String) {

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 5.1; rv:1.0) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/39.0 Mobile Safari/537.36"

        /** 页面里出现 ≥2 个这些词，基本可以认定「已经进了后台」 */
        private val KEYWORDS = listOf(
            "信号", "SIM", "ICCID", "IMEI", "IMSI", "运营商", "网络制式",
            "概述", "系统", "状态", "重启", "固件", "版本", "LAN", "WAN", "WiFi", "无线"
        )

        private val BAD_WORDS = listOf(
            "密码错误", "密码不正确", "密码有误", "用户名或密码错误", "登录失败", "授权失败", "认证失败"
        )

        private val SKIP_EXT = listOf(
            ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
            ".woff", ".ttf", ".zip", ".bin", ".mp4", ".pdf", ".map"
        )

        private val SKIP_WORDS = listOf(
            "logout", "signout", "reboot", "restart", "reset", "upgrade",
            "restore", "factory"
        )

        private val SEED_PATHS = listOf(
            "/", "/index.html", "/home.html", "/overview.html", "/status.html", "/state.html",
            "/main.html", "/network.html", "/info.html",
            "/status.cgi", "/overview.cgi", "/cgi-bin/status.cgi", "/goform/status"
        )

        private const val MAX_PAGES = 26
        private const val MAX_CORPUS = 400_000
    }

    val host: String
    val baseUrl: String
    val log = StringBuilder()

    private val cookies = LinkedHashMap<String, Cookie>()
    private val pages = LinkedHashMap<String, String>()
    private val postLog = ArrayList<String>()

    init {
        var h = hostInput.trim()
        h = h.removePrefix("https://").removePrefix("http://")
        h = h.trimEnd('/').trim()
        if (h.isEmpty()) h = "192.168.1.1"
        host = h.substringBefore('/')
        baseUrl = "http://$host"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                for (c in cookies) this@RouterClient.cookies[c.name] = c
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                this@RouterClient.cookies.values.filter { it.matches(url) }
        })
        .build()

    // ---------------------------------------------------------------- 日志

    private fun slog(s: String) {
        log.append(s).append('\n')
        if (log.length > 80_000) log.delete(0, 30_000)
    }

    fun diagText(): String = log.toString()

    fun pagesDump(maxPerPage: Int = 6000, maxPages: Int = 14): String {
        val sb = StringBuilder()
        var n = 0
        for ((u, b) in pages) {
            if (n >= maxPages) break
            n++
            sb.append("\n===== URL: ").append(u).append("  (长度 ").append(b.length).append(") =====\n")
            if (b.length > maxPerPage) {
                sb.append(b, 0, maxPerPage).append("\n...[已截断]")
            } else {
                sb.append(b)
            }
            sb.append('\n')
        }
        for (p in postLog) {
            sb.append("\n===== 登录提交/响应 =====\n").append(p).append('\n')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 基础请求

    private class Resp(
        val code: Int,
        val body: String,
        val location: String?,
        val setCookie: String,
        val authenticate: String = ""
    )

    private fun exec(req: Request): Resp {
        client.newCall(req).execute().use { r ->
            var body = ""
            try {
                body = r.body?.string().orEmpty()
            } catch (e: Exception) {
                body = ""
            }
            return Resp(
                r.code, body, r.header("Location"),
                r.headers("Set-Cookie").joinToString(" | "),
                r.header("WWW-Authenticate").orEmpty()
            )
        }
    }

    private fun abs(u: String) = if (u.startsWith("http")) u else baseUrl + u

    /** 走 HTTP 基本授权时用的 Authorization 头（部分固件不用表单，直接 401） */
    private var authHeader: String? = null

    private fun get(url: String): Resp {
        val b = Request.Builder()
            .url(abs(url))
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", baseUrl + "/")
        authHeader?.let { b.header("Authorization", it) }
        return exec(b.get().build())
    }

    private fun post(url: String, body: String, referer: String): Resp {
        val b = Request.Builder()
            .url(abs(url))
            .post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull()))
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", abs(referer))
            .header("Origin", baseUrl)
        authHeader?.let { b.header("Authorization", it) }
        return exec(b.build())
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- 网络自检

    /** 本机网卡信息 + 与目标是否同网段 */
    fun netSelfCheck(): String {
        val sb = StringBuilder()
        val ipv4 = ArrayList<String>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        ipv4.add(ip)
                        sb.append("  网卡 ").append(ni.name).append(" → ").append(ip).append('\n')
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("  读取网卡失败: ").append(e.message).append('\n')
        }
        if (ipv4.isEmpty()) sb.append("  未发现任何 IPv4 地址（网线没插好 / 没拿到 IP？）\n")

        try {
            val resolved = InetAddress.getByName(host).hostAddress
            sb.append("  解析 ").append(host).append(" → ").append(resolved).append('\n')
        } catch (e: Exception) {
            sb.append("  解析 ").append(host).append(" 失败: ").append(e.message).append('\n')
        }

        val seg = host.substringBeforeLast('.', "")
        val same = seg.isNotEmpty() && ipv4.any { it.substringBeforeLast('.', "") == seg }
        sb.append("  目标网段: ").append(seg).append(".x  →  ")
            .append(if (same) "与本机同网段 ✓" else "与本机不同网段 ✗（可能连不上）").append('\n')

        val out = sb.toString()
        slog("【本机网络】\n$out")
        return out
    }

    /** 直接测 TCP 80 端口，区分「网络不通」和「密码不对」 */
    fun tcpTest(): String {
        val t0 = System.currentTimeMillis()
        val r = try {
            Socket().use { s -> s.connect(InetSocketAddress(host, 80), 4000) }
            "OK  连接 $host:80 成功（${System.currentTimeMillis() - t0} ms）"
        } catch (e: Exception) {
            "FAIL 连接 $host:80 失败：${e.javaClass.simpleName}: ${e.message}"
        }
        slog("【TCP 测试】$r")
        return r
    }

    /** GET / 探测，返回一行概览并落日志 */
    fun probeHome(): String {
        return try {
            val r = get("/")
            pages["/"] = r.body
            val title = runCatching {
                val t = Jsoup.parse(r.body).title()
                if (t.isBlank()) "" else " 标题=$t"
            }.getOrDefault("")
            val msg = "HTTP ${r.code}, 长度 ${r.body.length}$title" +
                    (r.location?.let { ", Location=$it" } ?: "") +
                    (if (r.setCookie.isNotBlank()) ", Set-Cookie=${r.setCookie.take(100)}" else "") +
                    (if (r.authenticate.isNotBlank()) ", WWW-Authenticate=${r.authenticate.take(80)}" else "")
            slog("【首页探测】GET / → $msg")
            val text = HtmlParser.toLines(r.body).replace('\n', ' ').take(180)
            slog("  页面文字: $text")
            if (isLoginPage(r.body)) slog("  → 判定：这是登录页")
            else if (looksLoggedIn(r.body)) slog("  → 判定：当前已经是登录状态（无需再登录）")
            msg
        } catch (e: Exception) {
            val msg = "GET / 失败：${e.javaClass.simpleName}: ${e.message}"
            slog("【首页探测】$msg")
            msg
        }
    }

    // ---------------------------------------------------------------- 登录

    data class LoginOutcome(val ok: Boolean, val message: String)

    private class LoginForm(val action: String, val method: String, val fields: LinkedHashMap<String, String>)

    fun login(): LoginOutcome {
        slog("——— 开始登录 $baseUrl ———")

        val home: Resp = try {
            get("/")
        } catch (e: Exception) {
            val m = "无法连接 $baseUrl/ ：${e.javaClass.simpleName} ${e.message}"
            slog(m)
            return LoginOutcome(false, m)
        }
        pages["/"] = home.body
        slog("GET / → HTTP ${home.code}, 长度 ${home.body.length}")
        if (home.authenticate.isNotBlank()) slog("WWW-Authenticate: ${home.authenticate}")

        // 已经登录过了？
        if (looksLoggedIn(home.body)) {
            slog("首页看起来已经是后台页面，直接跳过登录")
            return LoginOutcome(true, "已处于登录状态")
        }

        // 401：固件用的是 HTTP 授权（浏览器会弹原生登录框），而不是页面表单
        if (home.code == 401 || home.authenticate.isNotBlank()) {
            if (tryHttpAuth(home.authenticate)) return LoginOutcome(true, "HTTP 授权登录成功")
        }

        val form = findLoginForm(home.body)
        var attempts = 0

        if (form != null) {
            slog("自动发现登录表单：action=${form.action} method=${form.method} 字段=${form.fields.keys.joinToString(",")}")
            for (b in buildBodies(form)) {
                attempts++
                val r = tryPost(form.action, b, form.method, "/") ?: continue
                if (accepted(r, b)) return LoginOutcome(true, "表单登录成功：${form.action}")
            }
        } else {
            slog("首页未发现 <form> 或密码框，改用内置候选接口")
        }

        val guesses = listOf(
            "/login.cgi", "/cgi-bin/login.cgi", "/login", "/goform/login",
            "/cgi-bin/login", "/login.html", "/index.cgi", "/goform/Login"
        )
        val bodies = listOf(
            "password=" + enc(password),
            "username=admin&password=" + enc(password),
            "user=admin&pwd=" + enc(password),
            "username=admin&passwd=" + enc(password)
        )
        for (g in guesses) {
            for (b in bodies) {
                attempts++
                val r = tryPost(g, b, "post", "/") ?: continue
                if (accepted(r, b)) return LoginOutcome(true, "候选接口登录成功：$g")
            }
        }

        slog("共尝试 $attempts 次登录，均未通过验证")
        logLoginPageSnapshot()
        return LoginOutcome(false, "登录未通过（尝试 $attempts 次）")
    }

    /** 401 场景：试几组常见的「用户名:密码」走 HTTP 基本授权 */
    private fun tryHttpAuth(challenge: String): Boolean {
        if (challenge.isNotBlank() && !challenge.lowercase().contains("basic")) {
            slog("  认证方式不是 Basic（$challenge），本版本未实现，跳过")
            return false
        }
        val pairs = listOf("admin" to password, "admin" to "admin", "root" to password)
        for ((u, p) in pairs) {
            authHeader = "Basic " + android.util.Base64.encodeToString(
                "$u:$p".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            val r = try {
                get("/")
            } catch (e: Exception) {
                slog("  基本授权 $u:*** 异常: ${e.message}")
                null
            }
            if (r != null) {
                slog("  基本授权 $u:*** → HTTP ${r.code}, 长度 ${r.body.length}")
                if (r.code == 200 && looksLoggedIn(r.body)) {
                    pages["/"] = r.body
                    slog("  → 基本授权通过")
                    return true
                }
            }
            authHeader = null
        }
        return false
    }

    /** 登录失败时把登录页 HTML 与表单结构原样记进日志，诊断报告里就能看到真实表单 */
    private fun logLoginPageSnapshot() {
        val h = pages["/"] ?: return
        if (h.isBlank()) return
        slog("——— 登录页原始 HTML（前 2500 字符）———")
        slog(h.take(2500))
        try {
            val doc = Jsoup.parse(h, baseUrl + "/")
            val forms = doc.select("form")
            slog("——— 发现的表单：${forms.size} 个 ———")
            for (f in forms) {
                val fields = f.select("input, select, textarea, button").joinToString(", ") {
                    it.tagName() + "[type=" + it.attr("type") + " name=" + it.attr("name") + "]"
                }
                slog("  <form action=${f.attr("action")} method=${f.attr("method")}> $fields")
            }
            val scripts = doc.select("script[src]").joinToString(", ") { it.attr("src") }
            if (scripts.isNotBlank()) slog("  引用的 JS: $scripts")
            val inlineJs = doc.select("script:not([src])").joinToString(" ") { it.data() }
            if (inlineJs.contains("password", true) && inlineJs.length < 4000) {
                slog("——— 页面内联 JS（含 password）———")
                slog(inlineJs.take(2500))
            }
        } catch (e: Exception) {
            slog("  解析登录页结构失败: ${e.message}")
        }
    }

    private fun tryPost(url: String, body: String, method: String, referer: String): Resp? {
        return try {
            val r = if (method.equals("get", true)) {
                get(if (url.contains("?")) "$url&$body" else "$url?$body")
            } else {
                post(url, body, referer)
            }
            slog(
                "  POST $url  body=[$body] → HTTP ${r.code}, 长度 ${r.body.length}" +
                        (r.location?.let { ", Location=$it" } ?: "") +
                        (if (r.setCookie.isNotBlank()) ", Set-Cookie=${r.setCookie.take(120)}" else "")
            )
            if (r.body.isNotBlank() && r.body.length < 3000) {
                val snip = HtmlParser.toLines(r.body).replace('\n', ' ').take(160)
                if (snip.isNotBlank()) slog("     响应文字: $snip")
            }
            postLog.add(
                "POST $url  body=[$body]  → HTTP ${r.code}" +
                        (r.location?.let { "  Location=$it" } ?: "") +
                        "\n" + r.body.take(6000)
            )
            while (postLog.size > 3) postLog.removeAt(0)
            r
        } catch (e: Exception) {
            slog("  POST $url 失败：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** 判断这次登录是否真的成功了：再看一眼首页是不是还是登录页 */
    private fun accepted(r: Resp, bodySent: String): Boolean {
        val t = HtmlParser.toLines(r.body)
        for (b in BAD_WORDS) {
            if (t.contains(b)) {
                slog("     → 服务器返回「$b」，密码或字段名不对")
                return false
            }
        }
        if (looksLoggedIn(r.body)) {
            slog("     → 验证通过：POST 响应已是后台页面")
            return true
        }
        val home = try {
            get("/")
        } catch (e: Exception) {
            slog("     → 复检首页异常: ${e.message}")
            return false
        }
        pages["/"] = home.body
        if (looksLoggedIn(home.body)) {
            slog("     → 验证通过：首页已不是登录页")
            return true
        }
        slog("     → 验证失败：首页仍是登录页")
        return false
    }

    private fun isLoginPage(html: String): Boolean {
        if (html.isBlank()) return false
        val low = html.lowercase()
        val t = HtmlParser.toLines(html)
        // 已经能点到「退出/注销」，肯定不是登录页
        val hasLogout = low.contains("logout") || low.contains("signout") ||
                t.contains("退出") || t.contains("注销") || t.contains("登出")
        if (hasLogout) return false
        if (t.contains("请输入密码") || t.contains("需要授权")) return true
        if (low.contains("type=\"password\"") || low.contains("type=password") ||
            low.contains("type='password'") || low.contains("type=\"pwd\"")
        ) {
            if (low.contains("<form")) return true
        }
        if (t.length < 1200 && t.contains("密码") && t.contains("登录")) return true
        return false
    }

    private fun looksLoggedIn(html: String): Boolean {
        if (html.isBlank()) return false
        if (isLoginPage(html)) return false
        val t = HtmlParser.toLines(html)
        var hit = 0
        for (k in KEYWORDS) if (t.contains(k)) hit++
        if (hit >= 2) return true

        // frameset / iframe 结构：顶层页面几乎没有文字，只能看 frame 的 src
        val low = html.lowercase()
        if (low.contains("<frameset") || low.contains("<frame ") || low.contains("<iframe")) {
            var h2 = 0
            for (k in listOf("status", "signal", "system", "network", "index", "main", "menu", "overview", "state"))
                if (low.contains(k)) h2++
            if (h2 >= 2) return true
        }
        // 有退出/注销入口，基本说明已经在后台里了
        if (low.contains("logout") || low.contains("signout") ||
            t.contains("退出") || t.contains("注销") || t.contains("登出")
        ) return true
        return false
    }

    private fun isUserField(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("user") || n.contains("account") || n.contains("name")
    }

    private fun findLoginForm(html: String): LoginForm? {
        return try {
            val doc = Jsoup.parse(html, baseUrl + "/")
            var formEl = doc.select("form").firstOrNull { it.selectFirst("input[type=password]") != null }
                ?: doc.select("form").firstOrNull()
            if (formEl == null) {
                val pw = doc.selectFirst("input[type=password]")
                if (pw == null) return null
                val f = LinkedHashMap<String, String>()
                f[pw.attr("name").ifBlank { pw.attr("id") }.ifBlank { "password" }] = password
                return LoginForm(baseUrl + "/", "post", f)
            }
            val fields = LinkedHashMap<String, String>()
            for (el in formEl.select("input, select, textarea")) {
                // 少数固件只写了 id 没写 name，提交时靠 JS 按 id 取，这里都兜住
                val name = el.attr("name").ifBlank { el.attr("id") }
                if (name.isBlank()) continue
                val type = el.attr("type").lowercase()
                val v: String? = when {
                    type == "password" -> password
                    type == "checkbox" || type == "radio" ->
                        if (el.hasAttr("checked")) el.attr("value").ifBlank { "on" } else null
                    type == "submit" || type == "button" || type == "reset" ||
                            type == "image" || type == "file" -> null
                    isUserField(name) -> "admin"
                    else -> el.attr("value")
                }
                if (v != null) fields[name] = v
            }
            if (fields.values.none { it == password }) fields["password"] = password
            var action = formEl.absUrl("action")
            if (action.isBlank()) {
                val raw = formEl.attr("action")
                action = if (raw.isBlank()) baseUrl + "/" else baseUrl + "/" + raw.trimStart('/')
            }
            val method = formEl.attr("method").ifBlank { "post" }
            LoginForm(action, method, fields)
        } catch (e: Exception) {
            slog("解析登录表单异常: ${e.message}")
            null
        }
    }

    private fun buildBodies(form: LoginForm): List<String> {
        val out = ArrayList<String>()
        out.add(encodeForm(form.fields))

        val onlyPw = LinkedHashMap<String, String>()
        for ((k, v) in form.fields) if (v == password) onlyPw[k] = v
        if (onlyPw.isNotEmpty()) out.add(encodeForm(onlyPw))

        val withUser = LinkedHashMap<String, String>(form.fields)
        if (withUser.values.none { it == password }) withUser["password"] = password
        if (!withUser.containsKey("username")) withUser["username"] = "admin"
        out.add(encodeForm(withUser))

        return out.distinct()
    }

    private fun encodeForm(m: Map<String, String>): String =
        m.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    // ---------------------------------------------------------------- 抓数据

    fun fetchSignalInfo(): SignalInfo {
        val info = SignalInfo()
        val corpus = StringBuilder()
        val visited = HashSet<String>()
        var fetched = 0
        var lastGood = ""

        fun remember(url: String, body: String) {
            if (body.isBlank()) return
            pages[url] = body
            if (corpus.length < MAX_CORPUS) {
                corpus.append("\n<!-- ==== ").append(url).append(" ==== -->\n")
                corpus.append(body.take(40_000)).append('\n')
            }
        }

        fun fetchPage(u: String): String? {
            val full = abs(u)
            if (!visited.add(full)) return null
            if (fetched >= MAX_PAGES) return null
            fetched++
            return try {
                val r = get(full)
                if (r.code == 200 && r.body.isNotBlank()) {
                    val tag = if (isLoginPage(r.body)) "  [仍是登录页]" else ""
                    slog("GET $full → ${r.code}, ${r.body.length} 字节$tag")
                    remember(full, r.body)
                    r.body
                } else {
                    slog("GET $full → ${r.code}, ${r.body.length} 字节")
                    if (r.body.isNotBlank()) remember(full, r.body)
                    null
                }
            } catch (e: Exception) {
                slog("GET $full → 异常: ${e.message}")
                null
            }
        }

        fun grab(u: String) {
            val body = fetchPage(u) ?: return
            val one = HtmlParser.parsePage(body, abs(u))
            if (one.hasData() && lastGood.isBlank()) lastGood = abs(u)
            HtmlParser.merge(info, one)
        }

        slog("——— 开始抓取页面 ———")
        // 已抓过的首页先解析一遍
        pages["/"]?.let {
            val one = HtmlParser.parsePage(it, baseUrl + "/")
            if (one.hasData()) lastGood = baseUrl + "/"
            HtmlParser.merge(info, one)
        }

        for (p in SEED_PATHS) {
            grab(p)
            if (info.iccid != "--" && (info.rssi != "--" || info.rsrp != "--")) break
        }

        if (!info.hasKeyData() || info.iccid == "--") {
            val links = LinkedHashSet<String>()
            for ((_, body) in pages) links.addAll(rankLinks(body))
            var n = 0
            for (u in links) {
                if (n >= 10) break
                if (visited.contains(u)) continue
                n++
                grab(u)
                if (info.iccid != "--" && (info.rssi != "--" || info.rsrp != "--")) break
            }
        }

        if (info.iccid == "--" || !SignalInfo.isReal(info.rssi)) {
            for (u in scriptEndpoints()) {
                if (fetched >= MAX_PAGES) break
                grab(u)
            }
        }

        if (!info.hasData()) {
            slog("所有页面都未解析到字段，用整段语料再兜底解析一次")
            HtmlParser.merge(info, HtmlParser.parsePage(corpus.toString(), ""))
        }

        info.rawHtml = corpus.toString()
        if (info.sourceUrl.isBlank() && lastGood.isNotBlank()) info.sourceUrl = lastGood
        info.connected = info.hasData()
        slog("抓取结束：共请求 $fetched 个地址；" + info.summary())
        return info
    }

    private fun rankLinks(html: String): List<String> {
        val map = LinkedHashMap<String, Int>()
        try {
            val doc = Jsoup.parse(html, baseUrl + "/")
            for (el in doc.select("a[href], frame[src], iframe[src], area[href]")) {
                val isFrame = el.tagName() == "frame" || el.tagName() == "iframe"
                val attr = if (isFrame) "src" else "href"
                val raw = el.attr(attr)
                if (raw.isBlank()) continue
                val low = raw.lowercase()
                if (low.startsWith("javascript") || low.startsWith("mailto") || low.startsWith("#")) continue
                if (SKIP_EXT.any { low.contains(it) }) continue
                if (SKIP_WORDS.any { low.contains(it) }) continue
                val full = el.absUrl(attr)
                if (full.isBlank() || !full.startsWith(baseUrl)) continue
                val sc = score(full, el.text())
                val old = map[full]
                if (old == null || sc > old) map[full] = sc
            }
        } catch (e: Exception) {
            slog("解析链接异常: ${e.message}")
        }
        return map.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun score(url: String, text: String): Int {
        val s = (url + " " + text).lowercase()
        var n = 0
        for (k in listOf(
            "信号", "signal", "status", "状态", "sim", "网络", "network",
            "system", "系统", "overview", "概述", "info", "信息", "device", "设备",
            "lte", "wan", "状态信息"
        )) if (s.contains(k)) n += 10
        for (ext in listOf(".html", ".htm", ".cgi", ".asp", ".json", ".xml")) {
            if (url.lowercase().contains(ext)) n += 5
        }
        return n
    }

    /** 从页面引用的 JS 里挖后端接口 */
    private fun scriptEndpoints(): List<String> {
        val scripts = LinkedHashSet<String>()
        for ((_, body) in pages) {
            try {
                val doc = Jsoup.parse(body, baseUrl + "/")
                for (s in doc.select("script[src]")) {
                    val full = s.absUrl("src")
                    if (full.startsWith(baseUrl) && full.lowercase().endsWith(".js")) scripts.add(full)
                }
            } catch (_: Exception) {
            }
        }
        val texts = StringBuilder()
        var n = 0
        for (s in scripts) {
            if (n >= 6) break
            n++
            try {
                texts.append(get(s).body).append('\n')
            } catch (_: Exception) {
            }
        }
        val found = LinkedHashSet<String>()
        try {
            val re1 = Regex("""["'](/[A-Za-z0-9_\-/\.]{2,60}\.(?:cgi|json|xml|asp|html?|do))["']""")
            for (m in re1.findAll(texts)) found.add(m.groupValues[1])
            val re2 = Regex("""["'](/goform/[A-Za-z0-9_\-]{2,40})["']""")
            for (m in re2.findAll(texts)) found.add(m.groupValues[1])
        } catch (_: Exception) {
        }
        val list = found.filter { u ->
            !SKIP_WORDS.any { u.lowercase().contains(it) } && u != "/" && u.length > 3
        }
        if (list.isNotEmpty()) slog("从 JS 中发现候选接口: " + list.take(8).joinToString(", "))
        return list.take(6).map { baseUrl + it }
    }
}
