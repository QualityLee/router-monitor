# USR-G805 4G 路由器取数模块 · 集成指南

> 给安卓工程师的交接文档。目标：在**已有 App** 中增加一个能力 —— 登录 4G 路由器后台，
> 读出 **4G 信号值** 和 **SIM 卡 ICCID**。
>
> 本文所有协议细节均在真机（Android 5.1 工控机 + USR-G805）上实测验证过。

---

## 0. TL;DR

| 项 | 内容 |
|---|---|
| 设备 | 有人物联网 **USR-G805** 4G 路由器 |
| 网络 | 工控机走**有线**接路由器 LAN；路由器 LAN IP `192.168.1.1`，账号/密码 `admin` / `admin` |
| 核心结论 | **不需要 WebView、不需要爬页面**。G805 有一个只读 HTTP 接口，参数对了**一个 GET 拿全所有字段** |
| 最小集成量 | 拷 **3 个 Kotlin 文件** + 加 **2 个依赖** + **约 20 行调用代码** |
| 官方仓库 | https://github.com/QualityLee/router-monitor （公开，可直接 clone） |

---

## 1. 原理：为什么不能爬页面

G805 的 webUI 是 **Knockout + RequireJS 的 JS 单页应用**。它的首页 HTML 只是空壳，
所有数据都是页面加载后用 XHR 拉回来、再靠 `data-bind` 渲染的。

**所以「GET 页面 → 正则提取」这条路走不通** —— 静态 HTML 里一个数据都没有
（实测：24 个页面快照全部为空文本，0 个数据字段）。

我们把它的业务 JS（`js/service.js`，279 KB）逆出来了，真实协议是：

```http
# 读（本模块只调用这一类）
GET  http://192.168.1.1/reqproc/proc_get?multi_data=1&isTest=false&cmd=<逗号分隔的字段名>

# 写（本模块绝不调用，列出仅供理解）
POST http://192.168.1.1/reqproc/proc_post     goformId=SET_WIFI_INFO / SET_BEARER_PREFERENCE ...
```

### ⚠️ 两个必须知道的坑（搞错了就是「拿不到数据」）

**坑 1：字段名是固件内部名，不是通用名。**
ICCID 那栏在 G805 上的字段真名叫 **`ziccid`**，**不是** `iccid`／`sim_iccid`——我们先后试了
18 个「合理猜测」的字段名，返回的全是空字符串。完整的字段名见 [§4.2](#42-字段名映射表)。

**坑 2：`multi_data=1` 必须带。**
不带这个参数时，G805 会把整个 `cmd` 串**当成一个 key**，返回 `{"字段1,字段2,...":""}`（空值）。
带上才会真的**分字段返回**。`isTest=false` 一并带上，与真机浏览器行为一致。

### 实测响应（这台设备的真实返回，已确认）

```json
{"wifi_coverage":"long_mode","m_ssid_enable":"0","sn":"01500326060200003460\n",
 "imei":"862790076742140","network_type":"LTE","sub_network_type":"FDD_LTE",
 "rssi":"-77","rscp":"","lte_rsrp":"-77","imsi":"","sim_imsi":"460113938676490",
 "ziccid":"89861126208093092119","signalbar":"5",
 "network_provider":"China Telecom","wan_ipaddr":"10.82.128.242",
 "uptime":" 0h 11m 22s","ppp_status":"ppp_connected", "...":"..."}
```

---

## 2. 交付内容与文件角色

### 2.1 文件清单

路径：`app/src/main/java/com/workbuddy/routermon/`

| 文件 | 行数 | 作用 | 第三方依赖 | 要不要拷 |
|---|---|---|---|---|
| `SignalInfo.kt` | 46 | 数据模型（ICCID / RSSI / RSRP / IMEI / IMSI / 制式 / 运营商 / 运行时长） | 无 | **必拷** |
| `HtmlParser.kt` | 372 | 解析：G805 flat-JSON 键值映射 + 通用「标签→值」提取 + 合并策略 | 无 | **必拷** |
| `RouterClient.kt` | 1273 | 全部网络逻辑：连通性自检 / 自动登录 / 抓取 / 接口挖掘 / 诊断报告 | okhttp3、jsoup | **必拷** |
| `Es5Fix.kt` | 204 | ES6→ES5 语法降级器（救活 Chrome 39 内核） | 无 | 仅路径 B 需要 |
| `WebViewActivity.kt` | 1210 | 路径 B：真浏览器内核跑完页面 JS 再抓 | `org.json`（系统自带） | 仅路径 B 需要 |
| `MainActivity.kt` | 371 | 演示界面（按钮 / 日志 / 报告导出） | kotlinx.coroutines、AndroidX | **参考用，别直接拷** |

### 2.2 两条集成路径

**路径 A —— 原生直连（推荐，最小）**
```
SignalInfo.kt + HtmlParser.kt + RouterClient.kt
依赖：okhttp3 + jsoup
```
无 UI、无 WebView、无协程依赖。全程 `okhttp` 同步调用，扔到 IO 线程即可。
**当前 v1.5 已确认这条路能直接拿到全部字段**（包括 ICCID），所以优先选它。

**路径 B —— WebView 兜底（备选）**
```
再 + Es5Fix.kt + WebViewActivity.kt
```
让系统浏览器内核真的把页面 JS 跑一遍，从渲染后的 DOM 里读值。
**什么时候需要它**：以后换了路由器型号/固件、HTTP 直连取不到时，作为通用兜底。
代价是要处理 ES5 降级（见 [§7 坑 1](#7-踩过的坑)）和 WebView 生命周期。

> 建议：**先按路径 A 集成**，跑通即可收工。路径 B 的代码可以原样留在仓库里备查，不必集成。

---

## 3. 集成步骤

### 3.1 拷贝源码

```bash
# 只拷路径 A 需要的三个文件
cp SignalInfo.kt HtmlParser.kt RouterClient.kt  <你的工程>/.../yourpackage/router/
```

> **包名**：三个文件首行都是 `package com.workbuddy.routermon`。
> 拷进去后**改成你自己的包名**，或者用 IDE 的 Move/Refactor 功能自动改。
> 三个文件之间没有跨包引用，改包名不会有连带影响。

> **语言**：源码是 Kotlin。如果你的工程是纯 Java，模块需要启用 Kotlin 插件
> （`id("org.jetbrains.kotlin.android")`），Kotlin 与 Java 可以互相直接调用。
> 如果策略上不允许引入 Kotlin，请参考 [§6 极简版](#6-极简版登录之后的最短路径)
> —— 那段逻辑用 Java 重写大约 80 行。

### 3.2 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // RouterClient 用
    implementation("org.jsoup:jsoup:1.17.1")               // RouterClient 用（链接打分）
}
```

**如果目标是 Android 5.x（API 21/22）**，还要开 core library desugaring，
否则 jsoup 在运行时可能缺类：

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
```

> **依赖冲突提醒**：这三个文件只用到 okhttp 的 `OkHttpClient / Request / Cookie / CookieJar`
> 和 jsoup 的 `Jsoup.parse / select`，全部是稳定 API。
> 如果你的工程已用 Retrofit + OkHttp，**只要 OkHttp ≥ 4.x 即可直接共存**，无需降级。

### 3.3 Manifest 与网络安全配置

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**关键：路由器是明文 HTTP，必须放行 cleartext。**

```xml
<!-- main/AndroidManifest.xml -->
<application
    android:networkSecurityConfig="@xml/network_security_config"
    android:usesCleartextTraffic="true" ...>
```

```xml
<!-- main/res/xml/network_security_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors><certificates src="system" /></trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">192.168.0.0</domain>
        <domain includeSubdomains="true">192.168.1.0</domain>
        <domain includeSubdomains="true">10.0.0.0</domain>
    </domain-config>
</network-security-config>
```

> 这段是**必须的**。Android 9+ 默认禁明文 HTTP，不加会直接抛
> `Cleartext HTTP traffic to 192.168.1.1 not permitted`。
> 就算你的 App 目前 targetSdk < 28，也建议现在就加上，避免以后升 target 踩雷。

### 3.4 调用示例（核心 20 行）

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 建议放在 ViewModel / Repository 里
suspend fun readRouter(): SignalInfo? = withContext(Dispatchers.IO) {
    // ① 一个 RouterClient 实例 = 一个会话（内部持有 CookieJar）
    val client = RouterClient(host = "192.168.1.1", password = "admin")

    // ② 登录（内部会自动跟 JS 跳转壳、找表单、试候选接口）
    val login = client.login()
    if (!login.ok) {
        // login.message 里有失败原因，直接给日志/上报用
        Log.w(TAG, "路由器登录失败: ${login.message}")
        return@withContext null
    }

    // ③ 抓数据（内部：爬页面 → 直连 proc_get → 兜底探测，逐级降级）
    val info = client.fetchSignalInfo()
    if (!info.hasData()) {
        Log.w(TAG, "未取到数据: ${client.diagText()}")
        return@withContext null
    }
    info
}

// 界面侧
viewModelScope.launch {
    readRouter()?.let { info ->
        tvIccid.text = info.iccid          // 89861126208093092119
        tvRssi.text  = "${info.rssi} dBm"  // -77 dBm
        tvRsrp.text  = info.rsrp           // -77
        tvMode.text  = info.networkMode    // LTE(FDD_LTE)
        tvOp.text    = info.operator       // China Telecom
    }
}
```

**三个必须注意的点：**

1. **不要在主线程调用。** `login()` 和 `fetchSignalInfo()` 是**同步阻塞**的
   （每次请求连接超时 6 秒 / 读写各 12 秒，最多抓 26 个页面），整体耗时可能 3~15 秒。
   必须在 IO 线程。
2. **复用同一个实例做轮询，不要每次 new。** 实例内部持有 cookie 和已抓页面缓存；
   每次 new 都要重新登录一遍，既慢又给路由器增加无谓压力。
   ```kotlin
   // 轮询正确姿势
   private var client: RouterClient? = null
   suspend fun poll() = withContext(Dispatchers.IO) {
       val c = client ?: RouterClient("192.168.1.1", "admin").also {
           it.login(); client = it
       }
       val info = c.fetchSignalInfo()   // 复用会话，不再重新登录
       ...
   }
   ```
3. **轮询间隔建议 ≥ 10 秒。** 路由器 web 服务端是个小嵌入式 HTTP 服务，
   并发/高频请求会被拒。10~30 秒对「看信号值」这个场景足够。

### 3.5 凭证不要硬编码

`192.168.1.1` / `admin` / `admin` 只是**这台设备**的当前值，请放到
`BuildConfig`、`SharedPreferences` 或设置页里，别写死在代码中：

```kotlin
// build.gradle.kts
buildConfigField("String", "ROUTER_HOST", "\"192.168.1.1\"")
```

---

## 4. 协议速查

### 4.1 接口一览

| 用途 | 方法 | 路径 | 参数 |
|---|---|---|---|
| **取数据**（只用这个） | GET | `/reqproc/proc_get` | `multi_data=1&isTest=false&cmd=<字段列表>` |
| 写配置 | POST | `/reqproc/proc_post` | `goformId=<动作>` + 字段 |

`goformId` 里存在的写动作（**仅列出，本模块绝不调用**）：
`SET_WIFI_SSID1_SETTINGS`、`SET_WIFI_SSID2_SETTINGS`、`SET_WIFI_INFO`、
`SET_WIFI_SECURITY_INFO`、`SET_WEB_LANGUAGE`、`SET_BEARER_PREFERENCE`、`SCAN_NETWORK`。

> **安全边界**：本模块对外发出的请求**只有 GET**。所有可能改路由器配置的接口
> （`proc_post`、`cgi-bin` 下的 `set`/`reboot`/`reset` 等）在代码里被显式列成黑名单
> （`WRITE_MARKS`），永不请求。集成时请保持这一点。

### 4.2 字段名映射表

请求用的 `cmd`（**实测有效，直接用这一串**）：

```
wifi_coverage,m_ssid_enable,sn,imei,network_type,sub_network_type,
rssi,rscp,lte_rsrp,imsi,sim_imsi,ziccid,signalbar,network_provider,
simcard_roam,wan_ipaddr,uptime,lan_ipaddr,mac_address,ppp_status,sta_count
```

响应键 → 业务字段（`HtmlParser.extractG805Json` 里就是这个映射）：

| 响应键 | 落到 | 备注 |
|---|---|---|
| `ziccid` | `iccid` | **★ ICCID 的真名**，不是 `iccid` |
| `imei` | `imei` | |
| `sim_imsi` | `imsi` | **优先**；此设备上 `imsi` 恒为空串 |
| `imsi` | `imsi` | 仅当 `sim_imsi` 缺失时兜底 |
| `rssi` | `rssi` | dBm，如 `-77` |
| `lte_rsrp` | `rsrp` | **优先** |
| `rscp` | `rsrp` | 仅当 `lte_rsrp` 缺失时兜底（3G 场景） |
| `network_type` + `sub_network_type` | `networkMode` | 拼成 `LTE(FDD_LTE)` |
| `network_provider` | `operator` | |
| `uptime` | `runTime` | 形如 `" 0h 11m 22s"`（注意前导空格） |

**如果以后换了路由器型号**：改 `RouterClient.probeG805ProcGet()` 里第一条 `cmd` 常量
（约在 `RouterClient.kt` 第 1097 行）即可，其余逻辑不用动。

---

## 5. 需要改的常量一览

| 想改什么 | 位置 | 现值 |
|---|---|---|
| 路由器 IP / 密码 | 构造参数 `RouterClient(host, password)` | 调用方传入，**无需改代码** |
| 取数字段列表 | `RouterClient.kt` → `probeG805ProcGet()` 第一条 `cmds.add(...)` | 见 §4.2 |
| HTTP 超时 | `RouterClient.kt` → `client` 的 okhttp 配置（L209） | 连接 6 秒 / 读写各 12 秒 |
| 最多抓多少个页面 | `RouterClient.kt` → `MAX_PAGES` | 26 |
| 最多抓多少 JS | `RouterClient.kt` → `MAX_JS_FETCH` | 16 |
| 写接口黑名单 | `RouterClient.kt` → `WRITE_MARKS` | 见 §4.1 |

---

## 6. 极简版：登录之后的最短路径

如果你的工程师只想**看懂核心**、或者想用 Java 重写，这里是去掉所有兜底逻辑后的本质。
**前提：client 必须已完成登录（cookie 已在 CookieJar 中）。**

```kotlin
private const val PROC_GET = "/reqproc/proc_get"
private const val FIELDS =
    "wifi_coverage,m_ssid_enable,sn,imei,network_type,sub_network_type," +
    "rssi,rscp,lte_rsrp,imsi,sim_imsi,ziccid,signalbar,network_provider," +
    "simcard_roam,wan_ipaddr,uptime,lan_ipaddr,mac_address,ppp_status,sta_count"

fun readG805(client: OkHttpClient): String? {
    // ★ multi_data=1 不能省：不带就返回空值（见 §1 坑 2）
    val url = "http://192.168.1.1$PROC_GET?multi_data=1&isTest=false&cmd=" +
              URLEncoder.encode(FIELDS, "UTF-8")
    return client.newCall(Request.Builder().url(url).build())
        .execute().use { it.body?.string() }
}

// 解析：直接拿 org.json 也行，字段名照 §4.2 映射
fun parse(json: String) = JSONObject(json).let {
    mapOf(
        "iccid"  to it.optString("ziccid"),          // ★ ziccid
        "imei"   to it.optString("imei"),
        "imsi"   to it.optString("sim_imsi").ifEmpty { it.optString("imsi") },
        "rssi"   to it.optString("rssi"),
        "rsrp"   to it.optString("lte_rsrp").ifEmpty { it.optString("rscp") },
        "mode"   to (it.optString("network_type") + "(" + it.optString("sub_network_type") + ")"),
        "op"     to it.optString("network_provider"),
        "uptime" to it.optString("uptime").trim(),
    )
}
```

**唯一没有给出极简版的环节是「登录」** —— 因为 G805 的登录流程不是固定一两个接口：
首页是个 JS 跳转壳，表单藏在跳转后的页面里，我们采用的是「跟跳转 → 找表单 → 试候选接口」
的自适应策略（`RouterClient.login()` 及其依赖链，`RouterClient.kt` 第 398~793 行，约 350 行）。

这部分**请不要自己重写**，直接用 `RouterClient.login()`，它已经在真机上验证通过。
如果你确实需要纯 Java 版，把 `login()` / `loadHome()` / `redirectTarget()` /
`findLoginForm()` / `buildBodies()` / `tryPost()` / `accepted()` / `looksLoggedIn()`
这一串方法按原逻辑翻译即可，逻辑本身与语言无关。

---

## 7. 踩过的坑

集成前请务必扫一眼，这些都是**真机上烧过时间**的：

### 坑 1：Chrome 39 不认 ES6 —— 这是路径 B 的核心坑

工控机 Android 5.1 的 WebView 内核是 **Chrome 39（ES5）**。G805 的 `js/service.js`
第 315 行用了 ES6 对象字面量简写属性：

```js
{ wifi_cur_state, ... }   // ES6 语法
```

Chrome 39 解析到这一行**整段报错、丢弃整个文件**，然后级联崩：

```
service.js:315 语法错误 → require(['service']) 拿到 undefined → 主流程不执行
→ 菜单不加载 → 紧接着 menu.js:10 报 getOpMode of undefined
→ Knockout 从未 applyBindings → 页面所有快照全空 → 0 个网络请求
```

**这就是「网页模式一开始什么都抓不到」的根因** —— 全项目只有这 1 处 ES6，
后来靠 `Es5Fix.kt` 在 JS 到达内核前把它降级成 ES5 才救活。

> 如果走路径 B，`Es5Fix.kt` 必须一起集成。
> 另外注意 `WebViewClient.shouldInterceptRequest` 在 **API 21~22 上 `isForMainFrame()`
> 有个已知 bug：永远返回 `true`**，没法用它区分主文档和子资源。
> 我们的做法是先按「同源 + `.js` 后缀」认领子资源，其余走主文档分支。

### 坑 2：`multi_data=1` 不能省
见 §1 坑 2。**最容易翻车的一条** —— 少了它，接口返回 200 但值全空，
会被误判成「字段名不对」而白排查半天。

### 坑 3：ICCID 字段叫 `ziccid`
见 §1 坑 1。`iccid` / `sim_iccid` / `iccid_state` 等常见名在 G805 上全是空。

### 坑 4：Android 9+ 禁明文 HTTP
见 §3.3。报错原文：`Cleartext HTTP traffic to 192.168.1.1 not permitted`。

### 坑 5：Kotlin smart cast 陷阱
`var lastWebInfo: SignalInfo?` 这类 **可空可变属性**，null-check 之后**不能直接**
调 `.hasData()` —— 编译器不允许 smart cast（因为属性可能被别的线程改）。
必须先锁成局部变量：

```kotlin
val web = lastWebInfo
if (web != null && web.hasData()) { ... }   // ✅
```

### 坑 6：Kotlin 块注释可以嵌套
KDoc 里写 `/cgi-bin/*.cgi` 会**开启嵌套注释**、报 `Unclosed comment`，
把后面的代码全吞掉，然后在**别的文件**里冒出一堆 `Unresolved reference`。
注释里别出现 `/*`。

### 坑 7：Kotlin 属性初始化器先于 `init` 块执行
`private var homeUrl = baseUrl + "/"` 会拿到空串（`baseUrl` 那时还没赋值）。
依赖其他属性的初始化必须挪进 `init { }`。

### 坑 8：jsoup 1.17.1 的 `Element` 没有 `form()`
取父级表单要写 `parent.parents().firstOrNull { it.tagName() == "form" }`。

---

## 8. 自测与回归

仓库根目录有一套**不依赖 Android** 的静态检查脚本，改完代码可以本地先跑：

```bash
# 1. Kotlin 静态体检（块注释嵌套 / 字符串闭合，几百毫秒）
python _lint_kt.py
#   → 检查 6 个文件，0 个有问题

# 2. 解析器逻辑回归（Python 等价移植 + 33 项）
python _test_parser.py
#   → 通过 33/33

# 3. G805 JSON 解析回归（真实响应样本 + 边界）
python _test_g805json.py
#   → 通过 8/8

# 4. ES5 降级器一致性（用真机抓的 12 份页面 JS 当语料）
NODE_PATH=<node_modules> node _test_es5fix.js _js
#   → 文件数 12 | 有改动 1 | 无改动 11 | 通过 12 | 失败 0

# 5. 接口挖掘正则（验证 cmd / goformId / 路径 挖得全）
node _test_mine.js _js
#   → ✓ 已知 cmd 全部挖到（4/4）
```

`_js/` 目录是**真机抓下来的 12 份路由器页面 JS**（约 655 KB），
是 4、5 两个脚本的语料。**非必须**，删掉不影响 App 运行，但建议保留以便以后回归。

**真机排查**：演示 App 的「诊断自检」按钮会导出一份完整报告（含登录过程、
页面清单、接口清单、业务 JS 源码、原生直连实测结果）。接入你自己的 App 时，
可以直接调：

```kotlin
client.diagText()       // 全过程文字日志
client.jsDump()         // 业务 JS 源码 + 接口清单（大，仅排查时用）
```

---

## 9. 已知边界

| 项 | 状态 |
|---|---|
| USR-G805（本设备固件） | ✅ 真机验证通过，ICCID / RSSI / RSRP / IMEI / IMSI / 制式 / 运营商 全部取到 |
| 其他型号 4G 路由器 | ⚠️ 未验证。`RouterClient` 的爬页面 + 候选接口探测是通用的，`probeG805ProcGet` 里第一条 `cmd` 需要按新设备改 |
| 固件升级后 | ⚠️ 字段名可能变。排查方法：用演示 App 的「网页模式」抓一份报告，里面的「接口清单」段会列出所有 `cmd` 真名 |
| 密码错误 / IP 不通 | ✅ `login()` 返回 `ok=false` + 可读原因；`netSelfCheck()` / `tcpTest()` 可定位是网卡还是 TCP 层问题 |
| 长时间运行 | ⚠️ `RouterClient.log` 是 `StringBuilder`，只增不减。如果做 7×24 常驻轮询，建议定期重建实例，或删掉 `slog()` 调用（见下） |

### 想精简 `RouterClient.kt` 的话

这 1273 行里**大部分是诊断/兜底代码**，正式集成可以删掉：

| 可以删 | 说明 |
|---|---|
| `netSelfCheck()` / `tcpTest()` / `probeHome()` | 诊断用 |
| `diagText()` / `pagesDump()` / `jsDump()` / `interfaceDump()` | 报告输出用 |
| `probeCgiInterfaces()` | 老固件的兜底探测 |
| `slog()` 内部实现 | 改成 `{}` 空实现，省掉 log 增长 |

**必须保留**：`login()` 及其依赖链（`loadHome` / `redirectTarget` / `findLoginForm` /
`buildBodies` / `tryPost` / `accepted` / `looksLoggedIn` / `tryHttpAuth` /
`isLoginPage` / `mineEndpoints`）、`fetchSignalInfo()`、`probeG805ProcGet()`、
以及 `get` / `post` / `enc` / `abs` / `exec` 这几个底层方法。

> 保守建议：**先原样集成跑通，再按上表删**。删完记得跑一遍 §8 的闸 1。

---

## 10. 交接清单

- [ ] clone 仓库：`git clone https://github.com/QualityLee/router-monitor.git`
- [ ] 拷 3 个文件 + 改包名（`SignalInfo.kt` / `HtmlParser.kt` / `RouterClient.kt`）
- [ ] 加依赖：`okhttp 4.12.0` + `jsoup 1.17.1`（Android 5.x 再加 desugaring）
- [ ] Manifest：`INTERNET` + `ACCESS_NETWORK_STATE` 权限
- [ ] 加 `network_security_config.xml`，并在 `<application>` 上引用 + `usesCleartextTraffic="true"`
- [ ] 按 §3.4 写调用代码（**放 IO 线程**、**复用实例**、**间隔 ≥10 秒**）
- [ ] 凭证移到 `BuildConfig` / 设置项，别硬编码
- [ ] 真机连上 G805 验证：ICCID / RSSI / RSRP / 制式 / 运营商 五项都能出
- [ ] （可选）路径 B：若需要通用兜底，再集成 `Es5Fix.kt` + `WebViewActivity.kt`
- [ ] 跑一遍 §8 的五个静态检查

---

## 附：怎么拿到一份「真实报告」做参照

排查时最有用的资料是**真机跑出来的诊断报告**。两份报告都可以用演示 App 自己导出：

| 报告 | 怎么出 | 里面有什么 |
|---|---|---|
| **网页模式报告** | 装演示 App → 点【② 网页模式】→ 结束后点【保存报告】 | **完整的业务 JS 源码**、接口清单（约 90 个 `cmd` 真名）、原生直连实测逐条结果、页面快照 |
| **诊断模式报告** | 点【① 诊断自检】→ 点【保存报告】 | 连通性自检（网卡/IP/TCP）、登录尝试全过程、页面清单 |

报告会写到设备外部存储（`WRITE_EXTERNAL_STORAGE`），点【复制】也可以直接贴到聊天里。

> 报告里含该设备的**真实 ICCID / IMEI / IMSI**。如果要把报告转给第三方（比如路由器厂商），
> 记得先脱敏。

**排查建议顺序**：
1. 先看报告头的 `页面 JS 错误` —— 不为 0 说明页面 JS 没跑起来（见 [坑 1](#坑-1chrome-39-不认-es6--这是路径-b-的核心坑)）
2. 再看「原生直连实测 `GET /reqproc/proc_get`」段第一条 —— 应能直接看到含 `ziccid` 的 JSON
3. 若第一条就是空值，检查 URL 里有没有 `multi_data=1`
4. 若全是 404，把报告里「接口清单」段的 `cmd` 真名拿出来，更新到 §4.2

