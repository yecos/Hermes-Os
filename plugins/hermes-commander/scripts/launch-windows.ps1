$ErrorActionPreference = "Stop"

$installRoot = Join-Path $env:LOCALAPPDATA "HermesCommander"
$binary = Join-Path $installRoot "hermes-node.exe"
$configFile = Join-Path $env:USERPROFILE ".hermes-node\hermes-node.env"

if (-not (Test-Path -LiteralPath $binary)) {
    [Console]::Error.WriteLine("Hermes Commander is not installed. Run scripts\install-hermes-commander-chatgpt.ps1 from the Hermes-Os repository.")
    exit 2
}

if (Test-Path -LiteralPath $configFile) {
    foreach ($line in Get-Content -LiteralPath $configFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $parts = $trimmed.Split("=", 2)
        if ($parts.Count -ne 2) {
            continue
        }

        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], "Process")
    }
}

& $binary mcp
exit $LASTEXITCODE
