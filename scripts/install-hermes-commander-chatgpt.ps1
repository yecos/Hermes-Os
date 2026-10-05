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

if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
    Require-Command "npm" "Install Node.js/npm, then run this installer again."
    Write-Host "Codex CLI was not found. Installing the official @openai/codex package..."
    & npm install -g "@openai/codex@latest"
    if ($LASTEXITCODE -ne 0) {
        throw "Codex CLI installation failed."
    }

    $npmGlobalBin = Join-Path $env:APPDATA "npm"
    if (Test-Path -LiteralPath $npmGlobalBin) {
        $env:PATH = "$npmGlobalBin;$env:PATH"
    }

    if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
        throw "Codex was installed but is not visible on PATH. Close and reopen PowerShell, then run the installer again."
    }
}

Write-Host "Codex CLI: $(& codex --version)"

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
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    $token = -join ($bytes | ForEach-Object { $_.ToString("x2") })

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

Write-Host ""
Write-Host "Hermes Commander local marketplace is registered."
Write-Host "Local plugins are installed/enabled from the ChatGPT Desktop Plugins Directory."

Write-Host ""
Write-Host "Hermes Commander preparation completed."
Write-Host "Binary: $binary"
Write-Host "Policy: $configFile"
Write-Host "Mode: $(if ($FullControl) { 'admin/full control' } else { 'restricted' })"
Write-Host ""
Write-Host "NEXT:"
Write-Host "1. Fully close and reopen ChatGPT Desktop."
Write-Host "2. Open Plugins."
Write-Host "3. Select the local marketplace: Hermes OS."
Write-Host "4. Install/enable Hermes Commander."
Write-Host "5. Start a new chat and ask: Use Hermes Commander and show system status."
