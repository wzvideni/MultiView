# MediaMTX ADB Reverse Auto Daemon
# 定期检查所有已连接的 Android 设备并自动绑定 8554 端口反向代理
$ErrorActionPreference = 'SilentlyContinue'

while ($true) {
    try {
        $lines = adb devices 2>$null
        foreach ($line in $lines) {
            if ($line -match '^([^\s]+)\s+device$') {
                $dev = $matches[1]
                # 直接反向代理，避免查询 --list 在部分设备上产生永久阻塞
                adb -s $dev reverse tcp:8554 tcp:8554 2>$null | Out-Null
            }
        }
    } catch {
        # 忽略单次轮询异常
    }
    Start-Sleep -Seconds 3
}
