package com.workbuddy.routermon

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.workbuddy.routermon.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 4G 路由器监控（USR-G805 等）
 *
 * 工控机接 LAN 有线 → 自动登录 webUI → 展示信号 / ICCID 等
 * 登录失败时可用「诊断自检」把全过程日志导出发给开发者。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var client: RouterClient? = null
    private var pollJob: Job? = null
    private var lastDiag = ""
    private var busy = false
    private var lastWebInfo: SignalInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDiag.setOnClickListener { doDiag() }
        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnRefresh.setOnClickListener { doFetchOnce() }
        binding.btnLogout.setOnClickListener { doLogout() }
        binding.btnWeb.setOnClickListener { openWeb() }
        binding.btnCopy.setOnClickListener { copyDiag() }
        binding.btnSave.setOnClickListener { saveDiag() }
        binding.btnClear.setOnClickListener { clearLog() }

        log("就绪。目标 http://${ip()}\n" +
                "先点【① 诊断自检】看链路；\n" +
                "若提示是 SPA 固件，请点【网页模式(推荐)】。")
    }

    override fun onResume() {
        super.onResume()
        // 网页模式抓到的结果带回主界面
        val info = WebViewActivity.lastInfo
        if (info != null && info.hasData() && info !== lastWebInfo) {
            lastWebInfo = info
            renderInfo(info)
            log("已接收网页模式的结果 ${info.summary()}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
    }

    // ------------------------------------------------------------ 动作

    /** 完整自检：网卡 / TCP / 首页 / 登录表单发现 / 各候选登录 / 抓取 */
    private fun doDiag() {
        val ipAddr = ip()
        if (TextUtils.isEmpty(ipAddr)) { toast("请填写路由器 IP"); return }
        if (busy) return
        busy = true
        setButtons(false)
        binding.tvLog.text = ""
        log("=== 诊断开始 ${nowText()} 目标=$ipAddr ===")

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                sb.append("===== 4G 路由器诊断报告 =====\n")
                sb.append("时间: ").append(nowText()).append('\n')
                sb.append("目标: http://").append(ipAddr).append("/   密码长度: ")
                    .append(pass().length).append('\n')
                val c = RouterClient(ipAddr, pass())
                try {
                    c.netSelfCheck()
                    c.tcpTest()
                    c.probeHome()
                    val r = c.login()
                    if (r.ok) {
                        log("登录成功 ✓ " + r.message)
                    } else {
                        log("登录失败 ✗ " + r.message)
                    }
                    // 不管登录成不成功都抓一遍：成功就能取到数据；
                    // 失败也能把页面清单 / JS 里的接口挖出来，便于定位真正的登录接口
                    val info = c.fetchSignalInfo()
                    if (info.hasData()) {
                        withContext(Dispatchers.Main) { renderInfo(info) }
                    } else {
                        log("未取到数据。若上面提示 SPA 固件，请点【网页模式(推荐)】")
                    }
                    sb.append(c.diagText())
                    sb.append("\n===== 抓到的原始响应 =====\n")
                    sb.append(c.pagesDump())
                } catch (e: Exception) {
                    sb.append("\n诊断异常: ").append(e.javaClass.simpleName).append(' ').append(e.message)
                }
                client = c
                sb.toString()
            }
            lastDiag = text
            showTail(text)
            busy = false
            setButtons(true)
            if (saveDiagToFile(text)) {
                toast("诊断完成，已保存到 " + diagFile().absolutePath)
            } else {
                toast("诊断完成（写文件失败，可点「复制诊断」）")
            }
        }
    }

    /** 正常使用路径：登录 + 一次抓取 */
    private fun doLogin() {
        val ipAddr = ip()
        if (TextUtils.isEmpty(ipAddr)) { toast("请填写路由器 IP"); return }
        if (TextUtils.isEmpty(pass())) { toast("请填写登录密码"); return }
        if (busy) return
        busy = true
        setButtons(false)
        log("登录 $ipAddr ...")

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val c = RouterClient(ipAddr, pass())
                try {
                    val r = c.login()
                    if (r.ok) {
                        val info = c.fetchSignalInfo()
                        withContext(Dispatchers.Main) { renderInfo(info) }
                    }
                    client = c
                    lastDiag = c.diagText() + "\n===== 原始响应 =====\n" + c.pagesDump()
                    Pair(r.ok, r.message)
                } catch (e: Exception) {
                    lastDiag = c.diagText()
                    Pair(false, "异常: ${e.javaClass.simpleName} ${e.message}")
                }
            }
            showTail(lastDiag)
            busy = false
            setButtons(true)
            if (res.first) {
                log("登录成功 ✓ ${res.second}")
                startPolling()
            } else {
                log("登录失败 ✗ ${res.second}")
                toast("登录未通过，请点【网页模式(推荐)】或【① 诊断自检】")
            }
        }
    }

    private fun doFetchOnce() {
        val c = client ?: return
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    c.fetchSignalInfo()
                } catch (e: Exception) {
                    log("获取失败: ${e.message}")
                    null
                }
            }
            info?.let { renderInfo(it) }
        }
    }

    private fun doLogout() {
        pollJob?.cancel()
        pollJob = null
        client = null
        clearUi()
        log("已退出（已清除本地会话）")
    }

    private fun openWeb() {
        val i = Intent(this, WebViewActivity::class.java)
        i.putExtra("ip", ip())
        i.putExtra("pwd", pass())
        startActivity(i)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                val c = client ?: break
                val info = withContext(Dispatchers.IO) {
                    try {
                        c.fetchSignalInfo()
                    } catch (_: Exception) {
                        null
                    }
                }
                info?.let { renderInfo(it) }
            }
        }
    }

    // ------------------------------------------------------------ UI

    private fun renderInfo(info: SignalInfo) {
        setText(binding.tvRssi, rssiText(info.rssi))
        setText(binding.tvRsrp, info.rsrp)
        setText(binding.tvNetwork, info.networkMode)
        setText(binding.tvOperator, info.operator)
        setText(binding.tvRuntime, info.runTime)
        setText(binding.tvIccid, info.iccid)
        setText(binding.tvImei, info.imei)
        setText(binding.tvImsi, info.imsi)
        if (info.hasData()) {
            log("刷新 ${nowTime()}  ${info.summary()}")
        } else {
            log("刷新 ${nowTime()}  未解析到字段（页面结构未识别）")
        }
    }

    /**
     * 有些固件（如 USR-G805）把信号写成 0~31 的原始等级而不是 dBm，
     * 如「RSSI: 31」。这里补一个换算提示，避免误读成「信号很差」。
     */
    private fun rssiText(v: String): String {
        if (v.isBlank() || v == "--") return "--"
        val s = v.trim()
        val n = s.toIntOrNull()
        if (n != null && n in 0..31) return "$s（0~31 等级，约 ${-113 + 2 * n} dBm）"
        return s
    }

    private fun setText(tv: android.widget.TextView, v: String) {
        runOnUiThread { tv.text = if (v.isBlank()) "--" else v }
    }

    private fun clearUi() {
        listOf(
            binding.tvRssi, binding.tvRsrp, binding.tvNetwork, binding.tvOperator,
            binding.tvRuntime, binding.tvIccid, binding.tvImei, binding.tvImsi
        ).forEach { it.text = "--" }
    }

    private fun setButtons(enabled: Boolean) {
        runOnUiThread {
            binding.btnLogin.isEnabled = enabled
            binding.btnDiag.isEnabled = enabled
            binding.btnWeb.isEnabled = enabled
        }
    }

    private fun clearLog() {
        binding.tvLog.text = ""
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        runOnUiThread {
            val prev = binding.tvLog.text?.toString().orEmpty()
            val lines = (prev.lines() + "$ts  $msg").takeLast(30)
            binding.tvLog.text = lines.joinToString("\n")
            binding.svLog.post { binding.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun showTail(text: String) {
        runOnUiThread {
            val lines = text.lines().takeLast(240)
            binding.tvLog.text = lines.joinToString("\n")
            binding.svLog.post { binding.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun copyDiag() {
        val text = if (lastDiag.isNotBlank()) lastDiag else binding.tvLog.text.toString()
        if (text.isBlank()) { toast("暂无日志"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("routermon_diag", text))
        toast("已复制 ${text.length} 字符到剪贴板")
    }

    private fun diagFile(): File = File(
        android.os.Environment.getExternalStorageDirectory(), "routermon_diag.txt"
    )

    private fun saveDiagToFile(text: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
                return false
            }
            diagFile().writeText(text, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            log("保存日志失败: ${e.message}")
            false
        }
    }

    private fun saveDiag() {
        val text = if (lastDiag.isNotBlank()) lastDiag else binding.tvLog.text.toString()
        if (text.isBlank()) { toast("暂无日志"); return }
        if (saveDiagToFile(text)) {
            toast("已保存到 " + diagFile().absolutePath)
        } else {
            toast("保存失败，请用「复制诊断」")
        }
    }

    private fun ip(): String = binding.etIp.text?.toString()?.trim().orEmpty()

    private fun pass(): String = binding.etPass.text?.toString().orEmpty()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun nowTime() = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())

    private fun nowText() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
