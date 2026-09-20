@echo off
title MediaMTX RTSP Server
echo ====================================================================
echo  MediaMTX RTSP 1~32 路多路监控模拟推流服务
echo  局域网 RTSP 基础地址: rtsp://192.168.31.49:8554/live/
echo    - 辅码流(子码流 360P 15fps): rtsp://192.168.31.49:8554/live/sub01 ~ sub32
echo    - 主码流(高清 1080P 25fps): rtsp://192.168.31.49:8554/live/main01 ~ main32
echo ====================================================================
C:\Programs\mediamtx\mediamtx.exe C:\Programs\mediamtx\mediamtx.yml
pause
