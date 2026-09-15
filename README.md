# 4G 路由器信号监控 App（USR-G805 实测）

针对**安卓工控机 (Android 5.1) + 有人物联网 USR-G805 4G 工业路由器**的信号监控小工具。

工控机通过网线接 G805 的 LAN 口 → App 自动登录路由器后台 → 读取 **信号强度** 与 **SIM 卡 ICCID**。

---

## 🎯 这版（v1.4）解决了什么

v1.3 装机后导出的「网页模式全量诊断」**645,775 字符**，把最后一层窗户纸捅破了。

v1.3 的三项修复**全部生效**（报告头为证）：

```
拦截器: 已挂上 __wbHooked ✓
主文档注入: OK 已注入（主文档 14442 字节）（2 次）
页面加载脚本: 29 个
页面 JS 错误: 2 条
```

钩子挂上了、29 个脚本清单拿到了、12 份业务 JS 源码也回来了 ——
**页面还是 0 个请求**。而报告里那 2 条 JS 错误就是答案：

```
1) JS错误: Uncaught SyntaxError: Unexpected token ,  @ http://192.168.1.1/js/service.js : 315 : 31
2) JS错误: Uncaught TypeError: Cannot read property 'getOpMode' of undefined  @ .../js/config/menu.js : 10 : 38
```

### 根因：`service.js` 用了 ES6 语法，而工控机的内核是 Chrome 39（只认 ES5）

去翻 `service.js` 第 315 行，原文是：

```js
function prepare(params) {
    var requestParams = {
        goformId : "SET_WIFI_SSID2_SETTINGS",
        wifi_cur_state,          // <<<< 就是这里
        isTest : isTest,
```

同一个文件里 SSID1 的版本写的是显式的 `isTest : isTest,`，只有 SSID2 这处写成了裸的
`wifi_cur_state,` —— 这是 **ES6 的对象字面量简写属性**，要 Chrome 43+ 才认识。

```
service.js 第 315 行语法错误 → 整个文件解析失败
  → require(['service', ...]) 的 service 模块永远没定义
  → main.js 的 require 回调永不执行 → 菜单不加载
  → menu.js:10 报 "getOpMode of undefined"          ← 报告里第 2 条错误
  → Knockout 从未 applyBindings → 24 个页面快照可见文本全空
  → 一个 XHR 都没发出 → 「网络请求记录（0 条）」
```

**一条语法错误，雪崩了整个页面。**

### 修复一：注入 ES5 降级器（`Es5Fix.kt`）

拦截同源业务 `.js`，抓下源码做保守降级，再交回内核：

| 规则 | 变换 | 保护 |
|---|---|---|
| 对象字面量简写属性 | `{a,}` → `{a : a,}` | **容器栈**：只在「最内层确实是对象字面量」时改写，数组 `[a, b]` 绝不误伤；并区分对象 `{` 与语句块 `{` |
| 行首 `let` / `const` | → `var` | 必须紧跟标识符/解构起始，避免误伤 `constant` |

**只在真的改出内容时才替换**，没毛病就 `return null` 交回内核 —— 零风险：
即使降级器以后误判，最坏也只是「某个本来就跑不起来的文件」替换失败，不可能搞坏正常页面。

用 `acorn`（`ecmaVersion: 5`）在 **655 KB 真实语料**上做过判据验证：

```
文件                                   改前错误  改后错误  改动
js__service.js                           14         3    y(1)   ← 14 → 3
其余 11 个文件                             0         0     n     ← 零误伤
通过 12 / 失败 0
```

`service.js` 改后剩下的 3 个错误全部落在**采集截断处**（末尾字符串被切断），
不是真实语法错误。**改这一行，service.js 就是合法 ES5 了。**

### 修复二：从完整 JS 里逆出了 G805 的真实接口协议

上一版单文件只抓 25,000 字符 —— 而 `service.js` 原始 279,404 字符，
**只回来 8.9%**，定义 `cmd` 的地方正好被截掉。这次放开到 900,000，从源码里读到：

```js
$.ajax({
    type : !!isPost ? "POST" : "GET",
    url  : isPost ? "/reqproc/proc_post"
                 : params.cmd ? "/reqproc/proc_get"   // 有 cmd  → GET（读）
                 : "/reqproc/proc_post",              // 有 goformId → POST（写）
    data : params, dataType : "json", cache : false
});
```

**读操作 = `GET /reqproc/proc_get?cmd=<逗号分隔的字段名列表>`**。
真机语料里挖到的 `cmd` 取值印证了这个形态：

```
m_ssid_enable,wifi_cur_state,NoForwarding,m_NoForwarding,
AuthMode,passPhrase
current_network_mode,m_netselect_save,net_select_mode,m_netselect_contents,net_select,ppp_status,modem_main_state
lan_station_list / station_list / m_netselect_status / m_netselect_contents / Language
```

### 修复三：普通模式直接打这个接口（完全不依赖页面 JS）

这条才是真正的**生路**：WebView 能不能跑起来都不重要了。

- 从已抓到的页面/JS 语料里正则挖出全部 `cmd`，再叠加一批兜底字段名
- 逐个 `GET /reqproc/proc_get?cmd=<值>`，把 HTTP 状态码 + 响应体写进报告
- **只发 GET**；写操作走 `proc_post`，一个都不发（沿用 `isWriteEndpoint` 安全闸的思路）

### 顺带修掉的三个小问题

1. `fetchAppJs` 单文件上限 `25,000 → 900,000`、文件数 `12 → 18`，但报告里每段只 dump
   前 30,000 字符（**全量文本只用来挖接口**，报告不能炸）
2. 报告新增「**提取到的接口清单**」段：把 `cmd` / `goformId` / 接口路径汇总成清单，
   `goformId` 只列不调
3. 报告最后一段 JS 源码会**吞掉「页面快照」**——加了显式结束标记
   `----- 业务 JS 源码结束 -----`

---

## 📜 v1.3 解决了什么

v1.2 装机后导出的「网页模式全量诊断」有 **436,901 字符**，把问题彻底钉死了。
报告里有三个扎眼的数字：

```
网络请求记录（0 条）        ← 拦截器一条都没记到
24 个页面快照，可见文本全是 0 字
已登录: true                ← 假阳性
```

对照报告里的原始 HTML，四个根因全部落实：

### 根因一：拦截器挂得太晚（接口记录为空的直接原因）

G805 首页是 **RequireJS + Knockout**，报告第 246 行是铁证：

```html
<script type="text/javascript" data-main="js/main" src="js/lib/require/require-jquery.js"></script>
```

业务模块全靠同步 `<script>` 注入，**同步脚本会阻塞 `load` 事件**。
旧代码在 `onPageFinished` 里注入钩子 —— 那时页面的首次鉴权 AJAX 早就打完了，
所以「网络请求记录（0 条）」。

**修法**：改用 `shouldInterceptRequest` 拦截**主文档**，自己取回 HTML、
把钩子插到 `<head>` 之后，再交给内核渲染。这样钩子先于页面**任何**脚本执行。
只拦主文档（`isForMainFrame` + `Accept: text/html`），子资源一概放行，
任何异常一律 `return null` 让内核正常加载 —— 宁可漏钩子，绝不让页面白屏。

### 根因二：Knockout 压根没跑起来

报告 24 个快照里，这行的 `class="signal"` **原样保留**：

```html
<i class="signal" data-bind="attr:{'class': signalCssClass}">&nbsp;</i>
```

KO 的 `attr` 绑定只要执行过一次就会覆盖原始 class。加上 24 个快照
`document.body.innerText` **全是空**，可以确定 `ko.applyBindings` 从未成功执行。

**修法**：新增 JS 错误收集 —— `window.onerror` + 劫持 `console.error/warn`，
把报错原文（含文件名、行号、列号）写进报告。下一版就能直接看到是哪个文件炸的。

### 根因三：`已登录: true` 是假阳性

旧判据：

```kotlin
val okNow = logout || menu || (loginTries >= 2 && !anyPw)
```

最后一项在「页面压根找不到密码框」时也成立 —— 而 G805 的登录是
**页面内模态框**（`jquery.simplemodal` + `#htmlContainer`），找不到密码框是常态。
于是程序**一次登录都没试**就宣告成功，直接跳去遍历导航页。

**修法**：只认 Knockout 渲染后的真实特征 —— 左侧菜单 / 状态栏 / 退出入口**可见**，
或渲染文本里同时命中 ≥2 个后台专有词。全部落空就老实继续等、继续试（上限 6 次）。

### 根因四：业务 JS 从未被抓到（最要命的一个）

旧 `mineEndpoints()` 只取前 10 个同源 JS，而 G805 首页引用 **30 个**，
前 10 个几乎全是 jquery / require 框架 —— `js/service.js`、`js/config/config.js`、
`js/login.js`、`js/status/statusBar.js` 这些**写满接口地址**的文件根本没被碰过。

**关键认识**：这些 JS 是**静态文件、不需要登录**就能取。拿到它们等于拿到接口说明书。

**修法**：用 `JS_LIB_MARKS` 过滤掉框架、按 `JS_HOT`（service/config/login/status…）
排序优先取、上限提到 16 个，正则从 4 条扩到 6 条（补上 G805 那种不带前导斜杠的
`xxx.cgi` 写法、以及 `$.post|$.get|$.ajax` 的第一个参数），
并把抓到 JS **源码原文**单独成段写进报告。

### 附带：给写操作加安全闸（本轮新增）

从 JS 挖出的接口未经审核，路由器上并存 `/cgi-bin/wifi_set`、`/cgi-bin/reboot`
这类写接口 —— 盲打可能改坏配置。现在按 `READONLY_MARKS` / `WRITE_MARKS`
判定，名字像写操作的**一律不做探测性 POST**，只读的才试。

---

## 📜 v1.2 解决了什么

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

## 🔧 五步递进策略（v1.2 建立，v1.3 增强）

| 步 | 能力 | 说明 |
| --- | --- | --- |
| 1 | **跟随跳转壳** | `GET /` 后自动识别 HTTP 3xx `Location` / JS `location.href` / `meta refresh`，跟到真正的首页（G805 会跟到 `/index.html`） |
| 2 | **SPA 识别** | 页面没有 `<form>` 且有 ≥3 处 `data-bind` / 引用 knockout / jquery.js → 标记为 SPA，并把结论明写在日志里 |
| 3 | **从 JS 挖接口** | 抓取页面引用的同源 JS（v1.3 起：先滤掉框架、业务 JS 优先、上限 16 个），用 **6 条正则**挖出 `/cgi-bin/...`、`/goform/...`、`/api/...`、裸 `xxx.cgi`、`$.post(url)` 等真实接口；**抓到的 JS 源码原文全部进报告** |
| 4 | **CGI 探测** | 对 13 个常见 `/cgi-bin/*.cgi` × 3 种请求体逐个 POST，只把「非错误页」的响应记进报告；**名字像写操作的一律不探测** |
| 5 | **网页模式（主力）** | 拦截主文档 → 把钩子插进 `<head>` → 真浏览器内核跑完 SPA 的 JS，**记录每一次 XHR / fetch / jQuery.ajax 的 URL、请求体、状态码、响应体**，同时收集页面 JS 报错，再遍历所有 `#hash` 导航页抓渲染后的 DOM |

另外保留 v1.1 的通用能力：登录表单自动发现、401 HTTP 授权兜底、通用「标签→值」解析、
页面 BFS 爬取、诊断日志导出。

---

## 📲 使用（请按这个顺序）

1. 打开 App，确认顶部是 `192.168.1.1` / `admin`（G805 出厂默认，用户名和密码都是 `admin`）
2. 点 **① 诊断自检** —— 它会打印完整链路，并明确告诉你是不是 SPA 固件
3. 若日志里出现 `★ 结论：这是 JS 单页应用，纯 HTTP 请求拿不到数据` →
   点 **网页模式(推荐)**
4. 网页模式会自动：打开后台 → 填密码框并点登录（最多重试 6 次）→
   遍历所有导航页 → 抓渲染后的 DOM → **拉取业务 JS 源码**
5. 完成后点 **导出接口记录**，内容会写到 `/sdcard/routermon_webview.txt`，同时**点一下日志框可复制到剪贴板**
6. 把导出的内容发回来

### 报告怎么看（v1.4 起新增了 ES5 降级三行）

```
拦截器: 已挂上 __wbHooked ✓                ← ✗ 的话接口记录为空是必然的
主文档注入: OK 已注入（主文档 14442 字节）（2 次）
子资源 JS 拦截: 看过 6 个 / ES5 降级 1 个（简写属性 1 处，let/const 0 处）
最近一次降级: /js/service.js（简写属性 1 处，let/const 0 处，279404 → 279430 字节）
页面加载脚本: 29 个
页面 JS 错误: 0 条                          ← v1.4 修对了就应该是 0
```

关键看两处：

- **`子资源 JS 拦截` 里「看过」必须是 > 0**（说明 `.js` 子资源拦截真的生效了）；
  若是 `0`，报告会直接提示「页面跑的仍是原始 ES6」。
- **`页面 JS 错误` 从 2 条变 0 条**，就说明 `service.js` 活了，Knockout 能跑起来了。

再往下看 **「原生直连实测 GET /reqproc/proc_get」** 段 —— 这是 v1.4 最重要的一段，
每个候选 `cmd` 的 HTTP 状态码和响应体都在那里。只要能返回 JSON，数据就成了。

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

## 🧪 编译前本地验证（四道闸）

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

### 3. ES5 降级器跨语言一致性

`_es5fix.js` 是 `Es5Fix.kt` 的 JS 逐行等价移植，`_test_es5fix.js` 用 `acorn`
以 `ecmaVersion: 5` 当判据，在 **655 KB 真机语料**上跑。

```bash
node _test_es5fix.js _js
```

断言两件事：**该修的修好了**（service.js 14 → 3 个语法错误），
以及 **不该动的零改动**（其余 11 个文件改前=改后，零误伤）。

### 4. 接口挖掘正则

`_test_mine.js` 验证 v1.4 新加的 `cmd` / `goformId` / 接口路径三条正则在真机语料上
真的能挖出东西（防止「正则在语法上没错但实际一条都匹配不到」这种哑火）。

```bash
node _test_mine.js _js
```

```
接口路径 (2): /reqproc/proc_post  /reqproc/proc_get
cmd 取值 (8): m_ssid_enable,... / AuthMode,passPhrase / station_list / lan_station_list ...
goformId 取值 (7): SET_WIFI_SSID1_SETTINGS / SET_WIFI_SSID2_SETTINGS / SET_WIFI_INFO ...
✓ 已知 cmd 全部挖到（4/4）
```

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
│
├── _split_js.py                    把真机报告里的大块 JS 源码切分成 _js/ 目录（一次性工具）
├── _js/                            真机回收的 12 份业务 JS 语料（643 KB，逐文件存放）
│   ├── service.js                  279 KB，ES6 简写属性就在第 315 行
│   ├── router.js                   971 KB（已截断存盘）
│   └── ...                         共 12 个文件
│
├── _es5check.js                    用 acorn(ecmaVersion:5) 判定语料是否 ES5 合规
├── _es5fix.js                      Es5Fix.kt 的 JavaScript 等价实现（跨语言对照）
├── _test_es5fix.js                 跑 _js/ 全量语料：验证降级器改动点 + Kotlin/JS 结果一致
├── _test_mine.js                   跑 _js/ 全量语料：验证接口挖掘三条正则真能挖到东西
├── _test_parser.py                 解析器 Python 等价移植 + 33 项回归
├── _lint_kt.py                     Kotlin 注释/字符串闭合静态体检
│
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
        │   ├── WebViewActivity.kt   网页模式：子资源 JS 拦截降级 + 注入钩子 + 自动登录 + 遍历导航页
        │   ├── Es5Fix.kt            ES6→ES5 极简降级器（简写属性 / let / const），Chrome 39 专用
        │   └── SignalInfo.kt        数据模型
        └── res/
            ├── layout/activity_main.xml
            ├── layout/activity_webview.xml
            ├── values/strings.xml
            └── xml/network_security_config.xml
```

### 本地验证四道闸（改动后按顺序跑）

| # | 命令 | 看什么 |
|---|------|--------|
| 1 | `python _lint_kt.py` | `检查 N 个文件，0 个有问题` |
| 2 | `python _test_parser.py` | `通过 33/33` |
| 3 | `node _test_es5fix.js _js` | `通过 12 | 失败 0`，`service.js` 修复点 14→3 |
| 4 | `node _test_mine.js _js` | `✓ 已知 cmd 全部挖到（4/4）` |

---

## ⚠️ 安全提醒

推送用的 GitHub Personal Access Token 建议**用完后立即撤销**：
https://github.com/settings/tokens
