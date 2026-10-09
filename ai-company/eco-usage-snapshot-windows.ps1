# Read Hermes-local usage aggregates; does not invoke inference models.
$ErrorActionPreference='Stop'
$dir=Join-Path $env:LOCALAPPDATA 'hermes\eco-metrics'
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$stamp=(Get-Date -Format 'yyyy-MM-dd')
$path=Join-Path $dir ($stamp+'.txt')
$stats=(& hermes insights --days 1 2>&1 | Out-String)
if($LASTEXITCODE -ne 0){throw 'Local Hermes insights failed'}
# These are local historical statistics, NOT subscription credits or invoice totals.
$body=('# Hermes local usage metrics - '+(Get-Date -Format o)+[Environment]::NewLine+$stats)
[IO.File]::WriteAllText($path,$body,(New-Object System.Text.UTF8Encoding($false)))
Get-ChildItem $dir -Filter '*.txt' -File | Where-Object {$_.LastWriteTime -lt (Get-Date).AddDays(-45)} | Remove-Item -Force
Write-Output ('ECO_USAGE_SNAPSHOT_CREATED='+$path)
