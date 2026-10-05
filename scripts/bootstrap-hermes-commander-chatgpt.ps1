param(
    [switch]$FullControl = $true
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$Name, [string]$Help) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Help"
    }
}

Write-Host "=== HERMES COMMANDER / CHATGPT DESKTOP BOOTSTRAP ==="

Require-Command "git" "Install Git for Windows, then run this bootstrap again."
Require-Command "npm" "Install Node.js/npm, then run this bootstrap again."

$repoRoot = Join-Path $env:USERPROFILE "Hermes-Os"

if (-not (Test-Path -LiteralPath (Join-Path $repoRoot ".git"))) {
    Write-Host "Cloning Hermes-Os to $repoRoot ..."
    & git clone "https://github.com/yecos/Hermes-Os.git" $repoRoot
    if ($LASTEXITCODE -ne 0) {
        throw "git clone failed."
    }
}
else {
    Write-Host "Hermes-Os already exists at $repoRoot"
}

Write-Host "Updating main..."
& git -C $repoRoot fetch --all --prune
if ($LASTEXITCODE -ne 0) { throw "git fetch failed." }

& git -C $repoRoot checkout main
if ($LASTEXITCODE -ne 0) { throw "git checkout main failed." }

& git -C $repoRoot pull --ff-only
if ($LASTEXITCODE -ne 0) { throw "git pull failed." }

Write-Host ""
Write-Host "Current Hermes-Os commit:"
& git -C $repoRoot log -1 --oneline

if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
    Write-Host ""
    Write-Host "Codex CLI not found. Installing official @openai/codex@latest..."
    & npm install -g "@openai/codex@latest"
    if ($LASTEXITCODE -ne 0) {
        throw "Codex CLI installation failed."
    }

    $npmGlobalBin = Join-Path $env:APPDATA "npm"
    if (Test-Path -LiteralPath $npmGlobalBin) {
        $env:PATH = "$npmGlobalBin;$env:PATH"
    }
}

if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
    throw "Codex was installed but PowerShell still cannot find it. Close and reopen PowerShell, then run this bootstrap again."
}

Write-Host ""
Write-Host "Codex:"
& codex --version

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    Write-Host ""
    Write-Host "Go was not found."
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Host "Installing Go using winget..."
        & winget install --id GoLang.Go -e --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -ne 0) {
            throw "Go installation through winget failed."
        }

        $goBin = "C:\Program Files\Go\bin"
        if (Test-Path -LiteralPath $goBin) {
            $env:PATH = "$goBin;$env:PATH"
        }
    }
    else {
        throw "Go 1.23+ is required and winget is not available. Install Go, reopen PowerShell, and run this bootstrap again."
    }
}

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw "Go was installed but is not visible on PATH. Close and reopen PowerShell, then run this bootstrap again."
}

Write-Host ""
Write-Host "Go:"
& go version

$installer = Join-Path $repoRoot "scripts\install-hermes-commander-chatgpt.ps1"
if (-not (Test-Path -LiteralPath $installer)) {
    throw "Hermes Commander installer was not found at $installer"
}

Write-Host ""
Write-Host "Installing Hermes Commander..."
$installArgs = @(
    "-NoLogo",
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $installer
)
if ($FullControl) {
    $installArgs += "-FullControl"
}

& powershell.exe @installArgs
if ($LASTEXITCODE -ne 0) {
    throw "Hermes Commander installer failed with exit code $LASTEXITCODE"
}

Write-Host ""
Write-Host "=== BOOTSTRAP COMPLETE ==="
Write-Host "Repository: $repoRoot"
Write-Host ""
Write-Host "Now fully close and reopen ChatGPT Desktop."
Write-Host "Then open Plugins -> marketplace 'Hermes OS' -> install/enable 'Hermes Commander'."
