package com.workbuddy.routermon

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USR-G805 4G 工业路由器监控主界面
 *
 * 工控机场景:接 LAN 有线 → 自动登录 webUI → 展示信号强度 / ICCID / IMEI / IMSI
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var client: RouterClient? = null
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnRefresh.setOnClickListener { doFetchOnce() }
        binding.btnLogout.setOnClickListener { doLogout() }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        client?.logout()
    }

    private fun doLogin() {
        val ip = binding.etIp.text?.toString()?.trim().orEmpty()
        val pass = binding.etPass.text?.toString().orEmpty()

        if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(pass)) {
            Toast.makeText(this, "请填写 IP 和密码", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLogin.isEnabled = false
        log("登录 $ip ...")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    client = RouterClient(ip, pass)
                    client?.login() ?: false
                } catch (e: Exception) {
                    log("登录异常: ${e.message}")
                    false
                }
            }
            binding.btnLogin.isEnabled = true
            if (ok) {
                log("登录成功 ✓")
                binding.btnRefresh.isEnabled = true
                binding.btnLogout.isEnabled = true
                doFetchOnce()
                startPolling()
            } else {
                log("登录失败,请检查 IP 和密码")
                Toast.makeText(this@MainActivity, "登录失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doLogout() {
        pollJob?.cancel()
        pollJob = null
        lifecycleScope.launch(Dispatchers.IO) {
            client?.logout()
            client = null
            withContext(Dispatchers.Main) {
                binding.btnRefresh.isEnabled = false
                binding.btnLogout.isEnabled = false
                clearUi()
                log("已退出")
            }
        }
    }

    private fun doFetchOnce() {
        val c = client ?: return
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try { c.fetchSignalInfo() } catch (e: Exception) {
                    log("获取失败: ${e.message}")
                    null
                }
            }
            info?.let { renderInfo(it) }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                val c = client ?: break
                val info = withContext(Dispatchers.IO) {
                    try { c.fetchSignalInfo() } catch (_: Exception) { null }
                }
                info?.let { renderInfo(it) }
            }
        }
    }

    private fun renderInfo(info: SignalInfo) {
        binding.tvRssi.text = info.rssi
        binding.tvNetwork.text = info.networkMode
        binding.tvOperator.text = info.operator
        binding.tvRuntime.text = info.runTime
        binding.tvIccid.text = info.iccid
        binding.tvImei.text = info.imei
        binding.tvImsi.text = info.imsi
        log("刷新 ${now()}  信号=${info.rssi}  ICCID=${info.iccid}")
    }

    private fun clearUi() {
        listOf(
            binding.tvRssi, binding.tvNetwork, binding.tvOperator, binding.tvRuntime,
            binding.tvIccid, binding.tvImei, binding.tvImsi
        ).forEach { it.text = "--" }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        runOnUiThread {
            val prev = binding.tvLog.text?.toString().orEmpty()
            val lines = (prev.lines() + "$ts  $msg").takeLast(15)
            binding.tvLog.text = lines.joinToString("\n")
        }
    }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
}