#requires -Version 7.0
[CmdletBinding()]
param([ValidateSet('All','Server','Client')][string]$Target='All')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $repo '.local/fullpack-manual-1.6.0'
$javaRoot='C:/Program Files/Java/jdk-21/bin'
function Test-Instance([string]$Directory) {
    $record=Join-Path $Directory 'process.json'
    if (!(Test-Path -LiteralPath $record)) { return $false }
    $state=Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    $process=Get-Process -Id $state.pid -ErrorAction SilentlyContinue
    return $process -and $process.ProcessName -in @('java','javaw') -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$state.started).ToUniversalTime().Ticks
}
function Read-SharedLog([string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) { return '' }
    $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
    $reader=[IO.StreamReader]::new($stream)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
$server=Join-Path $runtime 'server'
$client=Join-Path $runtime 'client'
if ($Target -in @('All','Server') -and !(Test-Instance $server)) {
    $process=Start-Process -FilePath "$javaRoot/java.exe" -ArgumentList ('"@'+$server+'/launch-server.args"') -WorkingDirectory $server -WindowStyle Hidden -RedirectStandardOutput "$server/console.log" -RedirectStandardError "$server/console-error.log" -PassThru
    @{pid=$process.Id;started=$process.StartTime.ToUniversalTime().ToString('o');directory=$server;port=25621} | ConvertTo-Json | Set-Content -LiteralPath "$server/process.json"
    Write-Output "Started full-pack server PID $($process.Id)."
}
if ($Target -eq 'All') {
    $deadline=[DateTime]::UtcNow.AddSeconds(55)
    while ((Read-SharedLog "$server/console.log") -notmatch 'Done \(') {
        if (!(Test-Instance $server)) { throw 'Server exited. Inspect its console.log and console-error.log.' }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Server still loading; rerun this command shortly. It will reuse the running server.' }
        Start-Sleep -Milliseconds 500
    }
}
if ($Target -in @('All','Client') -and !(Test-Instance $client)) {
    $process=Start-Process -FilePath "$javaRoot/javaw.exe" -ArgumentList ('"@'+$client+'/launch-client.args"') -WorkingDirectory $client -RedirectStandardOutput "$client/console.log" -RedirectStandardError "$client/console-error.log" -PassThru
    @{pid=$process.Id;started=$process.StartTime.ToUniversalTime().ToString('o');directory=$client;server='127.0.0.1:25621'} | ConvertTo-Json | Set-Content -LiteralPath "$client/process.json"
    Write-Output "Started full-pack client PID $($process.Id)."
}
Write-Output 'Manual arena server: 127.0.0.1:25621. Hub: 0.5 81 6.5.'
