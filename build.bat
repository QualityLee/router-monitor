@echo off
REM 一键编译 APK (CMD 版,如果 PowerShell 被禁用用这个)
REM 用法:双击本文件

setlocal
cd /d "%~dp0"

REM 找 SDK
set "SDK="
if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK if exist "C:\Android\Sdk\platform-tools\adb.exe" set "SDK=C:\Android\Sdk"

if not defined SDK (
    echo [X] 没找到 Android SDK,请先装 Android Studio。
    pause & exit /b 1
)
echo [OK] Android SDK: %SDK%
set "ANDROID_HOME=%SDK%"
set "PATH=%SDK%\platform-tools;%PATH%"

REM 找 Java
where java >nul 2>&1
if errorlevel 1 (
    echo [X] 没找到 Java,请安装 JDK 17。
    pause & exit /b 1
)
echo [OK] Java 已就绪

REM 编译
echo.
echo 编译 APK 中...
call gradle.bat assembleDebug --no-daemon --console=plain
if errorlevel 1 (
    echo [X] 编译失败。
    pause & exit /b 1
)

REM 收尾
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo.
    echo [OK] APK 编译成功: %CD%\app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo 下一步,安装到工控机:
    echo     adb install app\build\outputs\apk\debug\app-debug.apk
) else (
    echo [X] 没找到 APK,编译可能失败。
)
pause