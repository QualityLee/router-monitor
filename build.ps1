<#
.SYNOPSIS
    一键编译 USR-G805 监控 App 的 debug APK
.DESCRIPTION
    适用场景:你电脑装了 Android Studio(自带 JDK + Android SDK),懒得开 GUI。
    在 PowerShell 中执行:  .\build.ps1
    编译完后 APK 在:          .\app\build\outputs\apk\debug\app-debug.apk
.NOTES
    如果电脑没有 Android SDK,脚本会尝试自动下载命令行工具(国内用户可能要开代理)
#>

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# ---------- 1. 找 Android SDK ----------
$SDK_PATHS = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    "$env:LOCALAPPDATA\Android\Sdk",
    "$env:USERPROFILE\AppData\Local\Android\Sdk",
    "C:\Android\Sdk"
) | Where-Object { $_ }

$SDK = $null
foreach ($p in $SDK_PATHS) {
    if (Test-Path (Join-Path $p "platform-tools\adb.exe")) {
        $SDK = $p
        break
    }
}

if (-not $SDK) {
    Write-Host "[×] 没找到 Android SDK。" -ForegroundColor Red
    Write-Host "    请先装 Android Studio:https://developer.android.com/studio" -ForegroundColor Yellow
    Write-Host "    或者手动下载命令行工具放到 C:\Android\Sdk 后重试。" -ForegroundColor Yellow
    exit 1
}
Write-Host "[✓] Android SDK: $SDK" -ForegroundColor Green
$env:ANDROID_HOME = $SDK
$env:PATH = "$SDK\platform-tools;$SDK\cmdline-tools\latest\bin;$env:PATH"

# ---------- 2. 找 Java ----------
$JAVA_HOME_CANDIDATES = @(
    $env:JAVA_HOME,
    "$env:ProgramFiles\Android Studio\jbr",
    "$env:ProgramFiles\Android Studio\Android Studio\jbr",
    "$env:ProgramFiles\Eclipse Adoptium\jdk-17*"
) | Where-Object { $_ }

$JAVA = $null
foreach ($p in $JAVA_HOME_CANDIDATES) {
    if (Test-Path (Join-Path $p "bin\java.exe")) {
        $JAVA = $p
        break
    }
}
if (-not $JAVA) {
    try { $cmd = Get-Command java -ErrorAction Stop; $JAVA = Split-Path $cmd.Source -Parent | Split-Path -Parent } catch {}
}
if (-not $JAVA) {
    Write-Host "[×] 没找到 Java。请安装 JDK 17。" -ForegroundColor Red
    exit 1
}
Write-Host "[✓] Java:       $JAVA" -ForegroundColor Green
$env:JAVA_HOME = $JAVA
$env:PATH = "$JAVA\bin;$env:PATH"

# ---------- 3. 找 Gradle ----------
$GRADLE = $null
try { $cmd = Get-Command gradle -ErrorAction Stop; $GRADLE = $cmd.Source } catch {}

if (-not $GRADLE) {
    Write-Host "[i] 系统没装 gradle,改用 gradle wrapper..." -ForegroundColor Yellow
    if (-not (Test-Path "gradlew.bat")) {
        # 用项目自带 wrapper,如果也没有就让用户装
        Write-Host "[×] 缺少 gradlew.bat。请在 Android Studio 中打开本项目一次(会自动生成 wrapper)。" -ForegroundColor Red
        exit 1
    }
    & .\gradlew.bat --version | Out-Null
    $GRADLE = ".\gradlew.bat"
}

# ---------- 4. 编译 ----------
Write-Host ""
Write-Host ">>> 编译 APK ..." -ForegroundColor Cyan
if ($GRADLE -like "*.bat") {
    & $GRADLE assembleDebug --no-daemon --console=plain
} else {
    & $GRADLE assembleDebug --no-daemon --console=plain
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "[×] 编译失败,请看上面日志。" -ForegroundColor Red
    exit 1
}

# ---------- 5. 收尾 ----------
$APK = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $APK) {
    $size = "{0:N2}" -f ((Get-Item $APK).Length / 1MB)
    Write-Host ""
    Write-Host "[✓] APK 编译成功!  文件大小: $size MB" -ForegroundColor Green
    Write-Host "    路径: $APK" -ForegroundColor Green
    Write-Host ""
    Write-Host "下一步,把它装到工控机上:" -ForegroundColor Cyan
    Write-Host "    adb install `"$APK`"" -ForegroundColor White
} else {
    Write-Host "[×] 没找到生成的 APK,编译可能没成功。" -ForegroundColor Red
    exit 1
}