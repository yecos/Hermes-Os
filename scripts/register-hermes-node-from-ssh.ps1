param(
    [Parameter(Mandatory=$true)][string]$DeviceId,
    [Parameter(Mandatory=$true)][string]$Name,
    [Parameter(Mandatory=$true)][string]$Url,
    [Parameter(Mandatory=$true)][string]$SshHost
)

$ErrorActionPreference = "Stop"

function Assert-PrivateUrl([string]$RawUrl) {
    $uri = [Uri]$RawUrl
    if ($uri.Scheme -notin @("http", "https")) {
        throw "Hermes Node URL must use http or https."
    }
    $hostName = $uri.DnsSafeHost.ToLowerInvariant()
    $allowed = $false
    if ($hostName -eq "localhost" -or $hostName.EndsWith(".ts.net")) {
        $allowed = $true
    }
    $ip = $null
    if ([Net.IPAddress]::TryParse($hostName, [ref]$ip)) {
        $b = $ip.GetAddressBytes()
        if ($ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork) {
            if ($b[0] -eq 10) { $allowed = $true }
            if ($b[0] -eq 192 -and $b[1] -eq 168) { $allowed = $true }
            if ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) { $allowed = $true }
            if ($b[0] -eq 100 -and $b[1] -ge 64 -and $b[1] -le 127) { $allowed = $true }
            if ($b[0] -eq 127) { $allowed = $true }
        }
    }
    if (-not $allowed) {
        throw "Refusing to register a public Hermes Node URL."
    }
}

Assert-PrivateUrl $Url

$tokenLine = & ssh -o BatchMode=yes -o ConnectTimeout=5 $SshHost "grep '^HERMES_NODE_TOKEN=' ~/.hermes-node/hermes-node.env | head -n 1"
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the Hermes Node pairing token through the authorized SSH host."
}
$token = (($tokenLine | Out-String).Trim() -replace '^HERMES_NODE_TOKEN=', '')
if ($token -notmatch '^[A-Fa-f0-9]{64}$') {
    throw "The remote Hermes Node token did not have the expected format."
}

$headers = @{ Authorization = "Bearer $token" }
$deviceUri = ($Url.TrimEnd("/") + "/v1/device")
$device = Invoke-RestMethod -Uri $deviceUri -Headers $headers -TimeoutSec 5

$registryRoot = Join-Path $env:USERPROFILE ".hermes-node"
$registryPath = Join-Path $registryRoot "devices.json"
New-Item -ItemType Directory -Force -Path $registryRoot | Out-Null

$entries = @()
if (Test-Path -LiteralPath $registryPath) {
    try {
        $existing = Get-Content -LiteralPath $registryPath -Raw | ConvertFrom-Json
        if ($existing.devices) {
            $entries = @($existing.devices | Where-Object { $_.id -ne $DeviceId })
        }
    } catch {
        throw "Existing device registry is not valid JSON: $registryPath"
    }
}

$entries += [pscustomobject]@{
    id      = $DeviceId
    name    = $Name
    url     = $Url.TrimEnd("/")
    token   = $token
    enabled = $true
}

$registryJson = @{ devices = $entries } | ConvertTo-Json -Depth 8
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($registryPath, $registryJson, $utf8NoBom)

# Restrict the registry to the current user on Windows. Do not print the token.
$principal = "{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME
& icacls.exe $registryPath /inheritance:r /grant:r ("{0}:(F)" -f $principal) | Out-Null

Write-Host "Hermes Node paired without exposing its token."
Write-Host "Device ID: $DeviceId"
Write-Host "Name: $($device.name)"
Write-Host "Platform: $($device.os)/$($device.arch)"
Write-Host "Version: $($device.version)"
Write-Host "URL: $Url"
Write-Host "Registry: $registryPath"
