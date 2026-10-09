# Read-only health check: no model calls and no new Kanban tasks.
$ErrorActionPreference='Stop'
$limits=@{default=12;companydirector=12;companyproduct=8;companyarchitect=8;companyfrontend=12;companybackend=12;companyintegrations=12}
$models=@{default='gpt-5.6-sol';companydirector='gpt-5.6-sol';companyproduct='gpt-6-luna';companyarchitect='gpt-6-luna';companyfrontend='gpt-5.6-luna';companybackend='gpt-5.6-luna';companyintegrations='gpt-5.6-luna'}
$fail=@()
foreach($p in $limits.Keys){
 $prefix=@()
 if($p -ne 'default'){$prefix=@('--profile',$p)}
 $turns=& hermes @prefix config get agent.max_turns
 $children=& hermes @prefix config get delegation.max_concurrent_children
 $profile=(& hermes profile show $p | Out-String)
 if(([string]$turns).Trim() -ne [string]$limits[$p]){$fail+= "$p max_turns"}
 if(([string]$children).Trim() -ne '1'){$fail+= "$p max_children"}
 if(-not $profile.Contains($models[$p])){$fail+= "$p model"}
 Write-Output ("ROLE {0}: max_turns={1}, model={2}, max_children={3}" -f $p,$turns,$models[$p],$children)
}
foreach($kv in @(@('kanban.max_in_progress','1'),@('kanban.max_in_progress_per_profile','1'),@('kanban.auto_decompose','false'),@('kanban.failure_limit','1'))){
 $value=& hermes config get $kv[0]
 if(([string]$value).Trim() -ne $kv[1]){$fail+= $kv[0]}
}
$gateway=(& hermes gateway status | Out-String)
if($gateway -notmatch 'Gateway process running'){$fail+='gateway not running'}
Write-Output 'GATEWAY: existing default gateway checked'
$board=(& hermes kanban boards list | Out-String)
if($board -notmatch 'hermes-ai-company'){$fail+='ECO Kanban board missing'}
$stats=(& hermes kanban --board hermes-ai-company stats | Out-String)
Write-Output ('KANBAN_BOARD: '+ $(if($stats -match 'running'){'available'}else{'not responding'}))
$dry=(& hermes kanban --board hermes-ai-company dispatch --dry-run --max 1 --json | Out-String)
try {$obj=$dry | ConvertFrom-Json;Write-Output ('KANBAN_DRY_RUN: '+$(if(@($obj.spawned).Count -eq 0){'no-spawn'}else{'would spawn '+@($obj.spawned).Count}))}catch{$fail+='kanban dry-run JSON'}
if($fail.Count){
 Write-Output ('ECO_HEALTH_FAIL: '+($fail -join ', '))
 exit 2
}
Write-Output 'ECO_HEALTH_PASS (no Codex requests made)'
