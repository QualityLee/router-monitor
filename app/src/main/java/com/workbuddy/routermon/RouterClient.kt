package com.workbuddy.routermon

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * USR-G805 自有 webUI 的 HTTP 客户端
 *
 * 与 USR-G781/G806 的 OpenWrt+LuCI 不同,G805:
 *  - 登录 URL:  POST /login.cgi   (或 /login.html → /login.cgi)
 *  - 字段:      username=admin  password=<密码>
 *  - 用户名固定 admin,不能改
 *  - 登录成功后 Set-Cookie 一个 session id
 *  - 状态页:    GET /status.html  或 /overview
 *  - 字段中文名: "信号强度" / "SIM卡卡号" / "IMEI" / "IMSI" / "网络" / "运营商"
 */
class RouterClient(
    private val host: String,        // 例 192.168.1.1
    private val password: String     // G805 只校验密码
) {
    private val baseUrl = "http://$host"
    private val cookieStore = HashMap<String, List<Cookie>>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val cookies = cookieStore[url.host].orEmpty()
                return cookies.filter { it.matches(url) }
            }
        })
        .build()

    /** 登录,返回 true 表示成功 */
    @Throws(IOException::class)
    fun login(): Boolean {
        // G805 登录表单:username=admin&password=xxx
        // 不同固件 action 可能略有差异,代码里按 /login.cgi 为主,/login.html 兜底
        val body = "username=admin&password=${enc(password)}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

        // 先尝试 /login.cgi(常见 CGI 入口)
        val candidates = listOf(
            "/login.cgi",
            "/cgi-bin/login.cgi",
            "/login.html"
        )

        for (path in candidates) {
            val req = Request.Builder()
                .url("$baseUrl$path")
                .post(body)
                .header("Referer", "$baseUrl/login.html")
                .header("Origin", baseUrl)
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val setCookies = resp.headers("Set-Cookie")
                    val hasSession = setCookies.any { it.contains("Session", true) || it.contains("sid", true) }
                    if (resp.isSuccessful && (hasSession || resp.isRedirect)) {
                        // 进一步验证:看能否拉到状态页
                        if (verifyLoggedIn()) return true
                    }
                }
            } catch (_: Exception) {
                // 试下一个候选
            }
        }
        return false
    }

    /** 通过抓取一个受保护页面验证是否真的登录成功 */
    private fun verifyLoggedIn(): Boolean {
        return try {
            val html = get("/status.html", allowFail = true) +
                       get("/overview.html", allowFail = true) +
                       get("/", allowFail = true)
            // 登录后页面包含这些关键字其一即可
            html.contains("信号强度") ||
            html.contains("SIM卡卡号") ||
            html.contains("信号") ||
            html.contains("Signal", true)
        } catch (_: Exception) { false }
    }

    /** 抓取信号与 SIM 信息 */
    @Throws(IOException::class)
    fun fetchSignalInfo(): SignalInfo {
        // 状态页候选
        val pages = listOf("/status.html", "/overview.html", "/", "/index.html", "/main.html")
        var combined = ""
        for (p in pages) {
            combined += get(p, allowFail = true) + "\n"
            if (combined.contains("信号强度") || combined.contains("SIM卡卡号")) break
        }

        val info = SignalInfo()
        info.rawHtml = combined

        // 信号强度 -97 dBm
        info.rssi = extractByLabel(combined, "信号强度")?.let { grabNumber(it) } ?: "--"
        // SIM卡卡号 898607B6151770265124
        info.iccid = extractByLabel(combined, "SIM卡卡号")?.trim() ?: "--"
        // IMEI
        info.imei = extractByLabel(combined, "IMEI")?.trim() ?: "--"
        // IMSI
        info.imsi = extractByLabel(combined, "IMSI")?.trim() ?: "--"
        // 网络制式(2G/3G/4G 或 TDD-LTE/FDD-LTE)
        info.networkMode = extractByLabel(combined, "网络")?.trim() ?: "--"
        // 运营商
        info.operator = extractByLabel(combined, "运营商")?.trim() ?: "--"
        // 系统时间
        info.runTime = extractByLabel(combined, "运行时间")?.trim() ?: "--"

        // 兜底:用 ICCID 格式(8986开头20位)再匹配一次
        if (info.iccid == "--") {
            val m = Pattern.compile("8986\\d{16,20}").matcher(combined)
            if (m.find()) info.iccid = m.group()
        }

        return info
    }

    @Throws(IOException::class)
    fun logout() {
        try {
            get("/logout.cgi", allowFail = true)
            get("/logout.html", allowFail = true)
        } catch (_: Exception) { }
        cookieStore.clear()
    }

    @Throws(IOException::class)
    private fun get(path: String, allowFail: Boolean = false): String {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("Referer", "$baseUrl/")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (allowFail) return ""
                throw IOException("HTTP ${resp.code} on $path")
            }
            return resp.body?.string().orEmpty()
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * G805 状态页典型结构(简化):
     *   <td>信号强度</td><td>-97 dBm</td>
     *   <td>SIM卡卡号</td><td>898607B6151770265124</td>
     * 这里做最朴素的对齐匹配:找含 label 的 td,再读下一个 td
     */
    private fun extractByLabel(html: String, label: String): String? {
        // 兼容 HTML 里可能的全角空格、&nbsp; 等
        val cleaned = html.replace("&nbsp;", " ")
            .replace("\u3000", " ")
            .replace("\r", "")
        val regex = Regex(
            "<td[^>]*>\\s*${Pattern.quote(label)}\\s*</td>\\s*<td[^>]*>([^<]+)</td>",
            RegexOption.IGNORE_CASE
        )
        return regex.find(cleaned)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun grabNumber(s: String): String {
        val m = Regex("(-?\\d+(?:\\.\\d+)?)").find(s)
        return m?.groupValues?.getOrNull(1) ?: "--"
    }
}