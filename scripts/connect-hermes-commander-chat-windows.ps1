<#
.SYNOPSIS
Connect the EXISTING full Hermes Commander MCP server to ChatGPT through
OpenAI Secure MCP Tunnel. Does not reinstall Hermes or modify its policy.

Prerequisites:
 - Hermes Commander already installed in %LOCALAPPDATA%\HermesCommander
 - Control-plane tunnel_id (Platform Tunnels) and CONTROL_PLANE_API_KEY
 - Official OpenAI tunnel-client installed locally
 - Custom MCP apps enabled in the target ChatGPT workspace/account

Run: powershell -File .\scripts\connect-hermes-commander-chat-windows.ps1 -TunnelId tunnel_... -Run

This connector does not and cannot override ChatGPT plan, surface or app policies.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$TunnelId,

    [string]$Profile = "hermes-commander-full-chat",

    [string]$TunnelClient = "tunnel-client",

    [switch]$Run,

    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

if ($TunnelId -notmatch '^tunnel_[A-Za-z0-9_-]+$') {
    throw "TunnelId must be a Platform tunnel identifier beginning with tunnel_."
}
if ($Profile -notmatch '^[a-zA-Z0-9_-]+$') {
    throw "Profile must contain only letters, digits, underscores, or hyphens."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $repoRoot "plugins\hermes-commander\scripts\launch-windows.ps1"
$binary = Join-Path $env:LOCALAPPDATA "HermesCommander\hermes-node.exe"
$policy = Join-Path $env:USERPROFILE ".hermes-node\hermes-node.env"

# Call the ALREADY EXISTING launcher, which loads the user's Hermes policy
# and runs exactly `hermes-node.exe mcp`, preserving the full tool surface.
# A standalone .mcp.json local plugin is Desktop-only on web/mobile.
$mcpCommand = 'powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $launcher + '"'

if ($DryRun) {
    Write-Output "Hermes Commander full MCP (preview only)"
    Write-Output "Launcher: $launcher"
    Write-Output "Policy: $policy (not modified)"
    Write-Output "Binary: $binary (not modified)"
    Write-Output "MCP command: $mcpCommand"
    Write-Output "Tunnel profile: $Profile"
    Write-Output "Tunnel ID: $TunnelId"
    Write-Output "No network request was made."
    exit 0
}

if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "Hermes launcher missing: $launcher"
}
if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) {
    throw "Hermes Commander binary missing: $binary. Keep the existing installation."
}
if (-not (Test-Path -LiteralPath $policy -PathType Leaf)) {
    throw "Hermes policy missing: $policy. Refusing to run with implicit defaults."
}
if ([string]::IsNullOrWhiteSpace($env:CONTROL_PLANE_API_KEY)) {
    throw "CONTROL_PLANE_API_KEY must be supplied privately to the local process. Never commit or paste the key into a chat."
}
$client = Get-Command $TunnelClient -ErrorAction SilentlyContinue
if (-not $client) {
    throw "Official OpenAI tunnel-client not found: $TunnelClient. Install it from Platform tunnel settings."
}

Write-Host "Connecting full Hermes Commander MCP through Secure MCP Tunnel."
Write-Host "This preserves your existing local Hermes Node policy and exposes its full tool list."
Write-Host "Tunnel profile: $Profile"

# The init step may be rerun to refresh the local tunnel-client profile.
& $client.Source init --sample sample_mcp_stdio_local --profile $Profile --tunnel-id $TunnelId --mcp-command $mcpCommand
if ($LASTEXITCODE -ne 0) { throw "tunnel-client init failed: $LASTEXITCODE" }
& $client.Source doctor --profile $Profile --explain
if ($LASTEXITCODE -ne 0) { throw "tunnel-client doctor failed: $LASTEXITCODE" }

if ($Run) {
    Write-Host "Secure tunnel starting. Keep it running while using ChatGPT."
    & $client.Source run --profile $Profile
    if ($LASTEXITCODE -ne 0) { throw "tunnel-client run failed: $LASTEXITCODE" }
} else {
    Write-Host "Configuration checked. Run with -Run to keep the tunnel connected."
}
