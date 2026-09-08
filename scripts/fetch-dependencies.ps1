param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\.local\deps')
)
$ErrorActionPreference = 'Stop'
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$lockPath = Join-Path $PSScriptRoot '..\docs\dependencies.lock.json'
$dependencyLock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null

foreach ($dependency in $dependencyLock.dependencies) {
    # Optional integrations are compile-only in the distributable, but their API JAR is needed to build it.
    if ($dependency.scope -notin @('required', 'optional-compile')) { continue }
    if ([System.IO.Path]::GetFileName($dependency.file) -ne $dependency.file) {
        throw "Dependency lock contains an invalid filename: $($dependency.file)"
    }
    $targetPath = Join-Path $destinationPath $dependency.file
    if ((Test-Path -LiteralPath $targetPath) -and
            ((Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash -ieq $dependency.sha256)) {
        Write-Host "Verified $($dependency.file)"
        continue
    }
    $partialPath = "$targetPath.download"
    try {
        Invoke-WebRequest -Uri $dependency.url -OutFile $partialPath
        $actual = (Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash
        if ($actual -ine $dependency.sha256) {
            throw "SHA-256 mismatch for $($dependency.file): expected $($dependency.sha256), got $actual"
        }
        Move-Item -LiteralPath $partialPath -Destination $targetPath -Force
        Write-Host "Downloaded and verified $($dependency.file)"
    } finally {
        if (Test-Path -LiteralPath $partialPath) { Remove-Item -LiteralPath $partialPath -Force }
    }
}

# Companion is already nested in Sable at runtime. Extract only for the Java compile classpath.
$sablePath = Join-Path $destinationPath 'sable-neoforge-1.21.1-2.0.3.jar'
$companionFile = 'sable-companion-common-1.21.1-1.6.0.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($sablePath)
try {
    $entry = $archive.GetEntry("META-INF/jarjar/$companionFile")
    if ($null -eq $entry) { throw "Sable is missing the expected nested Companion dependency" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $destinationPath $companionFile), $true)
} finally {
    $archive.Dispose()
}
Write-Host "Extracted $companionFile for compilation only; do not install it separately in mods."
