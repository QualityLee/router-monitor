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
import java.io.File

/**
 * 网页模式 —— 这次对付 USR-G805 新 webui 的**主力方案**。
 *
 * 实测结论：G805 的后台是 Knockout.js 单页应用，静态 HTML 里一个值都没有，
 * 信号/ICCID 全靠页面 JS 通过 AJAX 从 /cgi-bin/*.cgi 拉。纯 HTTP 客户端永远抓不到。
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
            override fun onPageFinished(view: WebView?, url: String?) {
                if (done) return
                // 每次加载都先补挂拦截器（幂等）
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

    /** 注入 XHR / fetch / jQuery.ajax 拦截器。全部用 ES5 —— 工控机 WebView 是 Chrome 39 */
    private fun hookJs(): String = """
        (function(){
          try{
            if(window.__wbHooked){ return 'already'; }
            window.__wbHooked = 1;
            window.__wbNet = [];
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
            var lo = document.getElementById('logoutlink');
            var menu = document.getElementById('main_left_con');
            return JSON.stringify({
              pw: anyPw,
              logout: vis(lo),
              menu: vis(menu),
              hash: location.hash || '',
              net: (window.__wbNet ? window.__wbNet.length : -1)
            });
          }catch(e){ return JSON.stringify({err:''+e}); }
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

    private fun checkAndLogin() {
        eval(stateJs()) { st ->
            val o = runCatching { JSONObject(st) }.getOrNull()
            val anyPw = o?.optBoolean("pw", false) ?: false
            val logout = o?.optBoolean("logout", false) ?: false
            val menu = o?.optBoolean("menu", false) ?: false
            val net = o?.optInt("net", -1) ?: -1

            // 判据：出现「退出」入口 / 左侧菜单 就一定登录好了。
            // 注意不能只看「没有密码框」—— 登录框可能只是被隐藏了，
            // 所以再等两次尝试确认。
            val okNow = logout || menu || (loginTries >= 2 && !anyPw)
            if (okNow) {
                loggedIn = true
                appendResult("✓ 已登录（logout=$logout menu=$menu 已记录请求 $net 条）")
                startTour()
                return@eval
            }
            if (loginTries >= 6) {
                appendResult("✗ 连续 $loginTries 次自动登录未成功，仍继续抓取（接口记录里能看到原因）")
                startTour()
                return@eval
            }
            loginTries++
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

    private fun buildAndSave() {
        done = true
        touring = false

        val sb = StringBuilder()
        sb.append("===== 网页模式全量诊断 =====\n")
        sb.append("目标: http://").append(ip).append("/\n")
        sb.append("已登录: ").append(loggedIn).append("   密码长度: ").append(pwd.length).append('\n')
        sb.append("\n----- 网络请求记录（").append(netRecords.size).append(" 条）-----\n")
        if (netRecords.isEmpty()) {
            sb.append("(没有记录到任何 XHR / fetch / jQuery 请求)\n")
        } else {
            netRecords.forEachIndexed { i, s -> sb.append("\n#").append(i + 1).append(' ').append(s).append('\n') }
        }
        sb.append("\n----- 页面快照 -----\n").append(snapshots)

        val text = sb.toString()
        lastReport = text

        val ok = saveFile(text)
        val info = HtmlParser.parsePage(text, "webview")
        if (info.hasData()) lastInfo = info

        runOnUiThread {
            tvResult.text = buildString {
                append(if (info.hasData()) "✓ 网页模式解析成功：" else "✗ 网页模式没解析到字段（把导出内容发我）")
                append('\n').append(info.summary())
                append("\n接口记录 ").append(netRecords.size).append(" 条，快照 ").append(snapshots.length).append(" 字符")
                append('\n').append(if (ok) "已保存到 " + file().absolutePath else "写文件失败，请点这里复制")
                append("\n（点这里复制全部诊断内容）")
            }
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
