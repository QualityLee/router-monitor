# 4G 路由器信号监控 App（USR-G805 实测）

针对**安卓工控机 (Android 5.1) + 有人物联网 USR-G805 4G 工业路由器**的信号监控小工具。

工控机通过网线接 G805 的 LAN 口 → App 自动登录路由器后台 → 读取 **信号强度** 与 **SIM 卡 ICCID**。

---

## 🎯 这版（v1.2）解决了什么

v1.1 装机实测仍然提示「登录失败」。**用真机诊断日志把根因彻底定位清楚了**，两个都是页面结构问题，跟 IP / 密码完全无关：

### 根因一：`GET /` 只是个 JS 跳转壳

```
真机实测：GET /  → HTTP 200，只有 168 字节
内容：<script>window.location.href="index.html";</script>
```

真正的后台在 `/index.html`（14442 字节）。旧代码把这个 168 字节的壳当首页，
于是「页面文字」为空、「首页未发现 `<form>`」、登录态也判不出来 —— 后面全错位。

### 根因二：新 webui 是 Knockout.js **单页应用（SPA）**

`/index.html` 的静态 HTML 里**一个数据值都没有**，全是 `data-bind` 占位：

```html
<div data-bind="text: networkType"></div>
<div data-bind="text: networkOperator"></div>
<div id="signal_strength" data-bind="text: rssi"></div>
<a href="#network_details" data-trans="network_details">网络</a>
```

数据全靠页面 JS 通过 AJAX 从后端拉。**所以纯 HTTP 客户端永远抓不到值** —— 不是登录没成功，
是「登录成功了也没东西可抓」。同时 `data-trans="logout"` 让旧的登录态判断误以为已登录。

### 决定性线索

```
真机实测：GET /cgi-bin/status.cgi → HTTP 200，152 字节
响应：Document Error: Data follows / Access Error: Data follows / NOT POST REQUEST
```

→ 该接口**存在**，而且**只接受 POST**。SPA 的数据接口就在 `/cgi-bin/` 下。

### 顺带修掉的假值污染

诊断报告末尾出现过 `运营商=lay: none; position: relative;">` ——
SPA 属性里带 `>`（如 `data-bind="visible: x > 0"`）会让 HTML 标签正则提前断开，
残留的 CSS 片段被解析器当成了数据。现在有专门的黑名单过滤。

---

## 🔧 v1.2 的五步递进策略

| 步 | 能力 | 说明 |
| --- | --- | --- |
| 1 | **跟随跳转壳** | `GET /` 后自动识别 HTTP 3xx `Location` / JS `location.href` / `meta refresh`，跟到真正的首页（G805 会跟到 `/index.html`） |
| 2 | **SPA 识别** | 页面没有 `<form>` 且有 ≥3 处 `data-bind` / 引用 knockout / jquery.js → 标记为 SPA，并把结论明写在日志里 |
| 3 | **从 JS 挖接口** | 抓取页面引用的同源 JS（最多 10 个），用 4 条正则挖出 `/cgi-bin/...`、`/goform/...`、`/api/...` 等真实接口，再逐个 POST 试 |
| 4 | **CGI 探测** | 对 13 个常见 `/cgi-bin/*.cgi` × 3 种请求体逐个 POST，只把「非错误页」的响应记进报告 |
| 5 | **网页模式（主力）** | 真浏览器内核跑完 SPA 的 JS，**注入拦截器记录每一次 XHR / fetch / jQuery.ajax 的 URL、请求体、状态码、响应体**，再遍历所有 `#hash` 导航页抓渲染后的 DOM |

另外保留 v1.1 的通用能力：登录表单自动发现、401 HTTP 授权兜底、通用「标签→值」解析、
页面 BFS 爬取、诊断日志导出。

---

## 📲 使用（请按这个顺序）

1. 打开 App，确认顶部是 `192.168.1.1` / `admin`（G805 出厂默认，用户名和密码都是 `admin`）
2. 点 **① 诊断自检** —— 它会打印完整链路，并明确告诉你是不是 SPA 固件
3. 若日志里出现 `★ 结论：这是 JS 单页应用，纯 HTTP 请求拿不到数据` →
   点 **网页模式(推荐)**
4. 网页模式会自动：打开后台 → 填密码框并点登录 → 等登录成功 → 遍历所有导航页 → 抓渲染后的 DOM
5. 完成后点 **导出接口记录**，内容会写到 `/sdcard/routermon_webview.txt`，同时**点一下日志框可复制到剪贴板**
6. 把导出的内容发回来，里面包含**真实接口名 + 请求体 + 响应体**，据此就能把 App 改成直连接口

日常使用：**② 登录并获取**，之后每 10 秒自动刷新。

---

## 🔍 关于 G805 的真实页面（官方说明书 + 真机日志）

- 登录页：页面上是「需要授权 / 请输入用户名和密码。」+ 用户名 + 密码 + 「登录」按钮 —— **页面内模态框**，不是浏览器原生弹窗
- 状态总览页：导航为 `状态总览 | 路由表 | 服务 | 网络 | VPN | 防火墙 | 系统 | 退出`
- 信号与运营商在「网络 IPv4 WAN 状态」里，形如：

  ```
  RSSI: 31   运营商信息: 中国联通   模式: FDD-LTE(4G)   已连接: 0h 49m 24s
  ```

  注意 **`RSSI: 31` 是 0~31 的原始等级，不是 dBm**。App 会自动补注换算提示（≈ -51 dBm），避免误读成「信号很差」。
- ICCID / IMEI 不在状态总览，通常在「网络」子页里。

---

## 🧪 编译前本地验证（两道闸）

### 1. 解析器回归

`_test_parser.py` 是 `HtmlParser.kt` 的 Python 等价移植。

```bash
python _test_parser.py
```

覆盖 **33 项**：13 个页面用例 + 20 个 `clean()` 假值过滤单元用例。

```
[PASS] G805状态总览(说明书原文) / (单行拍平)
[PASS] G805登录页(应空) / 老版G805登录页(应空)
[PASS] G805 SPA首页(应空)              ← 真机回归：静态 HTML 必须解析为空
[PASS] G805属性断裂(不得产出CSS假值)   ← 真机回归：data-bind 里的 ">" 不能让 CSS 溢出成数据
[PASS] 表格型 / span双列 / 冒号文本 / label独占行 / JSON / 下拉框网络制式 / 版本号干扰
[PASS] clean('lay: none; position: relative;">') = None
[PASS] clean('中国联通') = '中国联通'   ... 共 20 项
通过 33/33
```

### 2. Kotlin 静态体检

```bash
python _lint_kt.py
```

专门扫「块注释 / 字符串是否闭合」。**这道闸是被一次真实翻车逼出来的**：

> Kotlin 的块注释**支持嵌套**。在 KDoc 里写 `/cgi-bin/*.cgi`，那个 `/*` 会被当成
> 开启一层嵌套注释，于是外层注释的 `*/` 被内层吃掉 → 编译器报 `Unclosed comment`，
> 整个文件后面的代码全被注释掉，还会在**别的文件**里冒出一堆莫名其妙的
> `Unresolved reference`（一次编译 252 条报错，全是这一个字符引起的）。
> 云上编译一次好几分钟，本地扫一遍几毫秒。

---

## ☁️ 编译（本机 Gradle 被 EDR 阻断，走云端）

本机安全策略封了 Java 进程间的 loopback TCP，Gradle 起不来守护进程，因此改用 **GitHub Actions 云编译**。

- 仓库：https://github.com/QualityLee/router-monitor
- Actions：https://github.com/QualityLee/router-monitor/actions

推代码（绕过 git，走 REST API）：

```bash
GITHUB_TOKEN=ghp_xxx python push_tree.py "提交说明"
```

> **为什么不是 `push_via_api.py`**：Contents API 每传一个文件 = 一次 push = **一次 Actions 构建**，
> 20 个文件就是 20 次构建（还得手动取消 19 次）。
> `push_tree.py` 用 Git Data API（blobs → tree → commit → update-ref）把整工程合成
> **一个 commit**，因此**只触发 1 次构建**。`push_via_api.py` 作为单文件补传的备用工具保留。

盯构建 + 下载产物：

```bash
GITHUB_TOKEN=ghp_xxx python ci.py cancel-stale      # 取消历史排队构建，只留最新
GITHUB_TOKEN=ghp_xxx python ci.py wait  <run_id>
GITHUB_TOKEN=ghp_xxx python ci.py apk   <run_id> ../outputs
```

产物：`outputs/RouterMonitor-debug.apk`（minSdk 21，兼容 Android 5.1）

安装：

```cmd
adb install -r RouterMonitor-debug.apk
```

工控机若无法连 USB，把 APK 拷到 U 盘，用文件管理器安装（需开启「未知来源」）。

### 云编译踩过的坑（已修）

- `android-actions/setup-android@v3` 已失效（内部执行 `sdkmanager tools`，该包已从 Google 仓库下架）。
  现改为直接用 runner 镜像自带的 SDK，再 `sdkmanager` 装 `platforms;android-34` / `build-tools;34.0.0`。
- `gradle/actions/setup-gradle@v4` 固定 Gradle 8.5，与 AGP 8.2.2 / Kotlin 1.9.22 匹配。
- Android 5.1 (API 22) 缺 `java.util.stream` / `java.time`，已在 `app/build.gradle.kts` 打开
  `isCoreLibraryDesugaringEnabled = true` 并引入 `desugar_jdk_libs`。
- 下载 artifact 要禁用自动重定向（Azure Blob 的二次跳转会导致 403）。
- 取 Actions **job 日志**同样要禁用重定向，否则 `Authorization` 头会被转发到 Blob 存储而 401。
- 类属性初始化器**先于 `init` 块执行**，所以 `var x = baseUrl + "/"` 这种写法拿到的是空值
  （`baseUrl` 在 `init` 里才赋值）。要么在 `init` 里赋值，要么改写成 `by lazy`。
- 注入到 WebView 的脚本必须严格 **ES5** —— 工控机 Android 5.1 的 WebView 是 **Chrome 39**，
  不能用 `let/const`、箭头函数、模板字符串、`Promise`。

---

## 📁 工程结构

```
android-router-monitor/
├── README.md
├── _test_parser.py                 解析器 Python 等价移植 + 33 项回归
├── _lint_kt.py                     Kotlin 注释/字符串闭合静态体检
├── push_tree.py                    一次性提交整个工程（只触发 1 次构建）
├── push_via_api.py                 单文件补传备用
├── ci.py                           查/取消/等待 Actions，下载 APK、拉 job 日志
├── build.ps1 / build.bat           本地一键编译（需要 Android Studio 环境）
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── .github/workflows/build.yml     云端编译工作流
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/workbuddy/routermon/
        │   ├── MainActivity.kt      界面 + 诊断流程 + 接收网页模式结果
        │   ├── RouterClient.kt      自检 / 跟跳转 / 登录 / SPA 识别 / JS 挖接口 / BFS 抓取
        │   ├── HtmlParser.kt        通用「标签→值」提取 + 假值过滤
        │   ├── WebViewActivity.kt   网页模式：注入拦截器 + 自动登录 + 遍历导航页
        │   └── SignalInfo.kt        数据模型
        └── res/
            ├── layout/activity_main.xml
            ├── layout/activity_webview.xml
            ├── values/strings.xml
            └── xml/network_security_config.xml
```

---

## ⚠️ 安全提醒

推送用的 GitHub Personal Access Token 建议**用完后立即撤销**：
https://github.com/settings/tokens
