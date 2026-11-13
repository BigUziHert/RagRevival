#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Source = 'C:/Users/caleb/curseforge/minecraft/Instances/Modded Minecraft',
    [string]$Name = 'fullpack-manual-1.6.0'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$runtimePath = [IO.Path]::GetFullPath((Join-Path $repo ".local/$Name"))
if (!$runtimePath.StartsWith([IO.Path]::GetFullPath((Join-Path $repo '.local/')), [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime must stay inside .local.' }
if (Test-Path -LiteralPath $runtimePath) { throw 'Choose a fresh runtime directory; this setup never replaces an existing world.' }
New-Item -ItemType Directory -Path $runtimePath | Out-Null
$clientPath = Join-Path $runtimePath 'client'
$serverPath = Join-Path $runtimePath 'server'
New-Item -ItemType Directory -Path $clientPath,$serverPath | Out-Null
$folders = @('mods','config','defaultconfigs','kubejs','data','datapacks','moonlight-global-datapacks','patchouli_books')
$clientFolders = @('resourcepacks','shaderpacks','schematics')
$manifest = foreach ($folder in $folders + $clientFolders) {
    $from = Join-Path $sourcePath $folder
    if (!(Test-Path -LiteralPath $from)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $from -Recurse -File) {
        [pscustomobject]@{ path = [IO.Path]::GetRelativePath($sourcePath,$file.FullName); bytes = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
    }
    Copy-Item -LiteralPath $from -Destination $clientPath -Recurse
    if ($folder -in $folders) { Copy-Item -LiteralPath $from -Destination $serverPath -Recurse }
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$runtimePath/source-manifest.json" -Encoding utf8
Copy-Item -LiteralPath "$sourcePath/options.txt" -Destination "$clientPath/options.txt"
Copy-Item -LiteralPath "$repo/.local/corpse-compat/client/natives" -Destination $clientPath -Recurse
Copy-Item -LiteralPath "$repo/.local/corpse-sublevels/libraries" -Destination $serverPath -Recurse
Copy-Item -LiteralPath "$repo/.local/corpse-sublevels/ops.json","$repo/.local/corpse-sublevels/eula.txt","$repo/.local/corpse-sublevels/server-command.ps1" -Destination $serverPath
foreach ($target in @($serverPath,$clientPath)) {
    New-Item -ItemType Directory -Path "$target/disabled-original-mods" | Out-Null
    foreach ($old in Get-ChildItem -LiteralPath "$target/mods" -Filter 'ragrevival-*.jar') {
        Move-Item -LiteralPath $old.FullName -Destination "$target/disabled-original-mods/$($old.Name)"
    }
    Copy-Item -LiteralPath "$repo/artifacts/1.6.0/ragrevival-1.21.1-1.6.0.jar" -Destination "$target/mods"
}
$argsText = Get-Content -LiteralPath "$repo/.local/corpse-compat/client/launch-native-1.6.0.args" -Raw
$oldClient = (Resolve-Path -LiteralPath "$repo/.local/corpse-compat/client").Path.Replace('\','/')
$argsText = $argsText.Replace($oldClient,$clientPath.Replace('\','/')).Replace('"-Xmx4G"','"-Xmx10G"').Replace('127.0.0.1:25611','127.0.0.1:25621')
$argsText | Set-Content -LiteralPath "$clientPath/launch-client.args" -Encoding utf8NoBOM
$serverArgs = Get-Content -LiteralPath "$repo/.local/corpse-sublevels/launch-server.args" -Raw
$serverArgs = $serverArgs.Replace('-Xmx4G','-Xmx8G')
$serverArgs | Set-Content -LiteralPath "$serverPath/launch-server.args" -Encoding utf8NoBOM
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
@"
server-ip=127.0.0.1
server-port=25621
online-mode=false
enforce-secure-profile=false
enable-rcon=true
rcon.port=25622
rcon.password=$password
enable-query=false
motd=RagRevival full modpack manual tests
max-players=4
gamemode=survival
difficulty=normal
spawn-protection=0
level-name=world
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:air","height"\:1}],"biome"\:"minecraft\:plains","features"\:false,"lakes"\:false}
generate-structures=false
view-distance=6
simulation-distance=5
sync-chunk-writes=true
max-tick-time=-1
pause-when-empty-seconds=-1
enable-command-block=true
function-permission-level=4
allow-flight=true
"@ | Set-Content -LiteralPath "$serverPath/server.properties" -Encoding utf8NoBOM
# Keep copied controls while making this laboratory launch readable and bounded.
$optionLines = Get-Content -LiteralPath "$clientPath/options.txt"
$changes = @{ fullscreen='false'; renderDistance='8'; simulationDistance='5'; maxFps='90'; pauseOnLostFocus='false'; onboardAccessibility='false'; skipMultiplayerWarning='true'; guiScale='2' }
foreach ($key in $changes.Keys) {
    $optionLines = @($optionLines | Where-Object { !$_.StartsWith($key+':') }) + ($key+':'+$changes[$key])
}
$optionLines | Set-Content -LiteralPath "$clientPath/options.txt" -Encoding utf8NoBOM
$irisPath = "$clientPath/config/iris.properties"
if (Test-Path -LiteralPath $irisPath) {
    $irisLines = @(Get-Content -LiteralPath $irisPath | Where-Object { $_ -notmatch '^enableShaders=' }) + 'enableShaders=false'
    $irisLines | Set-Content -LiteralPath $irisPath -Encoding utf8NoBOM
}
@{ source=$sourcePath; runtime=$runtimePath; server='127.0.0.1:25621'; sourceMods=@($manifest | Where-Object { $_.path -like 'mods*' }).Count; changes=@('Replaced copied RagRevival 1.4.3 with 1.6.0','New empty test world','Enabled local command blocks for test stations','Shaders off in test client; bounded view distances'); sourceUntouched=$true } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$runtimePath/setup.json"
Write-Output "Copied $(@($manifest | Where-Object { $_.path -like 'mods*' }).Count) original mod files and pack settings to $runtimePath"
