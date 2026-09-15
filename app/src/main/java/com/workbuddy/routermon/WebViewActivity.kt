package com.workbuddy.routermon

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网页模式 —— 这次对付 USR-G805 新 webui 的**主力方案**。
 *
 * 实测结论：G805 的后台是 Knockout.js 单页应用，静态 HTML 里一个值都没有，
 * 信号/ICCID 全靠页面 JS 通过 AJAX 从 cgi-bin 下的接口拉。纯 HTTP 客户端永远抓不到。
 *
 * 所以这里让真浏览器内核把页面 JS 跑完，然后：
 *   1. 注入拦截器，记录每一次 XHR / fetch / jQuery.ajax 的
 *      URL、请求体、状态码、响应体 —— 这是拿到真实接口的唯一可靠办法
 *   2. 自动填登录框（含可见性判断）并点「登录」，失败自动重试
 *   3. 登录后遍历所有 #hash 导航页，逐页抓取**渲染后**的 DOM 与文本
 *   4. 导出 = 接口记录 + 页面快照 + localStorage/Cookie，写入 /sdcard/routermon_webview.txt
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        /** 网页模式抓到的结果，回主界面时取用 */
        @Volatile
        var lastInfo: SignalInfo? = null

        private const val UA =
            "Mozilla/5.0 (Linux; Android 5.1; rv:1.0) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/39.0 Mobile Safari/537.36"

        private const val MAX_LOGIN_TRIES = 6

        /**
         * 后台页面的专有词。只在**渲染后的文本**（innerText，不含 display:none 的部分）
         * 里同时命中 2 个以上才认定已登录 —— 静态 HTML 里这些词再全也不算数，
         * 上一版就是被静态模板里的 logout/menu 骗了。
         */
        private val LOGGED_WORDS = listOf(
            "概览", "状态信息", "组网详情", "流量统计", "远程管理", "接入设备",
            "无线设置", "外网设置", "静态路由", "网络诊断", "防火墙",
            "已连接", "运营商", "信号强度", "重启"
        )
    }

    private lateinit var web: WebView
    private lateinit var tvResult: TextView
    private var ip = "192.168.1.1"
    private var pwd = "admin"
    private var permAsked = false

    /** 累积的页面快照 */
    private val snapshots = StringBuilder()
    private val netRecords = ArrayList<String>()
    private var navHashes: MutableList<String> = ArrayList()
    private var tourIndex = 0

    private var loggedIn = false
    private var loginTries = 0
    private var touring = false
    private var done = false
    private var lastReport = ""

    /** 主文档被拦截并注入钩子的次数。0 = 钩子压根没插进去，接口记录为空就怪不到页面上 */
    @Volatile
    private var intercepted = 0
    private var lastHookInfo = "未发生"

    /** 页面上 __wbHooked 是否真的存在 */
    private var hookSeen = false

    /** 见到过多少条接口记录（用来判「钩子装了但页面一个请求都没发」） */
    private var requestsSeen = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        ip = intent.getStringExtra("ip")?.trim().orEmpty().ifEmpty { "192.168.1.1" }
        pwd = intent.getStringExtra("pwd").orEmpty().ifEmpty { "admin" }
        web = findViewById(R.id.web)
        tvResult = findViewById(R.id.tv_result)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.allowFileAccess = true

        web.webViewClient = object : WebViewClient() {

            /**
             * 在主文档**到达内核之前**把拦截器插进 <head>。
             *
             * 为什么非这么干不可（2026-09-15 网页模式日志的教训）：
             * G805 首页是 RequireJS + Knockout，所有业务模块都是同步注入的 <script>，
             * 它们会阻塞 load 事件 —— onPageFinished 触发时，页面的首次鉴权 AJAX
             * **早就打完了**。上一版正是在 onPageFinished 里挂拦截器，结果报告里
             * 「网络请求记录（0 条）」，什么都看不到。
             * 改成拦截主文档、改完再交给内核，就能保证钩子在**任何**页面脚本之前执行。
             *
             * 只拦主文档；子资源一概放行；任何异常都返回 null 让内核正常加载，
             * 宁可漏钩子也绝不让页面白屏。
             */
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                if (done || request == null) return null
                try {
                    if (!request.isForMainFrame) return null
                    val url = request.url?.toString() ?: return null
                    if (!url.startsWith("http")) return null
                    val accept = request.requestHeaders?.get("Accept") ?: ""
                    // 只要 HTML 主文档，js/css/图片 一概不碰
                    if (accept.isNotEmpty() && !accept.contains("text/html")) return null

                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 12000
                    conn.setRequestProperty("User-Agent", UA)
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                    CookieManager.getInstance().getCookie(url)?.let {
                        conn.setRequestProperty("Cookie", it)
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val raw = stream?.readBytes() ?: return null
                    val html = String(raw, Charsets.UTF_8)
                    // 不是 HTML（二进制 / JSON / 空壳）→ 交回内核自己处理
                    if (!Regex("(?i)<html|<body|<head|<form|<meta").containsMatchIn(html)) return null

                    // 把响应里带的 Cookie 同步给 WebView，保证后续 XHR 会话一致
                    try {
                        val sc = conn.headerFields?.get("Set-Cookie")
                        if (sc != null) for (c in sc) CookieManager.getInstance().setCookie(url, c)
                    } catch (_: Exception) {
                    }

                    intercepted++
                    lastHookInfo = "OK 已注入（主文档 ${html.length} 字节）"
                    val body = injectHook(html).toByteArray(Charsets.UTF_8)
                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(body))
                } catch (e: Exception) {
                    lastHookInfo = "失败: ${e.javaClass.simpleName} ${e.message}"
                    return null
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (done) return
                // 兜底：万一主文档没被拦到（走了缓存之类），这里再补挂一次（幂等）
                eval(hookJs(), null)
                if (touring) return
                if (url == null || !url.startsWith("http")) return
                if (!loggedIn) {
                    loginTries = 0
                    checkAndLogin()
                }
            }
        }

        findViewById<Button>(R.id.btn_relogin).setOnClickListener { restart() }
        findViewById<Button>(R.id.btn_wv_login).setOnClickListener {
            loggedIn = false
            loginTries = 0
            checkAndLogin()
        }
        findViewById<Button>(R.id.btn_grab).setOnClickListener { snapshot("手动抓取", null) }
        findViewById<Button>(R.id.btn_export).setOnClickListener { exportNow() }

        tvResult.setOnClickListener { copyReport() }
        setStatus("正在打开 http://$ip/ ...\n会自动登录 -> 遍历导航页 -> 抓数据；\n完成后点【导出接口记录】把内容发我。")
        web.loadUrl("http://$ip/")
    }

    // ------------------------------------------------------------------ 工具

    private fun setStatus(t: String) {
        runOnUiThread { tvResult.text = t }
    }

    private fun appendResult(t: String) {
        runOnUiThread {
            tvResult.text = (tvResult.text.toString() + "\n" + t).lines().takeLast(12).joinToString("\n")
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun eval(js: String, cb: ((String) -> Unit)? = null) {
        try {
            web.evaluateJavascript(js) { v -> cb?.invoke(jsStr(v)) }
        } catch (e: Exception) {
            appendResult("执行 JS 失败: ${e.message}")
        }
    }

    /** evaluateJavascript 回来的是 JSON 字面量，字符串会带引号 */
    private fun jsStr(v: String?): String {
        if (v == null) return ""
        val t = v.trim()
        if (t.isEmpty() || t == "null") return ""
        if (t.startsWith("\"")) {
            return try {
                JSONTokener(t).nextValue().toString()
            } catch (e: Exception) {
                t
            }
        }
        return t
    }

    private fun restart() {
        done = false
        loggedIn = false
        loginTries = 0
        touring = false
        tourIndex = 0
        navHashes = ArrayList()
        snapshots.setLength(0)
        netRecords.clear()
        setStatus("重新打开 http://$ip/ ...")
        web.loadUrl("http://$ip/")
    }

    // ------------------------------------------------------------------ 1. 拦截器

    /** 把钩子脚本插到 <head> 之后，确保它先于页面里的任何脚本执行 */
    private fun injectHook(html: String): String {
        val tag = "<script>" + hookJs() + "</script>"
        val m = Regex("(?i)<head[^>]*>").find(html)
        if (m != null) {
            val at = m.range.last + 1
            return html.substring(0, at) + tag + html.substring(at)
        }
        return "<html><head>" + tag + "</head>" + html
    }

    /**
     * 注入 XHR / fetch / jQuery.ajax 拦截器 + JS 错误收集。
     * 全部用 ES5 —— 工控机 WebView 是 Chrome 39，不能用 let/const/箭头/模板串。
     *
     * 错误收集是这一版新加的：真机日志里 Knockout 从未 applyBindings、
     * 24 个页面可见文本全是 0 字，但「为什么」完全看不到。有了 window.onerror
     * 和 console，下次就能直接看到是哪个文件哪一行炸的。
     */
    private fun hookJs(): String = """
        (function(){
          try{
            if(window.__wbHooked){ return 'already'; }
            window.__wbHooked = 1;
            window.__wbNet = [];
            window.__wbErr = [];
            var err = function(s){
              try{
                window.__wbErr.push((''+s).substring(0,600));
                if(window.__wbErr.length > 60){ window.__wbErr.shift(); }
              }catch(x){}
            };
            window.onerror = function(msg, src, line, col){
              err('JS错误: ' + msg + '  @ ' + src + ' : ' + line + ' : ' + col);
              return false;
            };
            try{
              var ce = console.error;
              var cw = console.warn;
              console.error = function(){
                err('console.error: ' + Array.prototype.join.call(arguments, ' '));
                try{ ce.apply(console, arguments); }catch(x){}
              };
              console.warn = function(){
                err('console.warn: ' + Array.prototype.join.call(arguments, ' '));
                try{ cw.apply(console, arguments); }catch(x){}
              };
            }catch(x){}

            window.__wbClip = function(s,n){
              s = (s===undefined||s===null) ? '' : (''+s);
              return s.length > n ? s.substring(0,n) : s;
            };
            var rec = function(e){ try{ window.__wbNet.push(e); if(window.__wbNet.length>200){ window.__wbNet.shift(); } }catch(x){} };

            var XO = XMLHttpRequest.prototype.open;
            var XS = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m,u){
              this.__wbM = m; this.__wbU = u;
              return XO.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function(b){
              var self = this;
              var e = {k:'xhr', m:this.__wbM, u:''+this.__wbU, req:window.__wbClip(b,2000), s:'?', resp:''};
              rec(e);
              try{
                this.addEventListener('load', function(){
                  e.s = self.status;
                  try{ e.resp = window.__wbClip(self.responseText, 5000); }catch(x){}
                });
              }catch(x){}
              return XS.apply(this, arguments);
            };

            if(window.fetch){
              var f0 = window.fetch;
              window.fetch = function(u,o){
                var e = {k:'fetch', m:(o&&o.method)||'GET', u:''+u, req:o?window.__wbClip(o.body,2000):'', s:'?', resp:''};
                rec(e);
                return f0.apply(this, arguments).then(function(r){
                  e.s = r.status;
                  try{ r.clone().text().then(function(t){ e.resp = window.__wbClip(t,5000); }); }catch(x){}
                  return r;
                });
              };
            }

            if(window.jQuery && window.jQuery.ajaxPrefilter){
              try{
                window.jQuery.ajaxPrefilter(function(o, oo, jq){
                  var e = {k:'jq', m:''+(o.type||'GET'), u:''+(o.url||''), req:window.__wbClip(o.data,2000), s:'?', resp:''};
                  rec(e);
                  try{
                    jq.always(function(d, st, xhr){
                      try{ e.s = xhr ? xhr.status : '?'; }catch(x){}
                      try{ e.resp = window.__wbClip((typeof d === 'string') ? d : JSON.stringify(d), 5000); }catch(x){}
                    });
                  }catch(x){}
                });
              }catch(x){}
            }
            return 'hooked';
          }catch(e){ return 'hookerr:'+e; }
        })();
    """.trimIndent()

    // ------------------------------------------------------------------ 2. 登录

    private fun stateJs(): String = """
        (function(){
          try{
            var vis = function(el){
              try{
                if(!el) return false;
                if(el.offsetParent !== null) return true;
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              }catch(e){ return false; }
            };
            var pws = document.querySelectorAll('input[type=password]');
            var anyPw = false;
            for(var i=0;i<pws.length;i++){ if(vis(pws[i])){ anyPw = true; break; } }
            var txt = '';
            try{ txt = document.body ? (document.body.innerText || '') : ''; }catch(e){}
            return JSON.stringify({
              pw: anyPw,
              pwAll: pws.length,
              logout: vis(document.getElementById('logoutlink')),
              menu: vis(document.getElementById('main_left_con')),
              sbar: vis(document.getElementById('statusBar')),
              txt: txt.substring(0,3000),
              hash: location.hash || '',
              hooked: (typeof window.__wbHooked !== 'undefined'),
              net: (window.__wbNet ? window.__wbNet.length : -1),
              err: (window.__wbErr ? window.__wbErr.length : -1)
            });
          }catch(e){ return JSON.stringify({err2:''+e}); }
        })();
    """.trimIndent()

    private fun loginJs(): String = """
        (function(){
          try{
            var PWD = "@@PWD@@";
            var vis = function(el){
              try{
                if(!el) return false;
                if(el.offsetParent !== null) return true;
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              }catch(e){ return false; }
            };
            var fire = function(el){
              try{ el.dispatchEvent(new Event('input',{bubbles:true})); }catch(e){}
              try{ el.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){}
              try{ el.dispatchEvent(new Event('keyup',{bubbles:true})); }catch(e){}
            };
            var WORDS = ['登录','登陆','确定','提交','立即登录','登 录','login','signin','sign in','submit','ok','enter'];
            var isBtn = function(s){
              s = (s||'').replace(/\s+/g,'').toLowerCase();
              for(var i=0;i<WORDS.length;i++){ if(s === WORDS[i]){ return true; } }
              return false;
            };

            var all = document.querySelectorAll('input[type=password]');
            var pw = null;
            for(var i=0;i<all.length;i++){ if(vis(all[i])){ pw = all[i]; break; } }
            if(!pw && all.length){ pw = all[0]; }
            if(!pw){ return 'no-password-box'; }
            pw.value = PWD;
            fire(pw);

            var scope = pw;
            for(var d=0; d<6 && scope && scope.parentNode; d++){ scope = scope.parentNode; }
            if(!scope || !scope.querySelectorAll){ scope = document; }

            var texts = scope.querySelectorAll('input[type=text],input[type=email],input:not([type])');
            for(var j=0;j<texts.length;j++){
              var t = texts[j];
              if(t === pw) continue;
              var h = (''+(t.id||'')+' '+(t.name||'')+' '+(t.placeholder||'')+' '+(t.getAttribute('data-trans')||'')).toLowerCase();
              if(h.indexOf('password')>=0 || h.indexOf('pwd')>=0) continue;
              if(h.indexOf('user')>=0 || h.indexOf('account')>=0 || h.indexOf('name')>=0 ||
                 h.indexOf('账号')>=0 || h.indexOf('用户')>=0){
                t.value = 'admin';
                fire(t);
              }
            }

            var btns = scope.querySelectorAll('button,input[type=submit],input[type=button],a');
            for(var k=0;k<btns.length;k++){
              var b = btns[k];
              var s1 = ((b.textContent||'')+' '+(b.value||''));
              if(isBtn(s1)){ b.click(); return 'clicked:' + s1.replace(/\s+/g,''); }
            }

            var f = pw.form;
            if(f){
              try{
                if(f.onsubmit){ f.onsubmit(); return 'onsubmit'; }
                f.submit();
                return 'form-submit';
              }catch(e){ return 'form-err:'+e; }
            }

            var divs = document.querySelectorAll('div,span');
            for(var q=0;q<divs.length;q++){
              var s2 = (divs[q].textContent||'');
              if(vis(divs[q]) && isBtn(s2)){ divs[q].click(); return 'clicked-div'; }
            }
            return 'no-button';
          }catch(e){ return 'loginerr:'+e; }
        })();
    """.trimIndent().replace("\"@@PWD@@\"", JSONObject.quote(pwd))

    /**
     * 判定「已经登录」。
     *
     * ⚠️ 2026-09-15 重要修正 —— 上一版这里是：
     *     val okNow = logout || menu || (loginTries >= 2 && !anyPw)
     * 最后那个兜底在「页面压根找不到密码框」时也会成立，于是程序**一次登录都没试**
     * 就宣告 已登录 = true、直接跳去遍历导航页。
     *
     * 真机日志里那句 `已登录: true` 就是这么来的 ——
     * 而同一份日志里 Knockout 从未 applyBindings、24 个快照可见文本全是 0 字。
     *
     * 现在只认**真实渲染出来的**后台特征，什么都没有就老实继续等、继续试：
     *   - 左侧菜单 / 状态栏 / 退出入口可见（这三个都是 Knockout 渲染后才出现的）
     *   - 或页面渲染文本里同时出现 2 个以上后台专有词
     */
    private fun checkAndLogin() {
        eval(stateJs()) { st ->
            val o = runCatching { JSONObject(st) }.getOrNull()
            val anyPw = o?.optBoolean("pw", false) ?: false
            val logout = o?.optBoolean("logout", false) ?: false
            val menu = o?.optBoolean("menu", false) ?: false
            val sbar = o?.optBoolean("sbar", false) ?: false
            val net = o?.optInt("net", -1) ?: -1
            val errN = o?.optInt("err", -1) ?: -1
            val hooked = o?.optBoolean("hooked", false) ?: false
            val txt = o?.optString("txt").orEmpty()

            hookSeen = hooked
            if (net > 0) requestsSeen = net

            val hits = LOGGED_WORDS.count { txt.contains(it) }
            if (menu || logout || sbar || hits >= 2) {
                loggedIn = true
                appendResult("✓ 已登录（菜单=$menu 状态栏=$sbar 退出=$logout 关键词=$hits）接口 $net 条，JS 错误 $errN 条")
                startTour()
                return@eval
            }
            if (loginTries >= MAX_LOGIN_TRIES) {
                appendResult(
                    "✗ $MAX_LOGIN_TRIES 次后仍未见后台界面（渲染文本 ${txt.trim().length} 字，" +
                            "密码框 $anyPw）；钩子=$hooked 接口 $net 条 JS错误 $errN 条 —— 继续抓快照"
                )
                startTour()
                return@eval
            }
            loginTries++
            if (loginTries == 1 && !hooked) appendResult("⚠ 拦截器没挂上，接口记录会是空的")
            if (loginTries == 2 && txt.trim().length < 20) {
                appendResult("⚠ 页面渲染文本几乎为空（Knockout 可能没跑起来）；已记录 JS 错误 $errN 条")
            }
            eval(loginJs()) { r ->
                appendResult("第 $loginTries 次自动登录: $r")
            }
            web.postDelayed({ if (!done && !loggedIn) checkAndLogin() }, 1800)
        }
    }

    // ------------------------------------------------------------------ 3. 遍历导航页

    private fun navJs(): String = """
        (function(){
          try{
            var out = [], seen = {};
            var as = document.querySelectorAll('a[href]');
            for(var i=0;i<as.length;i++){
              var h = as[i].getAttribute('href') || '';
              if(h.length < 2 || h.charAt(0) !== '#'){ continue; }
              if(seen[h]){ continue; }
              var low = h.toLowerCase();
              if(low.indexOf('logout') >= 0 || low.indexOf('pass') >= 0){ continue; }
              seen[h] = 1;
              out.push(h);
            }
            return JSON.stringify(out);
          }catch(e){ return '[]'; }
        })();
    """.trimIndent()

    private fun clickHashJs(hash: String): String = """
        (function(){
          try{
            var a = document.querySelector('a[href="' + ${JSONObject.quote(hash)} + '"]');
            if(a){ a.click(); return 'click:'+location.hash; }
            location.hash = ${JSONObject.quote(hash)};
            return 'hash:'+location.hash;
          }catch(e){ return 'err:'+e; }
        })();
    """.trimIndent()

    private fun grabJs(): String = """
        (function(){
          try{
            var txt = '';
            try{ txt = document.body ? document.body.innerText : ''; }catch(e){}
            var html = '';
            try{ html = document.documentElement ? document.documentElement.outerHTML : ''; }catch(e){}
            var st = {};
            try{
              for(var i=0;i<localStorage.length;i++){
                var k = localStorage.key(i);
                st['ls:'+k] = (''+localStorage.getItem(k)).substring(0,200);
              }
            }catch(e){}
            try{
              for(var j=0;j<sessionStorage.length;j++){
                var k2 = sessionStorage.key(j);
                st['ss:'+k2] = (''+sessionStorage.getItem(k2)).substring(0,200);
              }
            }catch(e){}
            return JSON.stringify({
              url: location.href,
              hash: location.hash || '',
              text: (''+txt).substring(0,15000),
              html: (''+html).substring(0,50000),
              st: st,
              ck: (''+document.cookie).substring(0,2000)
            });
          }catch(e){ return JSON.stringify({err:''+e}); }
        })();
    """.trimIndent()

    private fun startTour() {
        if (touring) return
        touring = true
        eval(navJs()) { v ->
            val arr = runCatching { JSONArray(v) }.getOrNull()
            navHashes = ArrayList()
            navHashes.add("")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i, "")
                    if (h.isNotBlank() && h != "#" && !navHashes.contains(h)) navHashes.add(h)
                }
            }
            appendResult("开始遍历 ${navHashes.size} 个页面: " + navHashes.joinToString(" "))
            tourIndex = 0
            stepTour()
        }
    }

    private fun stepTour() {
        if (tourIndex >= navHashes.size) {
            finishReport()
            return
        }
        val h = navHashes[tourIndex]
        web.postDelayed({
            if (done) return@postDelayed
            if (h.isNotEmpty()) {
                eval(clickHashJs(h)) { r -> appendResult("切到 $h → $r") }
            }
            web.postDelayed({ snapshot(if (h.isEmpty()) "首屏" else h, h) }, 1600)
        }, 200)
    }

    /** 抓一页渲染后的 DOM 快照 */
    private fun snapshot(label: String, hash: String?) {
        eval(grabJs()) { v ->
            val o = runCatching { JSONObject(v) }.getOrNull()
            if (o == null) {
                appendResult("快照 $label 失败: ${v.take(120)}")
            } else {
                snapshots.append("\n===== 页面快照 [").append(label).append("] hash=")
                    .append(o.optString("hash")).append(" =====\n")
                snapshots.append("--- 可见文本 ---\n").append(o.optString("text")).append('\n')
                snapshots.append("--- 渲染后 HTML ---\n").append(o.optString("html")).append('\n')
                val st = o.optJSONObject("st")
                if (st != null && st.length() > 0) {
                    snapshots.append("--- 本地存储 ---\n").append(st.toString()).append('\n')
                }
                appendResult("已抓取 $label（文本 ${o.optString("text").length} 字）")
            }
            if (hash != null) {
                tourIndex++
                stepTour()
            }
        }
    }

    // ------------------------------------------------------------------ 4. 导出

    private fun netJs(): String = """
        (function(){
          try{
            var a = window.__wbNet || [];
            return JSON.stringify(a);
          }catch(e){ return '[]'; }
        })();
    """.trimIndent()

    /** 页面上所有 script[src]，按加载顺序 —— 用来确认到底哪些模块被加载了 */
    private fun scriptsJs(): String = """
        (function(){
          try{
            var out = [], ss = document.querySelectorAll('script[src]');
            for(var i=0;i<ss.length;i++){ out.push(ss[i].getAttribute('src')); }
            return JSON.stringify(out);
          }catch(e){ return '[]'; }
        })();
    """.trimIndent()

    /** 页面 JS 抛出的错误（由钩子里的 window.onerror / console 收集） */
    private fun errJs(): String = """
        (function(){
          try{ return JSON.stringify(window.__wbErr || []); }catch(e){ return '[]'; }
        })();
    """.trimIndent()

    private fun exportNow() {
        appendResult("正在收集接口记录 ...")
        eval(netJs()) { v ->
            val arr = runCatching { JSONArray(v) }.getOrNull()
            netRecords.clear()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    netRecords.add(formatNet(o))
                }
            }
            // 再补一张当前页快照
            eval(grabJs()) { g ->
                runCatching { JSONObject(g) }.getOrNull()?.let { o ->
                    snapshots.append("\n===== 页面快照 [导出时] hash=").append(o.optString("hash"))
                        .append(" =====\n--- 可见文本 ---\n").append(o.optString("text")).append('\n')
                    appendResult("当前页 Cookie: ${o.optString("ck").take(120)}")
                }
                buildAndSave()
            }
        }
    }

    private fun finishReport() {
        if (done) return
        eval(netJs()) { v ->
            val arr = runCatching { JSONArray(v) }.getOrNull()
            netRecords.clear()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    netRecords.add(formatNet(o))
                }
            }
            buildAndSave()
        }
    }

    private fun formatNet(o: JSONObject): String {
        val sb = StringBuilder()
        sb.append('[').append(o.optString("k")).append("] ")
            .append(o.optString("m", "GET")).append(' ').append(o.optString("u"))
            .append("  → HTTP ").append(o.optString("s", "?"))
        val req = o.optString("req")
        if (req.isNotBlank()) sb.append("\n     请求体: ").append(req.take(800))
        val resp = o.optString("resp")
        if (resp.isNotBlank()) sb.append("\n     响应体: ").append(resp.take(1500))
        return sb.toString()
    }

    // ------------------------------------------------- 业务 JS 源码采集（本轮新增）

    /** 这些名字的 JS 是第三方库，里面不可能有后端接口，跳过 */
    private val JS_LIB_MARKS = listOf(
        "/lib/", "require", "jquery.", "bootstrap", "underscore", "knockout",
        "html5shiv", "respond.min", "base64"
    )

    /** 同源 GET 一份文本（带 WebView 当前的 Cookie，保证和页面同一个会话） */
    private fun httpGetText(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "http://$ip/")
            conn.setRequestProperty("Accept", "*/*")
            CookieManager.getInstance().getCookie(url)?.let {
                conn.setRequestProperty("Cookie", it)
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.readBytes()
            if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** 逐个拉取业务 JS 源码，拼成报告里的一段。**必须在子线程调用** */
    private fun fetchAppJs(scriptUrls: List<String>): String {
        val sb = StringBuilder()
        var n = 0
        for (s in scriptUrls) {
            if (s.isBlank()) continue
            val low = s.lowercase()
            if (!low.contains(".js")) continue
            if (JS_LIB_MARKS.any { low.contains(it) }) continue
            if (n >= 12) break
            val u = if (s.startsWith("http")) s else "http://$ip/" + s.trimStart('/')
            val src = httpGetText(u)
            if (src.isNullOrBlank()) {
                runOnUiThread { appendResult("  JS 取不到: $u") }
                continue
            }
            n++
            sb.append("\n----- ").append(u).append("  (长度 ").append(src.length).append(") -----\n")
            sb.append(if (src.length > 25000) src.substring(0, 25000) + "\n...[已截断]" else src)
            sb.append('\n')
            runOnUiThread { appendResult("  已取 $u（${src.length} 字节）") }
        }
        return sb.toString()
    }

    private fun buildAndSave() {
        done = true
        touring = false
        eval(scriptsJs()) { sv ->
            val scripts = runCatching { JSONArray(sv) }.getOrNull()
            eval(errJs()) { ev ->
                val errs = runCatching { JSONArray(ev) }.getOrNull()
                val urls = ArrayList<String>()
                if (scripts != null) for (i in 0 until scripts.length()) urls.add(scripts.optString(i))
                val appUrls = urls.filter { u ->
                    u.contains(".js") && JS_LIB_MARKS.none { u.lowercase().contains(it) }
                }
                appendResult("正在拉取业务 JS 源码（${appUrls.size} 个）...")
                // 拉 JS 要联网，必须离开 UI 线程
                Thread {
                    val jsDump = fetchAppJs(urls)
                    runOnUiThread { writeReport(errs, urls, jsDump) }
                }.start()
            }
        }
    }

    private fun writeReport(errs: JSONArray?, scriptUrls: List<String>, jsDump: String) {
        val sb = StringBuilder()
        sb.append("===== 网页模式全量诊断 =====\n")
        sb.append("目标: http://").append(ip).append("/\n")
        sb.append("版本: v1.3\n")
        sb.append("已登录: ").append(loggedIn).append("   密码长度: ").append(pwd.length).append('\n')
        sb.append("拦截器: ").append(if (hookSeen) "已挂上 __wbHooked ✓" else "✗ 没挂上（接口记录为空是正常的）").append('\n')
        sb.append("主文档注入: ").append(lastHookInfo).append("（").append(intercepted).append(" 次）\n")
        sb.append("页面加载脚本: ").append(scriptUrls.size).append(" 个\n")
        val errCount = errs?.length() ?: 0
        sb.append("页面 JS 错误: ").append(errCount).append(" 条\n")

        sb.append("\n----- 页面加载的 script src（按加载顺序）-----\n")
        if (scriptUrls.isEmpty()) sb.append("(一个都没有)\n")
        else for (u in scriptUrls) sb.append("  ").append(u).append('\n')

        sb.append("\n----- 页面 JS 错误（").append(errCount).append(" 条）-----\n")
        if (errCount == 0) sb.append("(没有捕获到 JS 错误)\n")
        else for (i in 0 until errCount) sb.append("  ").append(i + 1).append(") ").append(errs?.optString(i)).append('\n')

        sb.append("\n----- 网络请求记录（").append(netRecords.size).append(" 条）-----\n")
        if (netRecords.isEmpty()) {
            sb.append("(没有记录到任何 XHR / fetch / jQuery 请求)\n")
        } else {
            netRecords.forEachIndexed { i, s -> sb.append("\n#").append(i + 1).append(' ').append(s).append('\n') }
        }

        if (jsDump.isNotBlank()) {
            sb.append("\n===== 业务 JS 源码（后端接口地址就写在里面）=====")
            sb.append(jsDump)
        }

        sb.append("\n----- 页面快照 -----\n").append(snapshots)

        val text = sb.toString()
        lastReport = text

        val ok = saveFile(text)
        val info = HtmlParser.parsePage(text, "webview")
        if (info.hasData()) lastInfo = info

        tvResult.text = buildString {
            append(if (info.hasData()) "✓ 网页模式解析成功：" else "✗ 网页模式没解析到字段（把导出内容发我）")
            append('\n').append(info.summary())
            append("\n接口记录 ").append(netRecords.size).append(" 条，快照 ")
            append(snapshots.length).append(" 字符，报告共 ").append(text.length).append(" 字符")
            append('\n').append(if (ok) "已保存到 " + file().absolutePath else "写文件失败，请点这里复制")
            append("\n（点这里复制全部诊断内容）")
        }
        toast(if (info.hasData()) "网页模式取到数据 ✓" else "已导出诊断内容")
    }

    private fun file(): File = File(Environment.getExternalStorageDirectory(), "routermon_webview.txt")

    private fun saveFile(text: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                if (!permAsked) {
                    permAsked = true
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1002)
                }
                return false
            }
            file().writeText(text, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            appendResult("保存失败: ${e.message}")
            false
        }
    }

    private fun copyReport() {
        val t = if (lastReport.isNotBlank()) lastReport else snapshots.toString()
        if (t.isBlank()) {
            toast("还没有可复制的诊断内容")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("routermon_webview", t))
        toast("已复制 ${t.length} 字符")
    }

    override fun onDestroy() {
        done = true
        try {
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
