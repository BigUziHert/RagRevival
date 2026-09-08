#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21',
    [string]$NeoForgeVersion = '21.1.249',
    [string]$AssetCache = "$env:APPDATA\.minecraft\assets"
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $repo '.local'
$launcher = Join-Path $runtime 'launcher'
$server = Join-Path $runtime 'server'
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "Java 21 was not found: $java. Supply -JavaHome." }
$null = & $java -version 2>&1
if ($LASTEXITCODE) { throw 'Java could not start.' }
foreach ($directory in @('installers','launcher','server','client-one','client-two','deps')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $runtime $directory) | Out-Null
}
& (Join-Path $PSScriptRoot 'fetch-dependencies.ps1') -Destination (Join-Path $runtime 'deps')
$installer = Join-Path $runtime "installers/neoforge-$NeoForgeVersion-installer.jar"
if (!(Test-Path -LiteralPath $installer)) {
    Invoke-WebRequest "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NeoForgeVersion/neoforge-$NeoForgeVersion-installer.jar" -OutFile $installer
}
if (!(Test-Path -LiteralPath "$launcher/launcher_profiles.json")) {
    '{"profiles":{},"settings":{},"version":3}' | Set-Content -Encoding utf8 "$launcher/launcher_profiles.json"
}
Push-Location (Join-Path $runtime 'installers')
try {
    if (!(Test-Path -LiteralPath "$launcher/libraries/net/neoforged/neoforge/$NeoForgeVersion/neoforge-$NeoForgeVersion-client.jar")) {
        & $java -jar $installer --installClient $launcher *> 'install-client.log'
        if ($LASTEXITCODE) { throw 'NeoForge client installation failed; see .local/installers/install-client.log.' }
    }
    if (!(Test-Path -LiteralPath "$server/libraries/net/neoforged/neoforge/$NeoForgeVersion/neoforge-$NeoForgeVersion-server.jar")) {
        & $java -jar $installer --installServer $server *> 'install-server.log'
        if ($LASTEXITCODE) { throw 'NeoForge server installation failed; see .local/installers/install-server.log.' }
    }
} finally { Pop-Location }
$minecraft = Get-Content -LiteralPath "$launcher/versions/1.21.1/1.21.1.json" -Raw | ConvertFrom-Json
$assets = Join-Path $launcher 'assets'
New-Item -ItemType Directory -Force -Path "$assets/indexes" | Out-Null
$indexPath = "$assets/indexes/$($minecraft.assetIndex.id).json"
if (!(Test-Path -LiteralPath $indexPath)) { Invoke-WebRequest $minecraft.assetIndex.url -OutFile $indexPath }
if ((Get-FileHash -Algorithm SHA1 -LiteralPath $indexPath).Hash -ne $minecraft.assetIndex.sha1) { throw 'Minecraft asset index hash mismatch.' }
$index = Get-Content -LiteralPath $indexPath -Raw | ConvertFrom-Json -AsHashtable
$objects = $index.objects.Values | Sort-Object hash -Unique
Write-Host "Verifying/downloading $($objects.Count) official Minecraft assets..."
$assetErrors = $objects | ForEach-Object -ThrottleLimit 12 -Parallel {
    $hash = $_.hash
    $prefix = $hash.Substring(0,2)
    $destination = Join-Path $using:assets "objects/$prefix/$hash"
    if (Test-Path -LiteralPath $destination) {
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA1).Hash -eq $hash) { return }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    $source = Join-Path $using:AssetCache "objects/$prefix/$hash"
    if ((Test-Path -LiteralPath $source) -and ((Get-FileHash -LiteralPath $source -Algorithm SHA1).Hash -eq $hash)) {
        Copy-Item -LiteralPath $source -Destination $destination
        return
    }
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        try {
            Invoke-WebRequest "https://resources.download.minecraft.net/$prefix/$hash" -OutFile $destination -ErrorAction Stop
            if ((Get-FileHash -LiteralPath $destination -Algorithm SHA1).Hash -ne $hash) { throw "Hash mismatch for $hash" }
            return
        } catch { if ($attempt -eq 2) { Write-Output "$hash : $_" } }
    }
}
if ($assetErrors) { throw "Assets failed: $($assetErrors -join ', ')" }
# NeoForge's installer supplies its libraries; supply any missing vanilla Windows artifacts as well.
foreach ($library in $minecraft.libraries) {
    if ($library.rules -and !($library.rules | Where-Object { $_.os.name -eq 'windows' -and $_.action -eq 'allow' })) { continue }
    $artifact = $library.downloads.artifact
    if (!$artifact) { continue }
    $destination = Join-Path $launcher "libraries/$($artifact.path)"
    if (!(Test-Path -LiteralPath $destination)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
        Invoke-WebRequest $artifact.url -OutFile $destination
    }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA1).Hash -ne $artifact.sha1) { throw "Library hash mismatch: $($library.name)" }
}
# Offline identities are intentionally scoped to this loopback-only local development server.
function Get-OfflineUuid([string]$Name) {
    $bytes = [System.Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Name"))
    $bytes[6] = ($bytes[6] -band 15) -bor 48
    $bytes[8] = ($bytes[8] -band 63) -bor 128
    $hex = [Convert]::ToHexString($bytes).ToLowerInvariant()
    return "$($hex.Substring(0,8))-$($hex.Substring(8,4))-$($hex.Substring(12,4))-$($hex.Substring(16,4))-$($hex.Substring(20,12))"
}
if (!(Test-Path -LiteralPath "$server/server.properties")) {
    $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    @"
server-ip=127.0.0.1
server-port=25575
online-mode=false
enforce-secure-profile=false
enable-rcon=true
rcon.port=25576
rcon.password=$password
enable-query=false
motd=RagRevival two-player local test
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
"@ | Set-Content -LiteralPath "$server/server.properties" -Encoding utf8
}
if (!(Test-Path -LiteralPath "$server/eula.txt")) { 'eula=true' | Set-Content -LiteralPath "$server/eula.txt" -Encoding utf8 }
if (!(Test-Path -LiteralPath "$server/ops.json")) {
    @('ReviveOne','ReviveTwo') | ForEach-Object { @{ uuid = Get-OfflineUuid $_; name = $_; level = 4; bypassesPlayerLimit = $true } } | ConvertTo-Json -AsArray | Set-Content -LiteralPath "$server/ops.json" -Encoding utf8
}
foreach ($client in @('client-one','client-two')) {
    $directory = Join-Path $runtime $client
    New-Item -ItemType Directory -Force -Path "$directory/mods","$directory/config" | Out-Null
    if (!(Test-Path -LiteralPath "$directory/options.txt")) {
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
'@ | Set-Content -LiteralPath "$directory/options.txt" -Encoding utf8
    }
}
New-Item -ItemType Directory -Force -Path "$server/mods" | Out-Null
@{ javaHome = $JavaHome; neoForgeVersion = $NeoForgeVersion; server = '127.0.0.1:25575'; players = @('ReviveOne','ReviveTwo') } | ConvertTo-Json | Set-Content -LiteralPath "$runtime/runtime.json" -Encoding utf8
Write-Host 'Runtime installed. Use sync-test-mods.ps1 after building, then start-test-runtime.ps1.'
