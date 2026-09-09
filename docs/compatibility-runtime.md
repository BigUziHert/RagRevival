# Reproducing the compatibility matrix

Use PowerShell 7 on Windows with Java 21. First prepare the base Minecraft 1.21.1 installation with `scripts/setup-test-runtime.ps1`; this verifies and downloads the public dependencies, libraries, and assets. Build the selected RagRevival version and optional harness:

```powershell
./gradlew.bat build testModJar
pwsh -File scripts/setup-compatibility-runtime.ps1 -Build 248 -ModVersion 1.3.5 -IncludeHarness
pwsh -File .local/compat/runtime-248/start-runtime.ps1 -Target All
pwsh -File .local/compat/runtime-248/server-command.ps1 'ragrevivaltest run'
```

The setup script only prepares files. Launching is a separate step. Each profile creates two physical client directories with offline operator identities `ReviveOne` and `ReviveTwo`, an independent server world, copied libraries, and its own loopback game/RCON ports. Minecraft assets are shared read-only with the base installation. The script leaves base runtime settings and processes untouched and refuses to overwrite a prepared or previously launched profile.

| `-Build` | NeoForge | Default Sable | Game / RCON ports | Unlocked Camera |
| --- | --- | --- | --- | --- |
| 219 | 21.1.219 | 1.1.1 | 25587 / 25588 | Omitted: minimum NeoForge 21.1.235 |
| 228 | 21.1.228 | 2.0.5 | 25589 / 25590 | Omitted: minimum NeoForge 21.1.235 |
| 248 | 21.1.248 | 2.0.5 | 25585 / 25586 | Clients only, when available locally |
| 250 | 21.1.250 | 2.0.5 | 25591 / 25592 | Clients only, when available locally |

The script downloads the selected Sable version using the URL and SHA256 from [the reviewed API matrix](sable-api-matrix-1.3.5.json), then verifies the official NeoForge installer SHA1. `-SableVersion` can select another reviewed version if its declared NeoForge minimum permits the selected profile. Ragdolls 0.7.2, Reactions 0.7.0, and Carry On 2.2.6.13 are verified against the dependency lock. Carry On's metadata permits all four rows; runtime compatibility must be established by the tests, not by this setup script.

Unlocked Camera is private and is never downloaded automatically. If available, place its built `unlockedcamera-1.0.0.jar` in `.local/deps` or supply `-UnlockedCameraJar`; use `-SkipUnlockedCamera` to omit it. Its existing minimum is preserved. The helper only installs it on clients at NeoForge 21.1.235 or later.

Optional parameters include `-GamePort`, `-RconPort`, and `-JavaHome`. Without `-IncludeHarness`, only the main mod is installed; its versioned artifact can be used if no matching build output exists. With the harness, remove its JAR from all three mod directories after stopping the processes and before ordinary play.

Every profile contains `start-runtime.ps1`, `server-command.ps1`, `runtime.json`, and an initial `installation.json` with installer/mod hashes. The launcher supports `-Target Server`, `ClientOne`, or `ClientTwo`, and `-PrepareOnly` to validate arguments without launching. Servers run hidden and clients open visible game windows. RCON secrets remain in the ignored local `server/server.properties` and are read by the helper without being printed. All profiles, caches, logs, secrets, and worlds stay under ignored `.local/compat`.
