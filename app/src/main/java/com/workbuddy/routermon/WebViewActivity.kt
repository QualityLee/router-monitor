package com.workbuddy.routermon

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * 兜底方案：用真正的浏览器内核打开路由器后台。
 *
 * 有些固件的登录密码是页面 JS 加密后再提交的，纯 HTTP 请求永远登不上；
 * 这里让 WebView 自己跑页面 JS 完成登录，再把 DOM 抓回来用同一套解析器取值。
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var tvResult: TextView
    private var ip = "192.168.1.1"
    private var pwd = "admin"
    private var grabbing = false
    private var lastHtml = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        ip = intent.getStringExtra("ip")?.trim().orEmpty().ifEmpty { "192.168.1.1" }
        pwd = intent.getStringExtra("pwd").orEmpty()
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

        web.addJavascriptInterface(Bridge(), "WBBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (grabbing) {
                    view?.evaluateJavascript(collectJs(), null)
                    return
                }
                if (url != null && url.startsWith("http://")) autoLogin(view)
            }
        }

        findViewById<Button>(R.id.btn_relogin).setOnClickListener { web.loadUrl("http://$ip/") }
        findViewById<Button>(R.id.btn_grab).setOnClickListener { grab() }
        tvResult.setOnClickListener {
            val t = if (lastHtml.isNotBlank()) lastHtml else tvResult.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("routermon_html", t))
            Toast.makeText(this, "已复制 ${t.length} 字符", Toast.LENGTH_SHORT).show()
        }

        tvResult.text = "正在打开 http://$ip/ ...\n登录后点【抓取本页数据】"
        web.loadUrl("http://$ip/")
    }

    private fun autoLogin(view: WebView?) {
        val pwdJson = JSONObject.quote(pwd)
        val js = """
        (function(){
          try{
            var pw=document.querySelectorAll('input[type=password]');
            if(!pw.length) return 'no-login-form';
            for(var i=0;i<pw.length;i++){
              pw[i].value=$pwdJson;
              try{pw[i].dispatchEvent(new Event('input',{bubbles:true}));}catch(e){}
              try{pw[i].dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}
            }
            var f=pw[0].form;
            if(!f) return 'no-form';
            var els=f.elements;
            for(var j=0;j<els.length;j++){
              var e=els[j], n=(e.name||'').toLowerCase(), t=(e.type||'').toLowerCase();
              if(t==='text'||t===''){
                if(n.indexOf('user')>=0||n.indexOf('account')>=0||n.indexOf('name')>=0){ e.value='admin'; }
              }
            }
            var btn=f.querySelector('input[type=submit],button[type=submit],button');
            if(btn){ btn.click(); } else { f.submit(); }
            return 'submitted';
          }catch(e){ return 'err:'+e; }
        })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun grab() {
        grabbing = true
        tvResult.text = "正在读取页面 DOM ..."
        web.evaluateJavascript(collectJs(), null)
    }

    private fun collectJs(): String = """
        (function(){
          var out=[];
          function push(d,tag){
            try{
              if(!d) return;
              var u = d.location ? d.location.href : tag;
              out.push('<!-- ==== '+u+' ==== -->');
              out.push(d.documentElement ? d.documentElement.outerHTML : d.body.innerHTML);
            }catch(e){ out.push('<!-- '+tag+' err:'+e+' -->'); }
          }
          push(document,'top');
          try{
            var fs=document.querySelectorAll('frame,iframe');
            for(var i=0;i<fs.length;i++){
              try{ push(fs[i].contentDocument,'frame'+i); }catch(e){}
              try{
                var inner=fs[i].contentDocument.querySelectorAll('frame,iframe');
                for(var k=0;k<inner.length;k++){ try{ push(inner[k].contentDocument,'frame'+i+'-'+k); }catch(e){} }
              }catch(e){}
            }
          }catch(e){}
          try{ window.WBBridge.onPage(out.join('\n')); }catch(e){}
          return out.length;
        })();
    """.trimIndent()

    inner class Bridge {
        @JavascriptInterface
        fun onPage(html: String) {
            lastHtml = html
            Thread {
                val info = HtmlParser.parsePage(html, "webview")
                runOnUiThread {
                    grabbing = false
                    tvResult.text = if (info.hasData()) {
                        "解析结果：\n" + info.summary() + "\n\n（点这里可复制原始 HTML）"
                    } else {
                        "没解析到字段（共 ${html.length} 字符）。\n点这里复制原始 HTML 发给开发者。"
                    }
                    Toast.makeText(
                        this@WebViewActivity,
                        if (info.hasData()) "解析到数据 ✓" else "未解析到数据",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }
    }

    override fun onDestroy() {
        try {
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
