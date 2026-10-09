# Hermes AI Company: Codex-only ECO settings for Hermes 0.20.2 on Windows.
# No inference calls, credentials, extra gateways or automated task creation.
param([switch]$Apply, [switch]$RestartGateway)
$ErrorActionPreference='Stop'
$root=Join-Path $env:LOCALAPPDATA 'hermes'
$profiles=@('default','companydirector','companyproduct','companyarchitect','companyfrontend','companybackend','companyintegrations')
$turnLimits=@{default=12;companydirector=12;companyproduct=8;companyarchitect=8;companyfrontend=12;companybackend=12;companyintegrations=12}
$entry=@()
foreach($profile in $profiles){
 $config=if($profile -eq 'default'){Join-Path $root 'config.yaml'}else{Join-Path $root ('profiles\'+$profile+'\config.yaml')}
 if(-not(Test-Path $config)){throw "Missing Hermes profile config: $profile"}
 $entry += [PSCustomObject]@{profile=$profile;config=$config}
}
if(-not $Apply){
 Write-Output 'DRY_RUN: -Apply writes config after backing up seven profiles'
 $entry | ForEach-Object {Write-Output ($_.profile+' => '+$_.config)}
 exit 0
}
$backup=Join-Path $root ('eco-backup-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
foreach($item in $entry){Copy-Item -Path $item.config -Destination (Join-Path $backup ($item.profile+'.yaml')) -Force}
function SetHermes([string]$profile,[string]$key,[string]$value){
 $a=@();if($profile -ne 'default'){$a=@('--profile',$profile)}
 & hermes @a config set $key $value | Out-Null
 if($LASTEXITCODE -ne 0){throw "Could not set $key for $profile"}
}
try {
 foreach($item in $entry){
  $p=$item.profile
  SetHermes $p 'agent.max_turns' ([string]$turnLimits[$p])
  SetHermes $p 'delegation.max_iterations' '8'
  SetHermes $p 'delegation.max_concurrent_children' '1'
  SetHermes $p 'agent.reasoning_effort' ($(if($p -in @('companyproduct','companyarchitect')){'medium'}else{'low'}))
 }
 foreach($setting in @(
  @('agent.max_verify_nudges','1'),
  @('agent.api_max_retries','1'),
  @('delegation.max_summary_chars','4000'),
  @('delegation.child_timeout_seconds','600'),
  @('kanban.max_in_progress','1'),
  @('kanban.max_in_progress_per_profile','1'),
  @('kanban.auto_decompose','false'),
  @('kanban.failure_limit','1')
  )){SetHermes 'default' $setting[0] $setting[1]}
 foreach($p in @('default','companydirector')){
  SetHermes $p 'compression.codex_gpt55_autoraise' 'false'
  SetHermes $p 'compression.threshold' '0.35'
  SetHermes $p 'compression.proactive_prune_tokens' '48000'
 }
 Write-Output ('BACKUP='+$backup)
 Write-Output 'CONFIG_APPLIED; now validate with hermes config get and optionally restart existing gateway'
 if($RestartGateway){
  & hermes gateway restart
  if($LASTEXITCODE -ne 0){throw 'Gateway restart failed'}
 }
} catch {
 Write-Output ('CONFIG_APPLY_FAILED; backup='+$backup)
 throw
}
