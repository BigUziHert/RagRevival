#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('All','Server','ClientOne','ClientTwo')][string]$Target = 'All',
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $repo '.local'
$settings = Get-Content -LiteralPath "$runtime/runtime.json" -Raw | ConvertFrom-Json
$launcher = Join-Path $runtime 'launcher'
$version = "neoforge-$($settings.neoForgeVersion)"
$minecraft = Get-Content -LiteralPath "$launcher/versions/1.21.1/1.21.1.json" -Raw | ConvertFrom-Json
$neoforge = Get-Content -LiteralPath "$launcher/versions/$version/$version.json" -Raw | ConvertFrom-Json
$processes = @{}
if (Test-Path -LiteralPath "$runtime/processes.json") {
    $processes = Get-Content -LiteralPath "$runtime/processes.json" -Raw | ConvertFrom-Json -AsHashtable
}
function Test-Rules($Rules) {
    if (!$Rules) { return $true }
    $allow = $false
    foreach ($rule in $Rules) {
        $matches = $true
        if ($rule.os.name -and $rule.os.name -ne 'windows') { $matches = $false }
        if ($rule.os.arch -and $rule.os.arch -ne 'x86_64') { $matches = $false }
        if ($rule.os.version -and [Environment]::OSVersion.Version.ToString() -notmatch $rule.os.version) { $matches = $false }
        if ($rule.features) { $matches = $false }
        if ($matches) { $allow = $rule.action -eq 'allow' }
    }
    return $allow
}
function Expand-Arguments($Entries, $Variables) {
    foreach ($entry in $Entries) {
        if ($entry -is [string]) { $values = @($entry) }
        elseif (Test-Rules $entry.rules) { $values = @($entry.value) }
        else { continue }
        foreach ($value in $values) {
            foreach ($key in $Variables.Keys) { $value = $value.Replace(('${' + $key + '}'), [string]$Variables[$key]) }
            if ($value -match '\$\{') { throw "Unresolved launch variable: $value" }
            $value
        }
    }
}
function Write-JavaArguments([string]$Path, [string[]]$Values) {
    # Java argument files have their own quoting rules; do not build a shell command.
    $Values | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' } | Set-Content -LiteralPath $Path -Encoding utf8NoBOM
}
function Test-Running([string]$Name) {
    if (!$processes.ContainsKey($Name)) { return $false }
    $process = Get-Process -Id $processes[$Name].pid -ErrorAction SilentlyContinue
    if (!$process) { return $false }
    if ($process.ProcessName -notin @('java','javaw')) { return $false }
    return $process.StartTime.ToUniversalTime().ToString('o') -eq $processes[$Name].started
}
if ($Target -in @('All','Server') -and !(Test-Running 'server')) {
    $server = Join-Path $runtime 'server'
    $argumentFile = Join-Path $server 'launch-server.args'
    # Java does not recursively expand an @file nested inside another @file.
    @('-Xms1G','-Xmx4G','-Djava.awt.headless=true') + @(Get-Content -LiteralPath "$server/libraries/net/neoforged/neoforge/$($settings.neoForgeVersion)/win_args.txt") + @('nogui') | Set-Content -LiteralPath $argumentFile -Encoding utf8NoBOM
    if (!$PrepareOnly) {
        $process = Start-Process -FilePath (Join-Path $settings.javaHome 'bin/java.exe') -ArgumentList ('"@' + $argumentFile + '"') -WorkingDirectory $server -WindowStyle Hidden -RedirectStandardOutput "$server/console.log" -RedirectStandardError "$server/console-error.log" -PassThru
        $processes['server'] = @{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o'); directory = $server }
        $processes | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$runtime/processes.json" -Encoding utf8
        Write-Host "Started server PID $($process.Id); console: .local/server/console.log"
    }
}
if ($Target -eq 'All' -and !$PrepareOnly) {
    $deadline = [DateTime]::UtcNow.AddSeconds(55)
    do {
        $log = Get-Item -LiteralPath "$runtime/server/logs/latest.log" -ErrorAction SilentlyContinue
        $ready = $log -and $log.LastWriteTimeUtc -ge [DateTime]::Parse($processes['server'].started).ToUniversalTime() -and (Select-String -LiteralPath $log.FullName -Pattern 'Done \(' -Quiet)
        if ($ready) { break }
        if (!(Test-Running 'server')) { throw 'The test server exited. Inspect .local/server/console.log and console-error.log.' }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if (!$ready) { throw 'Server has not become ready within 55 seconds. Inspect .local/server/logs/latest.log, then rerun the launcher.' }
}
$selected = switch ($Target) { 'All' { @(1,2) } 'ClientOne' { @(1) } 'ClientTwo' { @(2) } default { @() } }
foreach ($number in $selected) {
    $instance = if ($number -eq 1) { 'client-one' } else { 'client-two' }
    if (Test-Running $instance) { Write-Host "$instance is already running."; continue }
    $name = if ($number -eq 1) { 'ReviveOne' } else { 'ReviveTwo' }
    $directory = Join-Path $runtime $instance
    $natives = Join-Path $directory 'natives'
    New-Item -ItemType Directory -Force -Path $natives | Out-Null
    $libraries = [ordered]@{}
    foreach ($library in @($minecraft.libraries) + @($neoforge.libraries)) {
        if (!(Test-Rules $library.rules)) { continue }
        if ($library.name -match ':natives-windows-(arm64|x86)$') { continue }
        $parts = $library.name -split ':'
        $key = "$($parts[0]):$($parts[1])"
        if ($parts.Length -gt 3) { $key += ":$($parts[3])" }
        $artifact = Join-Path $launcher "libraries/$($library.downloads.artifact.path)"
        if (!(Test-Path -LiteralPath $artifact)) { throw "Missing library $artifact. Run setup-test-runtime.ps1." }
        $libraries[$key] = $artifact
    }
    $classpath = (@($libraries.Values) + "$launcher/versions/1.21.1/1.21.1.jar") -join ';'
    $uuidBytes = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$name"))
    $uuidBytes[6] = ($uuidBytes[6] -band 15) -bor 48
    $uuidBytes[8] = ($uuidBytes[8] -band 63) -bor 128
    $variables = @{
        auth_player_name = $name; version_name = $version; game_directory = $directory
        assets_root = "$launcher/assets"; assets_index_name = $minecraft.assetIndex.id
        auth_uuid = [Convert]::ToHexString($uuidBytes).ToLowerInvariant(); auth_access_token = '0'
        clientid = 'local-development'; auth_xuid = '0'; user_type = 'legacy'; version_type = 'release'
        natives_directory = $natives; launcher_name = 'ragrevival-local-test'; launcher_version = '1'
        classpath = $classpath; library_directory = "$launcher/libraries"; classpath_separator = ';'
    }
    $arguments = @('-Xms1G','-Xmx4G')
    $arguments += @(Expand-Arguments (@($minecraft.arguments.jvm) + @($neoforge.arguments.jvm)) $variables)
    # NeoForge's inherited profile ignores a vanilla jar named after the selected
    # profile. This launcher preserves its original Minecraft filename instead;
    # exclude that exact jar too so FML loads only its patched minecraft module.
    $arguments = @($arguments | ForEach-Object {
        if ($_.StartsWith('-DignoreList=')) { $_ + ",$($minecraft.id).jar" } else { $_ }
    })
    $arguments += $neoforge.mainClass
    $arguments += @(Expand-Arguments (@($minecraft.arguments.game) + @($neoforge.arguments.game)) $variables)
    $arguments += @('--width','1060','--height','720','--quickPlayMultiplayer',$settings.server)
    $argumentFile = Join-Path $directory 'launch-client.args'
    Write-JavaArguments $argumentFile $arguments
    if (!$PrepareOnly) {
        # javaw suppresses a console while Minecraft creates the requested visible game window.
        $process = Start-Process -FilePath (Join-Path $settings.javaHome 'bin/javaw.exe') -ArgumentList ('"@' + $argumentFile + '"') -WorkingDirectory $directory -WindowStyle Normal -RedirectStandardOutput "$directory/console.log" -RedirectStandardError "$directory/console-error.log" -PassThru
        $processes[$instance] = @{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o'); directory = $directory; player = $name }
        Write-Host "Started $name PID $($process.Id); game directory .local/$instance"
    }
}
if (!$PrepareOnly) { $processes | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$runtime/processes.json" -Encoding utf8 }
