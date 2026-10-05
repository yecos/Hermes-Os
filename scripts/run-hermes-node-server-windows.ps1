$ErrorActionPreference = "Stop"

$installRoot = Join-Path $env:LOCALAPPDATA "HermesCommander"
$binary = Join-Path $installRoot "hermes-node.exe"
$configFile = Join-Path $env:USERPROFILE ".hermes-node\hermes-node.env"
$logFile = Join-Path $installRoot "server.log"

if (-not (Test-Path -LiteralPath $binary)) {
    throw "Hermes Node binary not found at $binary"
}
if (-not (Test-Path -LiteralPath $configFile)) {
    throw "Hermes Node config not found at $configFile"
}

foreach ($line in Get-Content -LiteralPath $configFile) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
    $parts = $trimmed.Split("=", 2)
    if ($parts.Count -eq 2) {
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], "Process")
    }
}

$stamp = Get-Date -Format o
Add-Content -LiteralPath $logFile -Value "[$stamp] starting Hermes Node serve"
$cmdLine = '""{0}" serve >> "{1}" 2>&1"' -f $binary, $logFile
& cmd.exe /d /s /c $cmdLine
exit $LASTEXITCODE
