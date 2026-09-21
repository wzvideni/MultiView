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
    echo [ADB 端口反向代理] 正在检查并配置已连接的 Android 设备...
    for /f "tokens=1" %%d in ('adb devices ^| findstr /r /c:"[a-zA-Z0-9].*device$"') do (
        adb -s %%d reverse tcp:8554 tcp:8554 >nul 2>&1
        echo    - 设备 %%d: 反向代理就绪 [手机 127.0.0.1:8554 -^> 电脑 8554]
    )
    start "" /b powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0adb_reverse_daemon.ps1"
    echo [ADB 服务] 后台自动守护进程已就绪。
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

if %errorlevel% neq 0 (
    echo.
    echo [错误] MediaMTX 异常退出，退出码: %errorlevel%
    pause
)