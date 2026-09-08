#requires -Version 7.0
[CmdletBinding()]
param([string]$ModJar)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $repo '.local'
if (!$ModJar) {
    $ModJar = Get-ChildItem -LiteralPath "$repo/build/libs" -Filter '*.jar' | Where-Object Name -NotMatch '(sources|javadoc|test-harness)' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (!$ModJar -or !(Test-Path -LiteralPath $ModJar)) { throw 'Build the mod first or supply -ModJar.' }
$required = @('sable-neoforge-*.jar','sable_player_ragdoll-*.jar','ragdoll_reactions-*.jar')
$dependencies = @()
foreach ($pattern in $required) {
    $matches = @(Get-ChildItem -LiteralPath "$runtime/deps" -Filter $pattern | Where-Object Name -NotMatch '(sources|javadoc)')
    if ($matches.Count -ne 1) { throw "Expected exactly one $pattern in .local/deps, found $($matches.Count)." }
    $dependencies += $matches[0]
}
$optional = @(Get-ChildItem -LiteralPath "$runtime/deps" -Filter '*.jar' | Where-Object Name -Match '^(carryon|unlocked[_-]?camera)' | Where-Object Name -NotMatch '(sources|javadoc)')
foreach ($instance in @('server','client-one','client-two')) {
    $mods = Join-Path $runtime "$instance/mods"
    New-Item -ItemType Directory -Force -Path $mods | Out-Null
    # Only retire earlier builds of this mod; do not touch unrelated jars.
    Get-ChildItem -LiteralPath $mods -Filter 'ragrevival-*.jar' | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    Copy-Item -LiteralPath $ModJar -Destination $mods
    foreach ($dependency in $dependencies) { Copy-Item -LiteralPath $dependency.FullName -Destination $mods -Force }
    foreach ($dependency in $optional) {
        if ($instance -eq 'server' -and $dependency.Name -match '^unlocked') { continue }
        Copy-Item -LiteralPath $dependency.FullName -Destination $mods -Force
    }
    Write-Host "$instance mods: $((Get-ChildItem -LiteralPath $mods -Filter '*.jar').Name -join ', ')"
}
