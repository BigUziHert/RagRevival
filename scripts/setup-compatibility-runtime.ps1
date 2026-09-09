#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('219','228','248','250')][string]$Build = '248',
    [string]$SableVersion,
    [ValidateRange(0,65535)][int]$GamePort = 0,
    [ValidateRange(0,65535)][int]$RconPort = 0,
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9]+)*$')]
    [string]$ModVersion = '1.3.5',
    [string]$JavaHome,
    [string]$UnlockedCameraJar,
    [switch]$SkipUnlockedCamera,
    [switch]$IncludeHarness
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$compat = Join-Path $repo '.local/compat'
$runtime = Join-Path $compat "runtime-$Build"
$version = "21.1.$Build"
$launcher = Join-Path $runtime 'launcher'
$server = Join-Path $runtime 'server'
if (!$SableVersion) { $SableVersion = if ($Build -eq '219') { '1.1.1' } else { '2.0.5' } }
$ports = @{ '219' = 25587; '228' = 25589; '248' = 25585; '250' = 25591 }
if (!$GamePort) { $GamePort = $ports[$Build] }
if (!$RconPort) { $RconPort = $GamePort + 1 }
if ($GamePort -eq $RconPort -or $RconPort -gt 65535) { throw 'Game and RCON ports must be distinct valid ports.' }
if (Test-Path -LiteralPath "$runtime/server/world") { throw "Refusing to overwrite an initialized runtime: $runtime" }
if (Test-Path -LiteralPath "$runtime/processes.json") { throw "Runtime has been launched already; preserve its state and use its local helpers: $runtime" }
if (Test-Path -LiteralPath "$runtime/installation.json") { throw "Runtime is already prepared; use its local helpers: $runtime" }
if (Test-Path -LiteralPath "$server/server.properties") {
    $existingProperties = Get-Content -LiteralPath "$server/server.properties"
    $requestedProperties = @{ 'server-ip' = '127.0.0.1'; 'server-port' = $GamePort; 'rcon.port' = $RconPort }
    foreach ($property in $requestedProperties.GetEnumerator()) {
        $configured = @($existingProperties | Where-Object { $_ -match ('^' + [regex]::Escape($property.Key) + '=') })
        if ($configured.Count -ne 1 -or $configured[0] -cne "$($property.Key)=$($property.Value)") {
            throw 'Existing server.properties does not match the requested loopback address or ports. Preserve its settings and retry with matching ports.'
        }
    }
}
foreach ($path in @('runtime.json','launcher/libraries','launcher/assets','launcher/versions/1.21.1','server/libraries')) {
    if (!(Test-Path -LiteralPath "$repo/.local/$path")) { throw 'Prepare the base runtime first with scripts/setup-test-runtime.ps1.' }
}
if (!$JavaHome) { $JavaHome = (Get-Content -LiteralPath "$repo/.local/runtime.json" -Raw | ConvertFrom-Json).javaHome }
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "Java 21 was not found: $java. Supply -JavaHome." }
$mainJar = "$repo/build/libs/ragrevival-1.21.1-$ModVersion.jar"
if (!(Test-Path -LiteralPath $mainJar)) { $mainJar = "$repo/artifacts/$ModVersion/ragrevival-1.21.1-$ModVersion.jar" }
if (!(Test-Path -LiteralPath $mainJar)) { throw "Build RagRevival $ModVersion first, or use its versioned artifact." }
$harnessJar = "$repo/build/libs/ragrevival-1.21.1-$ModVersion-test-harness.jar"
if ($IncludeHarness -and !(Test-Path -LiteralPath $harnessJar)) { throw 'Build the opt-in harness first with ./gradlew.bat testModJar.' }
$review = Get-Content -LiteralPath "$repo/docs/sable-api-matrix-1.3.5.json" -Raw | ConvertFrom-Json
$sableCandidates = @($review.results | Where-Object version -EQ $SableVersion)
if ($sableCandidates.Count -ne 1) { throw "Sable $SableVersion is not in the reviewed dependency matrix." }
$sable = $sableCandidates[0]
$neoRequirement = @($sable.dependencies | Where-Object modId -EQ 'neoforge')[0].versionRange
if ($neoRequirement -notmatch '^\[(21\.1\.[0-9]+),\)$' -or [version]$version -lt [version]$Matches[1]) {
    throw "Sable $SableVersion requires NeoForge $neoRequirement; selected $version."
}
$sableDirectory = Join-Path $compat 'sable-review'
$sableJar = Join-Path $sableDirectory "sable-neoforge-1.21.1-$SableVersion.jar"
New-Item -ItemType Directory -Force -Path $sableDirectory | Out-Null
if (!(Test-Path -LiteralPath $sableJar) -or (Get-FileHash -LiteralPath $sableJar -Algorithm SHA256).Hash -ine $sable.sha256) {
    $partial = "$sableJar.download"
    try {
        Invoke-WebRequest -Uri $sable.url -OutFile $partial
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ine $sable.sha256) { throw "Sable $SableVersion SHA256 mismatch." }
        Move-Item -LiteralPath $partial -Destination $sableJar -Force
    } finally { if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial } }
}
# The base setup fetches the locked public runtime dependencies, including Carry On.
$dependencyLock = Get-Content -LiteralPath "$repo/docs/dependencies.lock.json" -Raw | ConvertFrom-Json
$publicMods = @('sable_player_ragdoll-1.21.1-0.7.2.jar','ragdoll_reactions-1.21.1-0.7.0.jar','carryon-neoforge-1.21.1-2.2.6.13.jar')
foreach ($file in $publicMods) {
    $locked = @($dependencyLock.dependencies | Where-Object file -EQ $file)
    $path = "$repo/.local/deps/$file"
    if ($locked.Count -ne 1 -or !(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine $locked[0].sha256) {
        throw "Missing or unverified $file; run scripts/fetch-dependencies.ps1."
    }
}
if (!$UnlockedCameraJar) { $UnlockedCameraJar = "$repo/.local/deps/unlockedcamera-1.0.0.jar" }
$useUnlockedCamera = !$SkipUnlockedCamera -and [int]$Build -ge 235 -and (Test-Path -LiteralPath $UnlockedCameraJar)
if (!$useUnlockedCamera) { Write-Output 'Unlocked Camera omitted: disabled, unavailable locally, or below its NeoForge 21.1.235 minimum.' }
foreach ($directory in @('installers/client','installers/server','launcher','server','client-one','client-two')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $runtime $directory) | Out-Null
}
Write-Output "Preparing NeoForge $version with Sable $SableVersion in $runtime"
# Only immutable launcher artifacts are reused. Each game instance starts with its own empty world/config state.
if (!(Test-Path -LiteralPath "$launcher/libraries")) {
    Copy-Item -LiteralPath "$repo/.local/launcher/libraries" -Destination $launcher -Recurse
}
if (!(Test-Path -LiteralPath "$server/libraries")) {
    Copy-Item -LiteralPath "$repo/.local/server/libraries" -Destination $server -Recurse
}
New-Item -ItemType Directory -Force -Path "$launcher/versions" | Out-Null
if (!(Test-Path -LiteralPath "$launcher/versions/1.21.1")) {
    Copy-Item -LiteralPath "$repo/.local/launcher/versions/1.21.1" -Destination "$launcher/versions" -Recurse
}
'{"profiles":{},"settings":{},"version":3}' | Set-Content -LiteralPath "$launcher/launcher_profiles.json" -Encoding utf8NoBOM
$installer = Join-Path $runtime "installers/neoforge-$version-installer.jar"
$installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"
if (!(Test-Path -LiteralPath $installer)) { Invoke-WebRequest $installerUrl -OutFile $installer }
$checksumContent = (Invoke-WebRequest "$installerUrl.sha1").Content
$expectedSha1 = if ($checksumContent -is [byte[]]) { [Text.Encoding]::UTF8.GetString($checksumContent).Trim() } else { ([string]$checksumContent).Trim() }
if ((Get-FileHash -LiteralPath $installer -Algorithm SHA1).Hash -ine $expectedSha1) { throw "Installer checksum mismatch: $version" }
foreach ($kind in @('client','server')) {
    $destination = if ($kind -eq 'client') { $launcher } else { $server }
    $expectedArtifact = "$destination/libraries/net/neoforged/neoforge/$version/neoforge-$version-$kind.jar"
    $expectedConfiguration = if ($kind -eq 'client') { "$launcher/versions/neoforge-$version/neoforge-$version.json" } else { "$server/libraries/net/neoforged/neoforge/$version/win_args.txt" }
    if (!(Test-Path -LiteralPath $expectedArtifact) -or !(Test-Path -LiteralPath $expectedConfiguration)) {
        Push-Location "$runtime/installers/$kind"
        try {
            $installFlag = if ($kind -eq 'client') { '--installClient' } else { '--installServer' }
            & $java -jar $installer $installFlag $destination *> 'install.log'
            if ($LASTEXITCODE -ne 0) { throw "NeoForge $kind install failed. Inspect $runtime/installers/$kind/install.log" }
        } finally { Pop-Location }
    }
    Write-Output "NeoForge $version $kind installation ready."
}
$settings = @{
    javaHome = $JavaHome; neoForgeVersion = $version; sableVersion = $SableVersion
    server = "127.0.0.1:$GamePort"; rconPort = $RconPort
    assetsRoot = "$repo/.local/launcher/assets"; players = @('ReviveOne','ReviveTwo')
    installedModVersion = $ModVersion; unlockedCamera = [bool]$useUnlockedCamera; includesHarness = [bool]$IncludeHarness
}
$settings | ConvertTo-Json | Set-Content -LiteralPath "$runtime/runtime.json" -Encoding utf8NoBOM
if (!(Test-Path -LiteralPath "$server/server.properties")) {
    $rconSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    @"
server-ip=127.0.0.1
server-port=$GamePort
online-mode=false
enforce-secure-profile=false
enable-rcon=true
rcon.port=$RconPort
rcon.password=$rconSecret
enable-query=false
motd=RagRevival NeoForge $version Sable $SableVersion compatibility test
max-players=4
gamemode=survival
difficulty=normal
spawn-protection=0
level-name=world
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1},{"block"\:"minecraft\:dirt","height"\:2},{"block"\:"minecraft\:grass_block","height"\:1}],"biome"\:"minecraft\:plains"}
view-distance=6
simulation-distance=5
sync-chunk-writes=true
max-tick-time=120000
pause-when-empty-seconds=-1
"@ | Set-Content -LiteralPath "$server/server.properties" -Encoding utf8NoBOM
}
'eula=true' | Set-Content -LiteralPath "$server/eula.txt" -Encoding utf8NoBOM
@(
    @{ uuid = '8e37a656-4e67-3507-b517-7df18801794f'; name = 'ReviveOne'; level = 4; bypassesPlayerLimit = $true },
    @{ uuid = '0a69efb0-a15b-3249-af6b-e94eaad5dfcd'; name = 'ReviveTwo'; level = 4; bypassesPlayerLimit = $true }
) | ConvertTo-Json | Set-Content -LiteralPath "$server/ops.json" -Encoding utf8NoBOM
$requiredMods = @(
    $mainJar,
    $sableJar
) + @($publicMods | ForEach-Object { "$repo/.local/deps/$_" })
if ($IncludeHarness) { $requiredMods += $harnessJar }
foreach ($instance in @('server','client-one','client-two')) {
    $directory = Join-Path $runtime $instance
    New-Item -ItemType Directory -Force -Path "$directory/mods" | Out-Null
    foreach ($mod in $requiredMods) { Copy-Item -LiteralPath $mod -Destination "$directory/mods" -Force }
    if ($instance -eq 'server') { continue }
    New-Item -ItemType Directory -Force -Path "$directory/config" | Out-Null
    if ($useUnlockedCamera) { Copy-Item -LiteralPath $UnlockedCameraJar -Destination "$directory/mods" -Force }
    @'
version:3955
autoJump:false
renderDistance:6
simulationDistance:5
maxFps:60
enableVsync:true
fullscreen:false
guiScale:2
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarning:true
soundCategory_master:0.15
'@ | Set-Content -LiteralPath "$directory/options.txt" -Encoding utf8NoBOM
}
$launchSource = Get-Content -LiteralPath "$repo/scripts/start-test-runtime.ps1" -Raw
# Derive the maintained launcher and RCON helper without changing the base runtime.
$runtimePattern = '(?m)^\$repo = Split-Path \$PSScriptRoot -Parent\r?\n\$runtime = Join-Path \$repo ''\.local'''
if ($launchSource -notmatch $runtimePattern -or !$launchSource.Contains('assets_root = "$launcher/assets"')) {
    throw 'The base launcher structure changed; review the compatibility launcher adaptation before proceeding.'
}
$launchSource = $launchSource -replace $runtimePattern, '$runtime = $PSScriptRoot'
$launchSource = $launchSource.Replace('assets_root = "$launcher/assets"', 'assets_root = $settings.assetsRoot')
$launchSource = $launchSource.Replace('[DateTime]::Parse($processes[''server''].started)', '([datetime]$processes[''server''].started)')
$launchSource = $launchSource.Replace('console: .local/server/console.log', 'console: $server/console.log')
$launchSource = $launchSource.Replace('game directory .local/$instance', 'game directory $directory')
$launchSource = $launchSource.Replace('Inspect .local/server/', 'Inspect this runtime server/')
$launchSource | Set-Content -LiteralPath "$runtime/start-runtime.ps1" -Encoding utf8NoBOM
$rconSource = Get-Content -LiteralPath "$repo/scripts/test-server-command.ps1" -Raw
$rconPattern = '(?m)^\$repo = Split-Path \$PSScriptRoot -Parent\r?\n\$properties = Get-Content -LiteralPath "\$repo/\.local/server/server.properties"'
if ($rconSource -notmatch $rconPattern) { throw 'The base RCON helper structure changed; review the compatibility helper adaptation before proceeding.' }
$rconSource = $rconSource -replace $rconPattern, '$properties = Get-Content -LiteralPath "$PSScriptRoot/server/server.properties"'
$rconSource | Set-Content -LiteralPath "$runtime/server-command.ps1" -Encoding utf8NoBOM
& "$runtime/start-runtime.ps1" -Target All -PrepareOnly
$manifest = @{
    installedAtUtc = [DateTime]::UtcNow.ToString('o'); installerUrl = $installerUrl
    installerSha256 = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    sableUrl = $sable.url; sableSha256 = $sable.sha256
    runtime = $settings
    mods = @(foreach ($instance in @('server','client-one','client-two')) {
        Get-ChildItem -LiteralPath "$runtime/$instance/mods" -Filter '*.jar' | ForEach-Object {
            @{ instance = $instance; file = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        }
    })
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$runtime/installation.json" -Encoding utf8NoBOM
Write-Output "Prepared $runtime; no game/server process launched."
