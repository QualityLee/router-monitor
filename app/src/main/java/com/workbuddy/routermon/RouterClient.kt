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
 *   1. GET / 并**跟随跳转壳**（USR-G805 的 / 只返回 window.location.href="index.html"）
 *   2. 用 Jsoup 自动发现最终首页里 <form> 的 action / method / 字段名
 *   3. 页面里没有表单时，抓取它引用的 JS，从里面挖 cgi-bin 下的接口路径再逐个试
 *   4. 登录是否成功不靠 Set-Cookie 名字判断，而是「POST 返回的 JSON + 复检首页」
 *   5. 若首页是 JS 单页应用（G805 新 webui 就是），静态 HTML 里没有数据，
 *      会额外对 /cgi-bin 接口做 POST 探测，并把结论指向「网页模式」
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
            "/main.html", "/network.html", "/info.html", "/device.html", "/sim.html",
            "/status.cgi", "/overview.cgi", "/cgi-bin/status.cgi", "/goform/status"
        )

        /**
         * USR-G805 真机实测（2026-09-15 诊断日志）：
         *   GET /cgi-bin/status.cgi → HTTP 200 + "Access Error: Data follows / NOT POST REQUEST"
         * 也就是这个接口**存在，而且只接受 POST**。SPA 的数据接口就在 /cgi-bin/ 下，
         * 这几个是最常见的命名，登录失败时会逐个 POST 探一遍并把响应写进报告。
         */
        private val CGI_CANDIDATES = listOf(
            "/cgi-bin/status.cgi", "/cgi-bin/status", "/cgi-bin/home.cgi",
            "/cgi-bin/baseinfo.cgi", "/cgi-bin/system.cgi",
            "/cgi-bin/network.cgi", "/cgi-bin/sim.cgi", "/cgi-bin/wireless.cgi",
            "/cgi-bin/signal.cgi", "/cgi-bin/info.cgi", "/cgi-bin/data.cgi",
            "/cgi-bin/login.cgi", "/cgi-bin/webui.cgi"
        )

        /** 登录接口返回 JSON 时，这些片段代表成功 */
        private val JSON_OK = listOf(
            "\"ret\":0", "\"ret\": 0", "\"code\":0", "\"code\": 0", "\"result\":0",
            "\"success\":true", "\"success\": true", "\"status\":0",
            "success", "ok", "true"
        )

        /** …这些代表失败 */
        private val JSON_FAIL = listOf(
            "\"ret\":1", "\"ret\":-1", "\"code\":-1", "\"code\":1", "\"success\":false",
            "\"success\": false", "\"result\":1", "password error", "login failed", "invalid"
        )

        private const val MAX_PAGES = 26
        private const val MAX_CORPUS = 400_000

        /**
         * 第三方库文件名特征。G805 首页引用了 30 个 JS，其中一大半是
         * jquery / knockout / underscore / require / bootstrap —— 里面只有框架代码，
         * 不可能有后端接口。跳过它们，把抓取配额留给真正的业务 JS。
         */
        private val JS_LIB_MARKS = listOf(
            "/lib/", "require", "jquery.", "bootstrap", "underscore", "knockout",
            "html5shiv", "respond.min", "base64"
        )

        /**
         * 名字里带这些词的业务 JS 最可能定义接口，优先抓。
         * 依据：G805 引用了 js/service.js、js/config/config.js、js/login.js、
         * js/status/statusBar.js、js/main.js、js/app.js —— 接口地址就在这几份里。
         */
        private val JS_HOT = listOf(
            "service", "config", "login", "main", "app", "status",
            "router", "util", "language", "logout", "tooltip", "menu"
        )

        private const val MAX_JS_FETCH = 16

        /**
         * 接口名里有这些词 → 只读接口，做探测性 POST 是安全的。
         *
         * 为什么要这个白名单：从 JS 里挖出来的接口是**未经审核**的，路由器上同时存在
         * /cgi-bin/wifi_set、/cgi-bin/reboot 这类**写**接口，盲打可能改坏用户的路由器配置。
         * 所以只允许对明确像"读"的接口做 POST 探测。
         */
        private val READONLY_MARKS = listOf(
            "status", "get", "info", "query", "list", "home", "state", "read",
            "overview", "detail", "stats", "statistic", "signal", "sim", "show",
            "load", "fetch", "check", "version", "traffic", "device", "network"
        )

        /** 接口名里有这些词 → 写操作，绝不做探测性请求 */
        private val WRITE_MARKS = listOf(
            "set", "save", "edit", "del", "remove", "reboot", "restart", "reset",
            "apply", "write", "update", "upgrade", "upload", "config", "submit",
            "add", "create", "modify", "change", "factory", "restore", "clear",
            "dial", "connect", "disconnect", "auth", "sms", "pin"
        )
    }

    val host: String
    val baseUrl: String
    val log = StringBuilder()

    private val cookies = LinkedHashMap<String, Cookie>()
    private val pages = LinkedHashMap<String, String>()
    private val postLog = ArrayList<String>()

    /** 成功抓到的业务 JS URL（按抓取顺序），供 jsDump() 单独输出 */
    private val jsUrls = ArrayList<String>()

    /**
     * 真正的首页。GET / 可能只是个 JS 跳转壳（G805 就是），要跟到最终页面。
     * 注意：不能写成 `= baseUrl + "/"` —— baseUrl 要到下面的 init 块里才赋值，
     * 属性初始化器的执行顺序在 init 块之前，那样拿到的是空值。所以在 init 里赋值。
     */
    private var homeUrl: String = ""

    /** 页面里没有 <form>、字段全靠 data-bind 渲染 → JS 单页应用，静态 HTML 抓不到值 */
    var spaDetected = false
        private set

    init {
        var h = hostInput.trim()
        h = h.removePrefix("https://").removePrefix("http://")
        h = h.trimEnd('/').trim()
        if (h.isEmpty()) h = "192.168.1.1"
        host = h.substringBefore('/')
        baseUrl = "http://$host"
        homeUrl = "$baseUrl/"
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
            sb.append("\n===== POST 提交/响应 =====\n").append(p).append('\n')
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

    /** GET / 探测，返回一行概览并落日志（会跟随跳转壳） */
    fun probeHome(): String {
        return try {
            val r = loadHome()
            val title = runCatching {
                val t = Jsoup.parse(r.body).title()
                if (t.isBlank()) "" else " 标题=$t"
            }.getOrDefault("")
            val msg = "HTTP ${r.code}, 长度 ${r.body.length}$title" +
                    (r.location?.let { ", Location=$it" } ?: "") +
                    (if (r.setCookie.isNotBlank()) ", Set-Cookie=${r.setCookie.take(100)}" else "") +
                    (if (r.authenticate.isNotBlank()) ", WWW-Authenticate=${r.authenticate.take(80)}" else "")
            slog("【首页探测】GET $homeUrl → $msg")
            val text = HtmlParser.toLines(r.body).replace('\n', ' ').take(180)
            slog("  页面文字: $text")
            if (isLoginPage(r.body)) slog("  → 判定：这是登录页")
            else if (looksLoggedIn(r.body)) slog("  → 判定：页面已是后台结构（是否真登录见下方「SPA」提示）")
            msg
        } catch (e: Exception) {
            val msg = "GET / 失败：${e.javaClass.simpleName}: ${e.message}"
            slog("【首页探测】$msg")
            msg
        }
    }

    /**
     * GET / 并一路跟随跳转，直到拿到真正的页面。
     *
     * USR-G805 真机实测：GET / 只返回 168 字节的壳 ——
     *     <script>window.location.href="index.html";</script>
     * 真正的后台页在 /index.html（14442 字节的 Knockout.js 单页应用）。
     * 不跟这一步，后面「找登录表单 / 判登录态 / 解析字段」全会错位。
     */
    private fun loadHome(): Resp {
        var path = "/"
        var r = get(path)
        pages[path] = r.body
        var hop = 0
        while (hop < 3) {
            val target = redirectTarget(r) ?: break
            hop++
            val next = if (target.startsWith("http")) target else "/" + target.trimStart('/')
            val nr = try {
                get(next)
            } catch (e: Exception) {
                slog("  跟随 $next 失败: ${e.message}")
                break
            }
            slog("  首页只是跳转壳 → 跟随 $next （HTTP ${nr.code}, ${nr.body.length} 字节）")
            path = next
            r = nr
            pages[path] = r.body
        }
        homeUrl = abs(path)
        if (homeUrl != baseUrl + "/") slog("  → 实际首页: $homeUrl")
        detectSpa(r.body)
        return r
    }

    /** 识别跳转目标：HTTP 3xx 的 Location / JS 的 location.href / meta refresh */
    private fun redirectTarget(r: Resp): String? {
        if (r.code in 300..399 && !r.location.isNullOrBlank()) {
            val loc = r.location!!.trim()
            return if (loc.startsWith("http")) loc else "/" + loc.trimStart('/')
        }
        if (r.body.isBlank() || r.body.length > 3000) return null
        Regex("""(?i)location\s*\.\s*(?:href|replace|assign)\s*(?:\(\s*)?=?\s*["']([^"']{1,140})["']""")
            .find(r.body)?.let {
                val v = it.groupValues[1].trim()
                if (v.isNotEmpty() && !v.startsWith("javascript")) return v
            }
        Regex("""(?is)<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["'][^"']*url\s*=\s*([^"';>\s]+)""")
            .find(r.body)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** 判断是不是 JS 单页应用：没有 <form>，字段全靠 data-bind 渲染出来 */
    private fun detectSpa(html: String) {
        if (html.isBlank() || spaDetected) return
        val low = html.lowercase()
        val binds = Regex("data-bind\\s*=").findAll(low).count()
        if (!low.contains("<form") &&
            (binds >= 3 || low.contains("knockout") || low.contains("jquery.js"))
        ) {
            spaDetected = true
            slog("  ⚠ 检测到 JS 单页应用：没有 <form>，$binds 处 data-bind，值由 JS 运行时填充")
            slog("     → 静态 HTML 里没有信号/ICCID 的值，必须走「从 JS 挖接口」或「网页模式」")
        }
    }

    // ---------------------------------------------------------------- 登录

    data class LoginOutcome(val ok: Boolean, val message: String)

    private class LoginForm(val action: String, val method: String, val fields: LinkedHashMap<String, String>)

    fun login(): LoginOutcome {
        slog("——— 开始登录 $baseUrl ———")

        val home: Resp = try {
            loadHome()
        } catch (e: Exception) {
            val m = "无法连接 $baseUrl/ ：${e.javaClass.simpleName} ${e.message}"
            slog(m)
            return LoginOutcome(false, m)
        }
        slog("GET $homeUrl → HTTP ${home.code}, 长度 ${home.body.length}")
        if (home.authenticate.isNotBlank()) slog("WWW-Authenticate: ${home.authenticate}")

        // 401：固件用的是 HTTP 授权（浏览器会弹原生登录框），而不是页面表单
        if (home.code == 401 || home.authenticate.isNotBlank()) {
            if (tryHttpAuth(home.authenticate)) return LoginOutcome(true, "HTTP 授权登录成功")
        }

        // 已经登录过了？注意 SPA 固件的首页在「未登录」时也长得像后台，
        // 所以这里只是跳过登录流程，真正的判定交给抓数据那一步。
        if (looksLoggedIn(home.body)) {
            slog("首页结构看起来已经是后台页面")
            return if (spaDetected)
                LoginOutcome(true, "SPA 固件：无法用纯 HTML 判断登录态，直接进入抓取（推荐用「网页模式」）")
            else
                LoginOutcome(true, "已处于登录状态")
        }

        val form = findLoginForm(home.body)
        var attempts = 0

        if (form != null) {
            slog("自动发现登录表单：action=${form.action} method=${form.method} 字段=${form.fields.keys.joinToString(",")}")
            for (b in buildBodies(form)) {
                attempts++
                val r = tryPost(form.action, b, form.method, homeUrl) ?: continue
                if (accepted(r, b)) return LoginOutcome(true, "表单登录成功：${form.action}")
            }
        } else {
            slog("首页未发现 <form> 或密码框，改用内置候选接口")
        }

        // SPA 固件的接口藏在页面引用的 JS 里，先挖出来再挨个试
        val mined = mineEndpoints()

        // 安全闸：挖出来的接口是未审核的，像写操作的绝不能拿来 POST（可能改坏路由器配置）
        val guesses = ArrayList<String>()
        for (m in mined) {
            if (isWriteEndpoint(m)) {
                slog("  跳过写接口 $m（不用于登录尝试）")
                continue
            }
            guesses.add(m)
        }
        guesses.addAll(listOf(
            "/login.cgi", "/cgi-bin/login.cgi", "/login", "/goform/login",
            "/cgi-bin/login", "/login.html", "/index.cgi", "/goform/Login"
        ))
        val bodies = listOf(
            "password=" + enc(password),
            "username=admin&password=" + enc(password),
            "user=admin&pwd=" + enc(password),
            "username=admin&passwd=" + enc(password)
        )
        for (g in guesses) {
            for (b in bodies) {
                attempts++
                val r = tryPost(g, b, "post", homeUrl) ?: continue
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

    /** 登录失败时把登录页 HTML 与表单/脚本结构原样记进日志 */
    private fun logLoginPageSnapshot() {
        val h = pages[homeUrl] ?: pages["/"] ?: return
        if (h.isBlank()) return
        slog("——— 登录页原始 HTML（$homeUrl，前 2500 字符）———")
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
            val pws = doc.select("input[type=password]")
            if (pws.isNotEmpty()) {
                slog("——— 密码输入框 ${pws.size} 个 ———")
                for (p in pws) {
                    // 用 parents() 往上找 <form>，而不是 p.form() —— jsoup 1.17 的 Element 没这个方法
                    val owner = p.parents().firstOrNull { it.tagName() == "form" }
                    val where = if (owner == null) "无（靠 JS 提交）"
                    else owner.attr("action").ifBlank { "(action 为空)" }
                    slog("  input#${p.attr("id")} name=${p.attr("name")} class=${p.attr("class")} 所在表单=$where")
                }
            }
            val scripts = doc.select("script[src]").joinToString(", ") { it.attr("src") }
            if (scripts.isNotBlank()) slog("  引用的 JS: $scripts")
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
            while (postLog.size > 14) postLog.removeAt(0)
            r
        } catch (e: Exception) {
            slog("  POST $url 失败：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** 判断这次登录是否真的成功了 */
    private fun accepted(r: Resp, bodySent: String): Boolean {
        val t = HtmlParser.toLines(r.body)
        for (b in BAD_WORDS) {
            if (t.contains(b)) {
                slog("     → 服务器返回「$b」，密码或字段名不对")
                return false
            }
        }
        // 接口返回 JSON：直接看字段判断，这是 SPA 固件唯一可靠的判据
        val raw = r.body.trim()
        if (raw.startsWith("{") || raw.startsWith("[")) {
            val low = raw.lowercase()
            val fail = JSON_FAIL.any { low.contains(it) }
            val ok = JSON_OK.any { low.contains(it) }
            if (fail && !ok) {
                slog("     → JSON 响应判定失败")
                return false
            }
            if (ok) {
                slog("     → 验证通过：JSON 响应 $ok")
                return true
            }
        }
        if (!spaDetected && looksLoggedIn(r.body)) {
            slog("     → 验证通过：POST 响应已是后台页面")
            return true
        }
        if (spaDetected) {
            // SPA 的首页在登录前后长得一样，无法用 HTML 复检，只能认 JSON
            slog("     → SPA 固件，HTML 无法复检登录态（响应长度 ${r.body.length}）")
            return false
        }
        val home = try {
            get(homeUrl)
        } catch (e: Exception) {
            slog("     → 复检首页异常: ${e.message}")
            return false
        }
        pages[homeUrl] = home.body
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
        // 已经能点到「退出/注销」，肯定不是登录页。
        // 注意：SPA 的 index.html 里 data-trans="logout" / data-bind="click:logout"
        // 未登录时也照样存在，所以原文匹配 "logout" 不算数（见真机日志的误判）。
        if (t.contains("退出登录") || t.contains("注销") || t.contains("登出") || t.contains("退出系统")) return false
        if (low.contains("signout")) return false
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
        // 有退出/注销入口，基本说明已经在后台里了。
        // 但 SPA 的 index.html 里 data-trans="logout" 未登录时也存在，
        // 所以只有当页面里根本没有密码输入框时，才拿这个当证据。
        val hasPwBox = low.contains("type=\"password\"") || low.contains("type=password") ||
                low.contains("type='password'")
        if (!hasPwBox && (low.contains("logout") || low.contains("signout"))) return true
        if (t.contains("退出登录") || t.contains("注销") || t.contains("登出")) return true
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
                return LoginForm(homeUrl, "post", f)
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
                action = if (raw.isBlank()) homeUrl else baseUrl + "/" + raw.trimStart('/')
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

        if (!info.hasKeyData()) {
            for (u in mineEndpoints()) {
                if (fetched >= MAX_PAGES) break
                if (visited.contains(u)) continue
                grab(u)
            }
        }

        if (!info.hasData()) {
            probeCgiInterfaces()
            val probeCorpus = postLog.joinToString("\n")
            if (probeCorpus.isNotBlank()) {
                HtmlParser.merge(info, HtmlParser.parsePage(probeCorpus, "probe"))
            }
            if (!info.hasData()) {
                slog("所有页面都未解析到字段，用整段语料再兜底解析一次")
                HtmlParser.merge(info, HtmlParser.parsePage(corpus.toString(), ""))
            }
        }

        if (!info.hasData() && spaDetected) {
            slog("★ 结论：这是 JS 单页应用，纯 HTTP 请求拿不到数据。")
            slog("  → 请点【网页模式(推荐)】，让真浏览器内核跑完页面 JS 再抓取。")
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

    /** 收集页面里引用的同源 JS 地址（全部，含第三方库） */
    private fun collectScripts(): List<String> {
        val scripts = LinkedHashSet<String>()
        for ((pageUrl, body) in pages) {
            if (body.isBlank() || pageUrl.startsWith("POST ")) continue
            try {
                val doc = Jsoup.parse(body, pageUrl)
                for (s in doc.select("script[src]")) {
                    val full = s.absUrl("src")
                    if (full.startsWith(baseUrl) && full.endsWith(".js", true)) scripts.add(full)
                }
            } catch (_: Exception) {
            }
        }
        return scripts.toList()
    }

    /**
     * 这个接口像不像「写操作」？像的就绝不做探测性请求。
     *
     * 从 JS 里挖出来的接口是未经审核的，路由器上同时有 /cgi-bin/wifi_set、
     * /cgi-bin/reboot 这类写接口 —— 盲打可能直接改坏用户的路由器配置。
     * 所以探测前必须过这一关：只允许明显「读」的接口。
     */
    private fun isWriteEndpoint(u: String): Boolean {
        val low = u.lowercase()
        // 明显是读的，优先放行（"get_status" 里既有 get 又有 status，别被 write 规则误伤）
        if (READONLY_MARKS.any { low.contains(it) } &&
            !low.contains("_set") && !low.contains("/set") && !low.contains("save")
        ) return false
        return WRITE_MARKS.any { low.contains(it) }
    }

    /**
     * 抓取页面引用的 JS 并从中挖出后端接口。
     *
     * USR-G805 新 webui 是 RequireJS + Knockout 单页应用：静态 HTML 里一个数据都没有，
     * 接口地址全写在 js/service.js、js/config/config.js、js/login.js 这些**业务 JS** 里。
     * 关键认识（来自 2026-09-15 的网页模式日志）：这些 JS 都是**静态文件、不需要登录**，
     * 所以哪怕登录没成，也能先把它们拉下来，把接口清单完整挖出来。
     *
     * 抓到的 JS 正文存进 pages，再由 jsDump() 单独成段输出到报告。
     */
    fun mineEndpoints(): List<String> {
        val scripts = collectScripts()
        if (scripts.isEmpty()) {
            slog("页面里没有引用同源 JS 文件，无法从 JS 挖接口")
            return emptyList()
        }

        // 真机实测首页引用 30 个 JS，其中大部分是框架。只抓业务 JS，配额留给真正有用的。
        val appJs = scripts.filter { s -> JS_LIB_MARKS.none { s.lowercase().contains(it) } }
        val ordered = appJs.sortedByDescending { s ->
            val hit = JS_HOT.indexOfFirst { s.lowercase().contains(it) }
            if (hit < 0) 0 else JS_HOT.size - hit
        }
        slog("页面引用 ${scripts.size} 个 JS，其中业务 JS ${appJs.size} 个：")
        for (s in ordered) slog("    ${JS_HOT.firstOrNull { s.lowercase().contains(it) } ?: "-"}  $s")

        val texts = StringBuilder()
        var n = 0
        for (s in ordered) {
            if (n >= MAX_JS_FETCH) break
            n++
            if (pages.containsKey(s)) {
                texts.append(pages[s]).append('\n')
                continue
            }
            try {
                val r = get(s)
                if (r.code == 200 && r.body.isNotBlank()) {
                    pages[s] = r.body
                    jsUrls.add(s)
                    texts.append(r.body).append('\n')
                    slog("GET $s → ${r.code}, ${r.body.length} 字节 ✓ 已存入报告")
                } else {
                    slog("GET $s → ${r.code}, ${r.body.length} 字节")
                }
            } catch (e: Exception) {
                slog("GET $s → 异常: ${e.message}")
            }
        }

        val found = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""["'](/cgi-bin/[A-Za-z0-9_\-/\.]{1,70}?\.(?:cgi|json|xml|asp|do|txt))["']"""),
            Regex("""["'](/goform/[A-Za-z0-9_\-]{2,40})["']"""),
            Regex("""["'](/(?:cgi-bin|cgi|api|webui|action)/[A-Za-z0-9_\-/\.]{1,70})["']"""),
            Regex("""(?:url|action|path)\s*[:=]\s*["'](/?[A-Za-z0-9_\-/\.]{2,70}\.(?:cgi|json|xml|do))["']"""),
            // G805 这类把接口写成 "xxx.cgi"（不带前导斜杠）的写法
            Regex("""["']([A-Za-z0-9_\-]{2,40}\.cgi(?:\?[A-Za-z0-9_\-=&%\.]{0,40})?)["']"""),
            // $.post / $.get / $.ajax 的第一个参数
            Regex("""\$\.(?:post|get|ajax)\s*\(\s*["']([^"']{2,80})["']""")
        )
        for (p in patterns) {
            try {
                for (m in p.findAll(texts)) found.add(m.groupValues[1])
            } catch (_: Exception) {
            }
        }
        val list = found.filter { u ->
            u.length > 3 &&
                    !u.lowercase().endsWith(".css") && !u.lowercase().endsWith(".png") &&
                    !u.lowercase().endsWith(".ico") && !u.lowercase().endsWith(".js") &&
                    !SKIP_WORDS.any { u.lowercase().contains(it) }
        }

        // 没有前导斜杠的 .cgi 补一个 /cgi-bin/ 版本（G805 的接口目录就是 /cgi-bin/）
        val norm = LinkedHashSet<String>()
        for (u in list) {
            norm.add(u)
            if (!u.startsWith("/") && u.lowercase().contains(".cgi")) norm.add("/cgi-bin/" + u)
        }

        if (norm.isEmpty()) {
            slog("看了 ${n} 个业务 JS，没挖到 /cgi-bin 之类的接口路径")
        } else {
            val readable = norm.filter { !isWriteEndpoint(it) }
            slog("★ 从 JS 挖到候选接口 ${norm.size} 个（其中看似只读、可安全探测的 ${readable.size} 个）：")
            slog("    只读: " + readable.take(16).joinToString(", "))
            val writes = norm.filter { isWriteEndpoint(it) }
            if (writes.isNotEmpty()) slog("    写操作（不探测）: " + writes.take(8).joinToString(", "))
        }
        return norm.toList()
    }

    /**
     * 业务 JS 源码单独成段输出。
     *
     * 这是本轮最重要的新增：G805 的接口地址、参数名、返回结构全写在这些 JS 里，
     * 而它们都是静态文件、**不需要登录**就能取到 —— 拿到它们等于拿到了接口说明书。
     * 只输出业务 JS（跳过 jquery / knockout 这些框架），每份截 maxPer。
     */
    fun jsDump(maxPer: Int = 25000, maxFiles: Int = 12): String {
        val picked = jsUrls.filter { pages.containsKey(it) }
        if (picked.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n===== 业务 JS 源码（后端接口地址就写在里面）=====\n")
        var n = 0
        for (u in picked) {
            if (n >= maxFiles) break
            n++
            val b = pages[u] ?: continue
            sb.append("\n----- ").append(u).append("  (长度 ").append(b.length).append(") -----\n")
            sb.append(if (b.length > maxPer) b.substring(0, maxPer) + "\n...[已截断]" else b)
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 对 /cgi-bin 下的 cgi 接口逐个 POST 探一探，并把响应写进报告。
     *
     * 依据：真机日志里 GET /cgi-bin/status.cgi 返回 200 + "NOT POST REQUEST"，
     * 说明这个接口存在、而且只认 POST —— 数据接口很可能就在这一批里。
     */
    private fun probeCgiInterfaces() {
        slog("——— POST 探测 /cgi-bin 接口 ———")
        val bodies = listOf(
            "",
            "{}",
            "username=admin&password=" + enc(password)
        )
        var hits = 0
        var skipped = 0
        for (c in CGI_CANDIDATES) {
            // 安全闸：像写操作的接口一律不碰，避免改坏路由器配置
            if (isWriteEndpoint(c)) {
                skipped++
                slog("  跳过 $c（名字像写操作，不做探测性 POST）")
                continue
            }
            for (b in bodies) {
                val r = try {
                    post(c, b, homeUrl)
                } catch (e: Exception) {
                    continue
                }
                val txt = HtmlParser.toLines(r.body).replace('\n', ' ').take(160)
                slog("  POST $c  body=[${if (b.isEmpty()) "(空)" else b.take(40)}] → HTTP ${r.code}, ${r.body.length} 字节")
                if (txt.isNotBlank()) slog("     $txt")
                val looksReal = r.code == 200 && r.body.length > 30 &&
                        !r.body.contains("Access Error") && !r.body.contains("Document Error") &&
                        !r.body.contains("NOT POST REQUEST")
                if (looksReal) {
                    hits++
                    postLog.add("POST $c  body=[$b]  → HTTP ${r.code}\n" + r.body.take(4000))
                }
            }
        }
        if (hits == 0) slog("  这批接口都没给出有效响应（都返回了错误页），跳过写接口 $skipped 个")
        else slog("  有 $hits 个接口给出了非错误响应，已记入下方「POST 提交/响应」；跳过写接口 $skipped 个")
    }
}
