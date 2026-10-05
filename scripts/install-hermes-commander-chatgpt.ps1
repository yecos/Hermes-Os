param(
    [switch]$FullControl
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$Name, [string]$Help) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Help"
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$nodeRoot = Join-Path $repoRoot "hermes-node"
$installRoot = Join-Path $env:LOCALAPPDATA "HermesCommander"
$configRoot = Join-Path $env:USERPROFILE ".hermes-node"
$configFile = Join-Path $configRoot "hermes-node.env"
$binary = Join-Path $installRoot "hermes-node.exe"

if (-not (Test-Path -LiteralPath (Join-Path $nodeRoot "go.mod"))) {
    throw "Hermes Node source was not found at $nodeRoot"
}

Require-Command "go" "Install Go 1.23 or newer, then run this installer again."
Require-Command "codex" "Install/update ChatGPT Desktop/Codex CLI so the codex command is available."

New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
New-Item -ItemType Directory -Force -Path $configRoot | Out-Null

Write-Host "Building Hermes Node..."
Push-Location $nodeRoot
try {
    & go test ./...
    if ($LASTEXITCODE -ne 0) {
        throw "Hermes Node tests failed."
    }

    & go build -o $binary ./cmd/hermes-node
    if ($LASTEXITCODE -ne 0) {
        throw "Hermes Node build failed."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $configFile)) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $token = [Convert]::ToHexString($bytes).ToLowerInvariant()

    $mode = if ($FullControl) { "admin" } else { "restricted" }
    $allowed = "git,docker,hermes,adb,ping,ipconfig,where,tasklist,taskkill,go,node,npm,npx,pnpm,python,py,curl,tailscale"

    @(
        "# Hermes Commander local policy"
        "HERMES_NODE_NAME=$env:COMPUTERNAME"
        "HERMES_NODE_LISTEN=127.0.0.1:9090"
        "HERMES_NODE_TOKEN=$token"
        "HERMES_NODE_ALLOWED_DIRS=$env:USERPROFILE"
        "HERMES_NODE_EXEC_MODE=$mode"
        "HERMES_NODE_ALLOWED_COMMANDS=$allowed"
        "HERMES_NODE_COMMAND_TIMEOUT=120s"
        "HERMES_NODE_MAX_READ_BYTES=4194304"
        "HERMES_NODE_MAX_WRITE_BYTES=4194304"
    ) | Set-Content -LiteralPath $configFile -Encoding utf8

    Write-Host "Created Hermes Node policy at $configFile"
}
elseif ($FullControl) {
    $lines = Get-Content -LiteralPath $configFile
    $found = $false
    $updated = foreach ($line in $lines) {
        if ($line -match '^HERMES_NODE_EXEC_MODE=') {
            $found = $true
            "HERMES_NODE_EXEC_MODE=admin"
        }
        else {
            $line
        }
    }
    if (-not $found) {
        $updated += "HERMES_NODE_EXEC_MODE=admin"
    }
    $updated | Set-Content -LiteralPath $configFile -Encoding utf8
    Write-Host "Enabled full command control in $configFile"
}

Write-Host "Testing Hermes Node binary..."
& $binary status
if ($LASTEXITCODE -ne 0) {
    throw "Hermes Node status test failed."
}

Write-Host "Registering Hermes OS local plugin marketplace..."
& codex plugin marketplace add $repoRoot --json
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Marketplace add returned an error. It may already be registered; checking available marketplaces."
    & codex plugin marketplace list --json
}

Write-Host "Installing Hermes Commander plugin..."
& codex plugin add "hermes-commander@hermes-os" --json
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Plugin add returned an error. Checking installed plugins."
    & codex plugin list --json
}

Write-Host ""
Write-Host "Hermes Commander installation completed."
Write-Host "Binary: $binary"
Write-Host "Policy: $configFile"
Write-Host "Mode: $(if ($FullControl) { 'admin/full control' } else { 'restricted' })"
Write-Host ""
Write-Host "Restart ChatGPT Desktop, open Plugins, enable Hermes Commander, and start a new chat."
