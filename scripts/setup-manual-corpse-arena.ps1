#requires -Version 7.0
<#
.SYNOPSIS
Builds the isolated full-modpack manual Corpse test arena.
.DESCRIPTION
Without -Apply this only writes a reviewable command list and layout manifest.
Use only the fresh, disposable .local fullpack server, with command blocks enabled.
The script never reads or writes the original CurseForge instance. It does not run
any commands until -Apply is supplied. Rebuild before testing, not over live corpses.

Minecraft Java 1.21.1: signs use front_text/back_text JSON strings; item replacement
uses the item command; button actions use permission-level-2 command blocks.
The separate test harness provides the level-2 ragrevivalmanual accessory commands.
The real lava and void tests use environmental damage, never a kill/damage command.
#>
[CmdletBinding()]
param(
    [string]$RconHelper = '',
    [ValidatePattern('^[A-Za-z0-9_]{1,16}$')][string]$Player = 'ReviveOne',
    [string]$OutputDirectory = '',
    [switch]$Apply,
    [switch]$TeleportPlayer
)
$ErrorActionPreference = 'Stop'
$arenaRepo = Split-Path $PSScriptRoot -Parent
$arenaLocalRoot = [IO.Path]::GetFullPath((Join-Path $arenaRepo '.local/'))
if (!$RconHelper) { $RconHelper = Join-Path $arenaLocalRoot 'fullpack-manual-1.6.0/server/server-command.ps1' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $arenaLocalRoot 'fullpack-manual-1.6.0/arena' }
$arenaHelperPath = [IO.Path]::GetFullPath($RconHelper)
$arenaOutputPath = [IO.Path]::GetFullPath($OutputDirectory)
foreach ($arenaPath in @($arenaHelperPath, $arenaOutputPath)) {
    if (!$arenaPath.StartsWith($arenaLocalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The helper and generated files must stay inside this checkout .local directory.'
    }
}

$arenaCommands = [Collections.Generic.List[string]]::new()
$arenaButtons = [Collections.Generic.List[object]]::new()
function Add-ArenaCommand([string]$Command) {
    if ($Command.Contains("`n") -or $Command.Contains("`r")) { throw 'Each Minecraft command must occupy one line.' }
    $arenaCommands.Add("execute in minecraft:overworld run $Command")
}
function Quote-Snbt([string]$Value) { ConvertTo-Json -InputObject $Value -Compress }
function Add-ArenaSign([int]$X, [int]$Y, [int]$Z, [string[]]$Lines, [string]$Color = 'black') {
    $messages = for ($line = 0; $line -lt 4; $line++) {
        $label = if ($line -lt $Lines.Length) { $Lines[$line] } else { '' }
        $component = @{ text = $label } | ConvertTo-Json -Compress
        Quote-Snbt $component
    }
    $face = '{messages:[' + ($messages -join ',') + '],color:' + (Quote-Snbt $Color) + ',has_glowing_text:1b}'
    Add-ArenaCommand "setblock $X $Y $Z minecraft:birch_sign[rotation=0]{is_waxed:1b,front_text:$face,back_text:$face}"
}
function Add-ArenaTitle([double]$X, [double]$Y, [double]$Z, [string]$Text, [string]$Color) {
    $component = @{ text = $Text; color = $Color; bold = $true } | ConvertTo-Json -Compress
    $quoted = Quote-Snbt $component
    Add-ArenaCommand "summon minecraft:text_display $X $Y $Z {Tags:[`"ragrevival_manual_arena`"],text:$quoted,billboard:`"center`",line_width:260,background:1073741824,shadow:1b,view_range:0.75f}"
}
function Add-ArenaButton([int]$X, [int]$Y, [int]$Z, [string[]]$Label, [string[]]$Actions, [string]$Color = 'black', [ValidateSet('north','south')][string]$Facing = 'south', [bool]$ConditionalChain = $false) {
    # Each action explicitly selects the nearby named Survival tester. A teleport
    # is always the last action; later chain blocks never chase the player away.
    for ($index = 0; $index -lt $Actions.Length; $index++) {
        $blockY = $Y - $index
        $block = if ($index -eq 0) { 'command_block' } else { 'chain_command_block' }
        $automatic = if ($index -eq 0) { '0b' } else { '1b' }
        $conditional = if ($index -gt 0 -and $ConditionalChain) { 'true' } else { 'false' }
        $action = "execute as @a[name=$Player,gamemode=survival,distance=..8,limit=1] run " + $Actions[$index]
        $quoted = Quote-Snbt $action
        Add-ArenaCommand "setblock $X $blockY $Z minecraft:${block}[facing=down,conditional=$conditional]{Command:$quoted,auto:$automatic,TrackOutput:0b,UpdateLastExecution:1b}"
    }
    $buttonZ = if ($Facing -eq 'north') { $Z - 1 } else { $Z + 1 }
    Add-ArenaCommand "setblock $X $Y $buttonZ minecraft:stone_button[face=wall,facing=$Facing,powered=false]"
    Add-ArenaSign $X ($Y + 1) $Z $Label $Color
    $arenaButtons.Add(@{ position = @($X,$Y,$buttonZ); label = $Label; actions = $Actions })
}
function Add-AccessoryPrep([int]$X, [int]$Y, [int]$Z, [string]$Scenario) {
    Add-ArenaButton $X $Y $Z @('1. PREP KIT', $Scenario.ToUpperInvariant(), 'Free hat + neck', 'Then press once') @(
        "ragrevivalmanual accessories-equip $Player $Scenario",
        "ragrevivalmanual accessories-check $Player before",
        'effect give @s minecraft:instant_health 1 6 true',
        'effect give @s minecraft:saturation 1 6 true'
    ) 'blue' 'south' $true
}

# Temporary loading covers the complete construction footprint. It is removed
# after installation; the arena adds no permanent corpse/physics chunk tickets.
Add-ArenaCommand 'forceload add -32 -32 32 16'
Add-ArenaCommand 'gamerule keepInventory false'
Add-ArenaCommand 'gamerule spawnRadius 0'
Add-ArenaCommand 'gamerule doMobSpawning false'
Add-ArenaCommand 'gamerule doDaylightCycle false'
Add-ArenaCommand 'gamerule doWeatherCycle false'
Add-ArenaCommand 'gamerule commandBlockOutput false'
Add-ArenaCommand 'gamerule doImmediateRespawn false'
Add-ArenaCommand 'difficulty normal'
Add-ArenaCommand 'time set noon'
Add-ArenaCommand 'weather clear'
if ($TeleportPlayer) {
    # Keep an online tester safe while the old temporary hub is replaced.
    Add-ArenaCommand "execute if entity $Player run gamemode spectator $Player"
}
Add-ArenaCommand 'kill @e[type=minecraft:text_display,tag=ragrevival_manual_arena]'
Add-ArenaCommand 'fill -32 77 -32 32 86 12 minecraft:air'
Add-ArenaCommand 'fill -9 80 -8 9 80 12 minecraft:smooth_quartz'
Add-ArenaCommand 'fill -30 80 -9 -12 80 9 minecraft:smooth_stone'
Add-ArenaCommand 'fill 12 80 -9 30 80 9 minecraft:smooth_stone'
Add-ArenaCommand 'fill -12 80 -2 12 80 2 minecraft:sea_lantern'
Add-ArenaCommand 'fill -8 80 -32 8 80 -16 minecraft:smooth_stone'
Add-ArenaCommand 'fill -2 80 -16 2 80 -8 minecraft:sea_lantern'
Add-ArenaCommand 'fill -9 80 -8 9 80 -8 minecraft:blue_concrete'
Add-ArenaCommand 'setblock 0 80 6 minecraft:gold_block'
Add-ArenaCommand 'setworldspawn 0 81 6 180'
Add-ArenaCommand "execute if entity $Player run spawnpoint $Player 0 81 6 180"

Add-ArenaTitle 0.5 85 8.5 'CORPSE TEST HUB' 'aqua'
Add-ArenaSign 0 81 8 @('Start in Survival', 'Real inventory', 'Real death tests', 'Respawn here')
Add-ArenaSign -2 81 8 @('Crouch: CapsLock', 'Loot: crouch+Use', 'Drag: empty Use', 'Give Up: hold G')
Add-ArenaSign 2 81 8 @('After each test', 'Store old loot', 'Free hat + neck', 'Then PREP again')
Add-ArenaSign 4 81 8 @('Login: wait 30s', 'Respawn: wait 10s', 'Pack protection', 'Then start test')
Add-ArenaSign 6 81 8 @('H: Accessories', 'Check hat + scarf', 'before / after', 'corpse transfer')
Add-ArenaButton -7 81 7 @('AFTER DEATH', 'Check tagged kit', 'Before looting', 'Read chat/report') @("ragrevivalmanual accessories-check $Player after_death") 'blue'
Add-ArenaButton 7 81 7 @('AFTER TRANSFER', 'Check tagged kit', 'After looting', 'Read chat/report') @("ragrevivalmanual accessories-check $Player after_transfer") 'blue'
Add-ArenaButton -7 81 3 @('HEAL + FEED', 'Between tests', 'No resistance', 'Keeps your loot') @('effect give @s minecraft:instant_health 1 6 true','effect give @s minecraft:saturation 1 6 true')
Add-ArenaButton 7 81 3 @('VOID RECOVERY', 'After respawn', 'Safe lower ring', 'Mind the hole') @('tp @s 2.5 -63 -24.5 90 30') 'purple'

# Supplies are physical inventory, not keepInventory or forced item transfers.
Add-ArenaCommand 'setblock -4 81 11 minecraft:chest[facing=north]'
$arenaSupplies = @('minecraft:diamond 7','minecraft:iron_chestplate 1','minecraft:iron_helmet 1','minecraft:iron_leggings 1','minecraft:iron_boots 1','minecraft:shield 1','minecraft:bread 16','minecraft:iron_pickaxe 1')
for ($slot = 0; $slot -lt $arenaSupplies.Length; $slot++) {
    Add-ArenaCommand "item replace block -4 81 11 container.$slot with $($arenaSupplies[$slot])"
}
Add-ArenaSign -4 82 11 @('VANILLA KIT', '7 diamonds', 'Armor + shield', 'Take before test')
Add-ArenaCommand 'setblock 4 81 11 minecraft:chest[facing=north]'
Add-ArenaSign 4 82 11 @('RECOVERED LOOT', 'Store old kits', 'Do not PREP over', 'equipped hat/scarf')

# Lava pool: two source layers over a solid bottom. Only the user's LAVA TEST
# button enters it. Nothing damages/kills the player by command in this station.
Add-ArenaCommand 'fill -26 77 -3 -20 80 3 minecraft:polished_blackstone_bricks'
Add-ArenaCommand 'fill -25 78 -2 -21 80 2 minecraft:air'
Add-ArenaCommand 'fill -25 78 -2 -21 79 2 minecraft:lava'
Add-ArenaTitle -23.5 85 -5.5 'LAVA / REAL DAMAGE' 'gold'
Add-AccessoryPrep -28 81 5 'lava'
Add-ArenaButton -24 81 5 @('2. LAVA TEST', 'Real lava damage', 'Downed: hold G', 'or wait timer') @('tp @s -23.5 80 0.5 0 30') 'red'
Add-ArenaButton -20 81 5 @('3. DRAIN LAVA', 'After respawn', 'Recover your body', 'Refill when done') @('fill -25 78 -2 -21 79 2 minecraft:air replace minecraft:lava') 'blue'
Add-ArenaButton -16 81 5 @('4. ENTER POOL', 'Only when drained', 'Crouch Use: loot', 'Hub button below') @('execute if block -24 79 0 minecraft:air run tp @s -23.5 78 0.5 0 20') 'blue'
Add-ArenaButton -28 81 -6 @('REFILL LAVA', 'After recovering', 'Never while inside', 'Ready next test') @('fill -25 78 -2 -21 79 2 minecraft:lava') 'red'
Add-ArenaButton -23 80 3 @('RETURN TO HUB', 'Keeps your loot', 'Then check', 'after transfer') @('tp @s 0.5 81 6.5 180 0') 'black' 'north'
Add-ArenaSign -16 81 -5 @('Lava can down you', 'Give Up / bleedout', 'creates the corpse', 'No instant kill')

# Accessories use ordinary non-terminal damage through the normal downed/death
# path. This is intentionally separate from the environmental hazard stations.
Add-ArenaTitle 23.5 85 -5.5 'ACCESSORIES / NORMAL DEATH' 'green'
Add-AccessoryPrep 15 81 5 'accessory'
Add-ArenaButton 20 81 5 @('2. NORMAL DAMAGE', 'Triggers downed', 'Hold G to give up', 'Then respawn') @('damage @s 1000 minecraft:generic') 'red'
Add-ArenaButton 25 81 5 @('3. CHECK CORPSE', 'Before looting', 'Crouch Use: loot', 'Transfer Items') @("ragrevivalmanual accessories-check $Player after_death") 'blue'
Add-ArenaButton 29 81 5 @('4. CHECK LOOT', 'After transfer', 'Counts all slots', 'Read chat/report') @("ragrevivalmanual accessories-check $Player after_transfer") 'blue'
Add-ArenaSign 23 81 -5 @('H: Accessories', 'Hat + scarf slots', 'Transfer then check', 'worn / inventory')

# A narrow center hole remains open all the way through minimum build height.
# The recovery ring never catches a centered first fall. After actual void death,
# Corpse's default void protection keeps the body at Y=-64 beside this ring.
Add-ArenaCommand 'fill -4 -64 -29 4 79 -21 minecraft:air'
Add-ArenaCommand 'fill -4 -64 -29 4 -64 -21 minecraft:sea_lantern'
Add-ArenaCommand 'fill -1 -64 -26 1 80 -24 minecraft:air'
Add-ArenaCommand 'fill -4 -63 -29 -4 -63 -21 minecraft:glass_pane'
Add-ArenaCommand 'fill 4 -63 -29 4 -63 -21 minecraft:glass_pane'
Add-ArenaCommand 'fill -4 -63 -29 4 -63 -29 minecraft:glass_pane'
Add-ArenaCommand 'fill -4 -63 -21 4 -63 -21 minecraft:glass_pane'
Add-ArenaTitle 0.5 85 -30 'VOID / REAL FALL' 'light_purple'
Add-AccessoryPrep -6 81 -19 'void'
Add-ArenaButton -2 81 -19 @('2. VOID DROP', 'Real fall + death', 'No kill command', 'Respawn at hub') @('tp @s 0.5 82 -24.5 0 65') 'red'
Add-ArenaButton 3 81 -19 @('3. LOWER RING', 'After respawn', 'Body at Y=-64', 'Crouch Use: loot') @('tp @s 2.5 -63 -24.5 90 30') 'purple'
Add-ArenaButton 3 -63 -23 @('RETURN TO HUB', 'Keep recovered kit', 'Then check', 'after transfer') @('tp @s 0.5 81 6.5 180 0')
Add-ArenaSign -3 -63 -27 @('VOID RECOVERY', 'Do not step in hole', 'Crouch Use: loot', 'Empty Use: drag')
Add-ArenaSign 6 81 -27 @('Default Corpse', 'void protection ON', 'Body stays below', 'Use LOWER RING')

if ($TeleportPlayer) {
    Add-ArenaCommand "execute if entity $Player run gamemode survival $Player"
    Add-ArenaCommand "execute if entity $Player run tp $Player 0.5 81 6.5 180 0"
}
Add-ArenaCommand 'forceload remove -32 -32 32 16'

New-Item -ItemType Directory -Path $arenaOutputPath -Force | Out-Null
$arenaCommandPath = Join-Path $arenaOutputPath 'arena-commands.mcfunction'
$arenaCommands | Set-Content -LiteralPath $arenaCommandPath -Encoding utf8NoBOM
$arenaManifest = [ordered]@{
    minecraft = '1.21.1'; dimension = 'minecraft:overworld'; player = $Player
    hub = @{ feet = @(0.5,81,6.5); yaw = 180; pitch = 0 }
    lava = @{ entry = @(-23.5,80,0.5); recovery = @(-23.5,78,0.5) }
    accessories = @{ pad = @(23.5,81,0.5); equip = "ragrevivalmanual accessories-equip $Player accessory" }
    void = @{ drop = @(0.5,82,-24.5); recovery = @(2.5,-63,-24.5); nativeClampY = -64 }
    buttons = $arenaButtons.ToArray(); commandCount = $arenaCommands.Count
    requirements = @('Fresh disposable Overworld with minimum Y=-64', 'enable-command-block=true and spawn-protection=0', 'Corpse fallIntoVoid=false (default)', 'Test harness with level-2 ragrevivalmanual commands', 'Clear old worn hat/scarf slots before preparing another run')
    commandFile = $arenaCommandPath; applied = [bool]$Apply
}
$arenaManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $arenaOutputPath 'arena-layout.json') -Encoding utf8NoBOM
if (!$Apply) {
    Write-Output "Prepared $($arenaCommands.Count) commands; no server commands executed. Review $arenaCommandPath"
    Write-Output 'Hub: 0.5 81 6.5, yaw 180. Run again with -Apply after the isolated server is ready.'
    return
}
if (!(Test-Path -LiteralPath $arenaHelperPath -PathType Leaf)) { throw "Missing isolated RCON helper: $arenaHelperPath" }
$arenaTranscript = [Collections.Generic.List[object]]::new()
try {
    for ($index = 0; $index -lt $arenaCommands.Count; $index++) {
        $response = (& $arenaHelperPath $arenaCommands[$index] | Out-String).Trim()
        $arenaTranscript.Add(@{ command = $arenaCommands[$index]; response = $response })
        if ($response -match 'Unknown or incomplete command|Incorrect argument|Expected |Unknown block|Unknown item|Invalid |Unclosed|That position is not loaded|Cannot place blocks outside') {
            throw "Arena command failed: $($arenaCommands[$index]) -- $response"
        }
        if ($index -eq 0) {
            $ready = $false
            for ($attempt = 0; $attempt -lt 80; $attempt++) {
                $loaded = (& $arenaHelperPath 'execute in minecraft:overworld if loaded -32 80 -32 if loaded 32 80 16 run time query gametime' | Out-String)
                if ($loaded -match 'The time is') { $ready = $true; break }
                Start-Sleep -Milliseconds 250
            }
            if (!$ready) { throw 'Arena construction chunks did not load within 20 seconds.' }
        }
    }
} finally {
    # Also remove the temporary construction tickets if a command failed.
    & $arenaHelperPath 'execute in minecraft:overworld run forceload remove -32 -32 32 16' | Out-Null
    $arenaTranscript | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $arenaOutputPath 'arena-install-results.json') -Encoding utf8NoBOM
}
Write-Output "Installed $($arenaCommands.Count) arena commands. Hub: 0.5 81 6.5, facing north (yaw 180)."
