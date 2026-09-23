# MediaMTX ADB Reverse Auto Daemon
# 自动检测已连接的 Android 设备并绑定 8554 端口反向代理
# 具备：单例去重、命令超时防挂死、MediaMTX 联动自退出机制
$ErrorActionPreference = 'SilentlyContinue'

# 1. 单例保护：清理可能存在的旧实例，避免多实例竞争 ADB
$currentPid = $PID
Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' or Name = 'pwsh.exe'" | Where-Object {
    $_.ProcessId -ne $currentPid -and $_.CommandLine -like "*adb_reverse_daemon.ps1*"
} | ForEach-Object {
    try { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } catch {}
}

# 2. 超时执行 ADB 命令辅助函数 (防止坏死连接永久阻塞)
function Invoke-AdbCommand {
    param(
        [string]$Arguments,
        [int]$TimeoutMs = 2000
    )
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = 'adb'
        $psi.Arguments = $Arguments
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        if ($p.WaitForExit($TimeoutMs)) {
            $stdout = $p.StandardOutput.ReadToEnd()
            return @{ Success = ($p.ExitCode -eq 0); Output = $stdout }
        } else {
            try { $p.Kill() } catch {}
            return @{ Success = $false; Output = "" }
        }
    } catch {
        return @{ Success = $false; Output = "" }
    }
}

# 记录已成功反向代理的设备及时间
$configuredDevices = @{}
$mediamtxMissCount = 0

# 3. 守护轮询
while ($true) {
    try {
        # 监测 MediaMTX 进程；若 MediaMTX 未运行超过 30 秒（例如 MediaMTX 已关闭），守护进程自动退出
        $mediamtx = Get-Process -Name "mediamtx" -ErrorAction SilentlyContinue
        if (-not $mediamtx) {
            $mediamtxMissCount++
            # 宽限期：给 mediamtx 启动留出充足时间（约 30 秒）
            if ($mediamtxMissCount -gt 10) {
                break
            }
        } else {
            $mediamtxMissCount = 0
        }

        # 查询当前连接的设备列表 (带 2000ms 超时保护)
        $devicesResult = Invoke-AdbCommand -Arguments "devices" -TimeoutMs 2000
        $currentOnlineDevices = @()

        if ($devicesResult.Success -and $devicesResult.Output) {
            $lines = $devicesResult.Output -split "`r?`n"
            foreach ($line in $lines) {
                if ($line -match '^([^\s]+)\s+device$') {
                    $dev = $matches[1]
                    $currentOnlineDevices += $dev

                    # 仅针对新接入或尚未代理成功的设备执行 reverse
                    if (-not $configuredDevices.ContainsKey($dev)) {
                        $revResult = Invoke-AdbCommand -Arguments "-s $dev reverse tcp:8554 tcp:8554" -TimeoutMs 2000
                        if ($revResult.Success) {
                            $configuredDevices[$dev] = [DateTime]::Now
                        }
                    }
                }
            }
        }

        # 清理已断开的设备记录（支持热插拔重新连接后再次触发代理）
        $keys = @($configuredDevices.Keys)
        foreach ($k in $keys) {
            if ($currentOnlineDevices -notcontains $k) {
                $configuredDevices.Remove($k)
            }
        }
    } catch {
        # 忽略单次轮询异常
    }

    Start-Sleep -Seconds 3
}
