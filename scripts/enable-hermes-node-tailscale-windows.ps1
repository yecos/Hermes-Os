param(
    [int]$Port = 9090,
    [string]$TaskName = "Hermes Node Tailscale"
)

$ErrorActionPreference = "Stop"

function Set-EnvLine {
    param([string]$Path, [string]$Name, [string]$Value)
    $lines = @()
    if (Test-Path -LiteralPath $Path) {
        $lines = @(Get-Content -LiteralPath $Path)
    }
    $found = $false
    $out = foreach ($line in $lines) {
        if ($line -match ("^" + [regex]::Escape($Name) + "=")) {
            $found = $true
            "$Name=$Value"
        } else {
            $line
        }
    }
    if (-not $found) { $out += "$Name=$Value" }
    $out | Set-Content -LiteralPath $Path -Encoding utf8
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$installRoot = Join-Path $env:LOCALAPPDATA "HermesCommander"
$configRoot = Join-Path $env:USERPROFILE ".hermes-node"
$configFile = Join-Path $configRoot "hermes-node.env"
$binary = Join-Path $installRoot "hermes-node.exe"
$sourceRunner = Join-Path $scriptDir "run-hermes-node-server-windows.ps1"
$installedRunner = Join-Path $installRoot "serve-windows.ps1"

if (-not (Test-Path -LiteralPath $binary)) {
    throw "Hermes Commander is not installed. Run install-hermes-commander-chatgpt.ps1 first."
}
if (-not (Test-Path -LiteralPath $configFile)) {
    throw "Hermes Node config does not exist at $configFile"
}

$tailscale = Get-Command tailscale.exe -ErrorAction SilentlyContinue
if (-not $tailscale) {
    $candidate = "C:\Program Files\Tailscale\tailscale.exe"
    if (Test-Path -LiteralPath $candidate) {
        $tailscale = Get-Item -LiteralPath $candidate
    }
}
if (-not $tailscale) {
    throw "Tailscale CLI was not found."
}

$tsExe = if ($tailscale.Source) { $tailscale.Source } else { $tailscale.FullName }
$tailscaleIP = (& $tsExe ip -4 | Select-Object -First 1).Trim()
if (-not $tailscaleIP -or $tailscaleIP -notmatch '^100\.') {
    throw "Could not determine a Tailscale IPv4 address."
}

New-Item -ItemType Directory -Force -Path $installRoot, $configRoot | Out-Null
Copy-Item -LiteralPath $sourceRunner -Destination $installedRunner -Force

Set-EnvLine -Path $configFile -Name "HERMES_NODE_LISTEN" -Value ("{0}:{1}" -f $tailscaleIP, $Port)
Set-EnvLine -Path $configFile -Name "HERMES_NODE_ALLOWED_REMOTE_CIDRS" -Value "100.64.0.0/10"

$taskArgs = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $installedRunner
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $taskArgs
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$principal = New-ScheduledTaskPrincipal -UserId ("{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)

$task = New-ScheduledTask -Action $action -Trigger $trigger -Principal $principal -Settings $settings
Register-ScheduledTask -TaskName $TaskName -InputObject $task -Force | Out-Null

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator
)
$firewallName = "Hermes Node Tailscale $Port"
if ($isAdmin) {
    Get-NetFirewallRule -DisplayName $firewallName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $firewallName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -LocalAddress $tailscaleIP -RemoteAddress "100.64.0.0/10" -Profile Any | Out-Null
} else {
    Write-Warning "Not running elevated; no firewall rule was created. Tailscale may still allow the connection depending on Windows Firewall policy."
}

Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq "hermes-node.exe" -and $_.CommandLine -match "\sserve(\s|$)"
} | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
}

Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 2

$healthURL = "http://{0}:{1}/v1/health" -f $tailscaleIP, $Port
try {
    $health = Invoke-RestMethod -Uri $healthURL -TimeoutSec 3
} catch {
    throw "Hermes Node task was registered but health check failed at $healthURL : $($_.Exception.Message)"
}

Write-Host ""
Write-Host "Hermes Node Tailscale server enabled."
Write-Host "Task: $TaskName"
Write-Host ("Listen: {0}:{1}" -f $tailscaleIP, $Port)
Write-Host "Allowed remote CIDR: 100.64.0.0/10"
Write-Host "Health: $($health.ok)"
Write-Host "The bearer token remains stored only in $configFile"
