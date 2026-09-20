@echo off
title MediaMTX RTSP Server
cd /d "%~dp0"

for /f "tokens=4" %%a in ('route print ^| findstr 0.0.0.0.*0.0.0.0') do (
    set LOCAL_IP=%%a
)
if "%LOCAL_IP%"=="" set LOCAL_IP=127.0.0.1

echo ==============================================================================
echo                 MediaMTX RTSP 1-32 路多路监控模拟推流服务
echo ==============================================================================
echo  本机局域网 IP: %LOCAL_IP%
echo  RTSP 服务端口: 8554 (TCP/UDP)
echo.
echo  [流地址列表]:
echo    - 辅码流 [流畅预览 640x360@15fps, 1秒关键帧秒开]:
echo      rtsp://%LOCAL_IP%:8554/live/sub01  ~  rtsp://%LOCAL_IP%:8554/live/sub32
echo.
echo    - 主码流 [高清全屏 1920x1080@25fps, 1秒关键帧]:
echo      rtsp://%LOCAL_IP%:8554/live/main01 ~  rtsp://%LOCAL_IP%:8554/live/main32
echo.
echo  [工作机制]:
echo    - 按需拉流 On-Demand: 客户端连接时自动触发推流，断开3秒后自动关闭以节省资源
echo ==============================================================================
echo.

if not exist "C:\Programs\ffmpeg\bin\ffmpeg.exe" (
    echo [警告] 未检测到 C:\Programs\ffmpeg\bin\ffmpeg.exe
    echo 请确认 FFmpeg 安装路径是否正确，否则模拟视频流将无法自动生成。
    echo.
)

echo 正在启动 MediaMTX 服务...
echo 提示: 按 Ctrl+C 可停止服务
echo.

mediamtx.exe mediamtx.yml

if %errorlevel% neq 0 (
    echo.
    echo [错误] MediaMTX 异常退出，错误代码: %errorlevel%
    pause
)
