# Hermes AI Company ECO: single-instance Windows supervisor.
# Runs deterministic SQLite/Kanban controller only. The controller itself is
# the sole authority to spawn a Codex worker, after owner approval and budget.
$ErrorActionPreference='Continue'
$root=Join-Path $env:LOCALAPPDATA 'hermes\company-control'
New-Item -Path $root -ItemType Directory -Force | Out-Null
$log=Join-Path $root 'supervisor.log'
$python=Join-Path $env:LOCALAPPDATA 'hermes\hermes-agent\venv\Scripts\python.exe'
$env:PATH=((Split-Path $python -Parent)+';C:\Program Files\Git\cmd;'+$env:PATH)
$controller='C:\Dev\Hermes-AI-Company-ECO\ai-company\company_control.py'
$mutex=New-Object System.Threading.Mutex($false,'Local\HermesAICompanyEcoSupervisor')
$locked=$false
try {
    try { $locked=$mutex.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $locked=$true }
    if(-not $locked){exit 0}
    while($true){
        try {
            if((Test-Path $log) -and (Get-Item $log).Length -gt 1048576){
                Move-Item -Path $log -Destination ($log+'.old') -Force
            }
            if(-not(Test-Path $controller) -or -not(Test-Path $python)){
                ('['+(Get-Date -Format o)+'] OFFLINE executable_missing') | Add-Content $log
            } else {
                $out=& $python $controller tick 2>&1
                $code=$LASTEXITCODE
                # Do not save payloads, prompts or secrets from controller errors.
                $event=if($code -eq 0){'CHECK_OK'}else{'CHECK_FAIL code='+$code}
                ('['+(Get-Date -Format o)+'] '+$event) | Add-Content $log
            }
        } catch {
            ('['+(Get-Date -Format o)+'] EXCEPTION type='+$_.Exception.GetType().Name) | Add-Content $log
        }
        Start-Sleep -Seconds 45
    }
} finally {
    if($locked){$mutex.ReleaseMutex()}
    $mutex.Dispose()
}
