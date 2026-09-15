# USR-G805 4G 路由器监控 App

针对**安卓工控机(Android 5.1)+ 有人物联网 USR-G805 4G 工业路由器**的信号监控小工具。

## 📦 当前状态

**代码已就绪，但 APK 需要在你的电脑/云端编译**（沙箱里没有 Android 工具链）。
两条路任选一条 → 见下方「编译 APK」章节。

## 🎯 功能

- 自动登录路由器 webUI(USR-G805 自有后台,只需密码)
- 实时抓取:
  - **信号强度**(dBm)
  - **SIM 卡卡号 (ICCID)**
  - **IMEI / IMSI**
  - 网络制式 / 运营商 / 运行时间
- 每 10 秒自动刷新
- 后台轮询

## 🧩 适配差异(对比 USR-G781 等 OpenWrt 型号)

| 项 | USR-G781/G806(OpenWrt) | USR-G805 |
| --- | --- | --- |
| 架构 | LuCI CGI | 自有 webUI |
| 登录字段 | username + password | username=admin(固定)+ password |
| 登录 URL | `POST /cgi-bin/luci` | `POST /login.cgi` 或 `/cgi-bin/login.cgi` |
| 状态页 | `/admin/status/overview` | `/status.html` 或 `/overview.html` |
| 字段名 | RSRP/RSRQ/SINR | 信号强度 / SIM卡卡号 |

工程已针对 G805 调整;若你手头还有其他 USR 型号,改回 `RouterClient.kt` 里的登录/状态页 URL 即可。

## 🚀 编译 APK(两条路任选)

### 路 A — 本地编译(快,推荐)

**前提**:电脑上装了 **Android Studio**(自带 JDK 17 + Android SDK),或者单独装了 JDK 17 + Android SDK。

打开 PowerShell 或 CMD,进入本目录:

```powershell
.\build.ps1
# 或者
.\build.bat
```

脚本会自动找 SDK/Java/Gradle,跑 `gradle assembleDebug`。

完成后 APK 在:
```
.\app\build\outputs\apk\debug\app-debug.apk
```

如果脚本报告「没找到 SDK」,要么装 Android Studio,要么把命令行工具放到 `C:\Android\Sdk`。

### 路 B — GitHub Actions 云编译(无需本地环境)

1. 在 GitHub 上新建一个仓库(public/private 都行)
2. 把整个 `android-router-monitor/` 目录推上去
3. 切到 Actions 标签页 → 选 "Build Debug APK" → Run workflow
4. 跑完后在 Artifacts 下载 `app-debug`

不需要你电脑有任何 Android 工具,云端免费(每月 2000 分钟)。

### 路 C — Android Studio GUI(最直观)

1. 启动 Android Studio
2. `File → Open` 选 `android-router-monitor/` 目录
3. 等右下角 Gradle Sync 完成
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
5. 弹窗点 `locate` 跳到 APK 位置

## 📲 装到工控机

```bash
adb install app\build\outputs\apk\debug\app-debug.apk
```

或把 APK 拷到 U 盘,插工控机用文件管理器点击安装(需先在「设置 → 安全」里允许「未知来源」)。

## 🖥️ 启动 App

1. 打开「4G路由器监控」
2. 默认已填 `192.168.1.1 / admin`(USR-G805 出厂默认)
3. 点击 **登录并获取信息**
4. App 自动 10 秒拉一次数据

## 🔧 工作原理

USR-G805 webUI 是自家嵌入式 HTTP 服务,典型流程:

```
# 1. 拉登录页(GET /)
# 2. 提交表单
POST /login.cgi
Content-Type: application/x-www-form-urlencoded

username=admin&password=admin
→ Set-Cookie: SessionId=xxx
→ 302 Found

# 3. 拉状态页
GET /status.html
Cookie: SessionId=xxx
→ HTML 含 <td>信号强度</td><td>-97 dBm</td> 等

# 4. 正则提取
```

代码里对登录 URL 做了多候选(`/login.cgi`、`/cgi-bin/login.cgi`、`/login.html`),字段匹配适配中文标签。

## ⚠️ 常见问题

| 现象 | 处理 |
| --- | --- |
| 登录失败 | 浏览器先试试 192.168.1.1 能否打开、密码是否 admin。如果浏览器登录页 form action 不是 `/login.cgi` 或 `/cgi-bin/login.cgi`,把登录页 HTML(右键查看源文件)发我,我加上 |
| 信号强度是 `--` | G805 状态页字段名在不同固件版本可能略有差异。如果浏览器里显示的是"信号"而不是"信号强度",把状态页 HTML 拷给我,我加一行正则 |
| ICCID 是 `--` | 同上,字段名可能是 "SIM卡号"、"SIM ICCID"、"SIM 卡号" 等。把状态页 HTML 给我,我加正则 |
| 网络制式是 `--` | G805 这里显示的是 "TDD-LTE" / "FDD-LTE" / "4G" 等字样,只要在状态页能找到就能抓到 |
| 后台被杀,数据不刷新 | 工控机如果有电池优化,把 App 加白名单;或用 Foreground Service |

## 📐 信号强度阈值参考

| 信号 | 优 | 良 | 中 | 差 |
| --- | --- | --- | --- | --- |
| RSSI (dBm) | ≥ -70 | -70 ~ -85 | -85 ~ -100 | < -100 |

## 📁 工程结构

```
android-router-monitor/
├── README.md                       本文件
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── build.ps1                       一键编译脚本(PowerShell)
├── build.bat                       一键编译脚本(CMD)
├── .github/workflows/build.yml     云端编译工作流
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/workbuddy/routermon/
        │   ├── MainActivity.kt
        │   ├── RouterClient.kt        ★ 适配 G805
        │   └── SignalInfo.kt
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml
            └── xml/network_security_config.xml
```

---

## ☁️ 已用 GitHub Actions 成功编译（推荐路径）

因为本机安全策略封了 Java 进程间的 loopback TCP，Gradle 无法在本地运行。
工程已推送到 https://github.com/QualityLee/router-monitor ，云端编译已跑通。

产物（本地副本）：`outputs/RouterMonitor-debug.apk`（7.1 MB，minSdk 21，兼容 Android 5.1）

### 重新编译
1. 打开 https://github.com/QualityLee/router-monitor/actions
2. 左侧选 **Build Debug APK** → **Run workflow** → 选 main → 绿色按钮
3. 约 3 分钟完成，在 run 页面底部 **Artifacts** 下载 `app-debug.zip`

### 踩过的坑（已在仓库中修好）
- `android-actions/setup-android@v3` 已失效（内部执行 `sdkmanager tools`，该包已从 Google 仓库下架），
  现改为直接用 runner 镜像自带的 SDK + `sdkmanager` 装 `platforms;android-34` / `build-tools;34.0.0`。
- `gradle/actions/setup-gradle@v4` 指定 Gradle 8.5，与 AGP 8.2.2 / Kotlin 1.9.22 匹配。

### 安装到工控机（Android 5.1）
```cmd
adb install -r RouterMonitor-debug.apk
```
工控机若无法连 USB，把 APK 拷到 U 盘，用文件管理器安装（需开启「未知来源」）。


---

## ☁️ 已用 GitHub Actions 成功编译（推荐路径）

因为本机安全策略封了 Java 进程间的 loopback TCP，Gradle 无法在本地运行。
工程已推送到 https://github.com/QualityLee/router-monitor ，云端编译已跑通。

产物（本地副本）：`outputs/RouterMonitor-debug.apk`（7.1 MB，minSdk 21，兼容 Android 5.1）

### 重新编译
1. 打开 https://github.com/QualityLee/router-monitor/actions
2. 左侧选 **Build Debug APK** → **Run workflow** → 选 main → 绿色按钮
3. 约 3 分钟完成，在 run 页面底部 **Artifacts** 下载 `app-debug.zip`

### 踩过的坑（已在仓库中修好）
- `android-actions/setup-android@v3` 已失效（内部执行 `sdkmanager tools`，该包已从 Google 仓库下架），
  现改为直接用 runner 镜像自带的 SDK + `sdkmanager` 装 `platforms;android-34` / `build-tools;34.0.0`。
- `gradle/actions/setup-gradle@v4` 指定 Gradle 8.5，与 AGP 8.2.2 / Kotlin 1.9.22 匹配。

### 安装到工控机（Android 5.1）
```cmd
adb install -r RouterMonitor-debug.apk
```
工控机若无法连 USB，把 APK 拷到 U 盘，用文件管理器安装（需开启「未知来源」）。
