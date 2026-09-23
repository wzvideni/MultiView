@echo off
chcp 65001 >nul
title MediaMTX RTSP Server
cd /d "%~dp0"

set "LOCAL_IP="
for /f "tokens=4" %%a in ('route print ^| findstr "0.0.0.0.*0.0.0.0"') do (
    set "LOCAL_IP=%%a"
)
if "%LOCAL_IP%"=="" set "LOCAL_IP=127.0.0.1"

echo ==============================================================================
echo                 MediaMTX RTSP 1-32 路多路监控模拟推流服务
echo ==============================================================================
echo  [网络信息]
echo    本机局域网 IP: %LOCAL_IP%
echo    RTSP 端口: 8554 (TCP/UDP)
echo.
echo  [播放地址列表]:
echo    - 辅码流 [预览 640x360@15fps, 1关键帧/秒]:
echo      rtsp://%LOCAL_IP%:8554/live/sub01  ~  rtsp://%LOCAL_IP%:8554/live/sub32
echo.
echo    - 主码流 [全高清 1920x1080@25fps, 1关键帧/秒]:
echo      rtsp://%LOCAL_IP%:8554/live/main01 ~  rtsp://%LOCAL_IP%:8554/live/main32
echo.
echo    - USB 调试直连 (Android 手机连接 USB 时):
echo      rtsp://127.0.0.1:8554/live/sub01  ~  rtsp://127.0.0.1:8554/live/sub32
echo ==============================================================================
echo.

where ffmpeg >nul 2>nul
if %errorlevel% neq 0 (
    if not exist "C:\Programs\ffmpeg\bin\ffmpeg.exe" (
        echo [警告] 未检测到 ffmpeg，请确保已安装 FFmpeg 并配置环境变量或安装在 C:\Programs\ffmpeg\bin\
        echo.
    )
)

where adb >nul 2>nul
if %errorlevel% equ 0 (
    echo [ADB 端口反向代理] 正在启动后台自动代理守护进程 [支持热插拔]...
    start "" /b powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0adb_reverse_daemon.ps1"
    for /f "tokens=1" %%d in ('adb devices 2^>nul ^| findstr /r /c:"[a-zA-Z0-9].*device$"') do (
        echo    - 检测到 Android 设备: %%d [后台自动完成 8554 端口反向代理]
    )
    echo [ADB 服务] 反向代理守护进程已在后台运行 [异步执行，不阻塞 MediaMTX 启动]。
    echo.
)

set "MEDIAMTX_CMD=mediamtx"
where mediamtx >nul 2>nul
if %errorlevel% neq 0 (
    if exist "mediamtx.exe" (
        set "MEDIAMTX_CMD=.\mediamtx.exe"
    ) else if exist "C:\Programs\mediamtx\mediamtx.exe" (
        set "MEDIAMTX_CMD=C:\Programs\mediamtx\mediamtx.exe"
    ) else (
        echo [错误] 未找到 mediamtx 可执行文件！
        echo 请确保已安装 MediaMTX 并将其添加到 PATH，或放入 C:\Programs\mediamtx\ 或当前目录。
        pause
        exit /b 1
    )
)

echo 正在启动 MediaMTX 服务...
echo 提示: 按 Ctrl+C 可停止服务
echo.

%MEDIAMTX_CMD% mediamtx.yml
set "EXIT_CODE=%errorlevel%"

REM 停止后台的 ADB 守护进程
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name = 'powershell.exe' or Name = 'pwsh.exe'\" | Where-Object { $_.CommandLine -like '*adb_reverse_daemon.ps1*' } | Stop-Process -Force -ErrorAction SilentlyContinue" >nul 2>&1

if %EXIT_CODE% neq 0 (
    echo.
    echo [错误] MediaMTX 异常退出，退出码: %EXIT_CODE%
    pause
)