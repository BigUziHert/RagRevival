# Local two-player test runtime

The Windows PowerShell 7 scripts keep every installation, dependency, configuration,
log, process record, and test world inside ignored `.local/`. They do not use or
change Minecraft launcher accounts. Java 21 is required; this machine has
`C:\Program Files\Java\jdk-21`.

```powershell
pwsh -File scripts/setup-test-runtime.ps1
# Setup fetches the three required dependency jars from docs/dependencies.lock.json.
# Optional: Carry On jar in .local/deps; Unlocked Camera jar in .local/deps (clients only).
./gradlew.bat build
pwsh -File scripts/sync-test-mods.ps1
pwsh -File scripts/start-test-runtime.ps1
```

Install RagRevival **1.3.0** on all three instances together. Its network protocol
is version 3, so older clients and servers must be updated to match.

NeoForge is pinned to **21.1.249** on Minecraft **1.21.1**. Setup uses the official
NeoForge installer and Mojang assets, verifies Mojang SHA-1 hashes, and copies
matching existing asset-cache files when available. Common assets/libraries live
in `.local/launcher`; game directories, options, mods, natives, and logs are separate:

| Instance | Directory | Identity / address |
| --- | --- | --- |
| Dedicated server | `.local/server` | `127.0.0.1:25575` |
| First client | `.local/client-one` | `ReviveOne` |
| Second client | `.local/client-two` | `ReviveTwo` |

Both client identities have deterministic offline UUIDs and operator access in
this private development world. The offline server is bound to loopback and is
not intended for public hosting. Both clients connect automatically through
Minecraft Quick Play. Each process has a 4 GiB maximum heap, a 6-chunk view distance,
and its own console logs. The server runs hidden; both game windows remain visible.

`start-test-runtime.ps1 -Target Server`, `-Target ClientOne`, or `-Target ClientTwo`
starts an individual instance. `-PrepareOnly` validates installed libraries and
writes Java argument files without launching. Already-running recorded processes
are skipped. The local RCON password is randomly generated and remains only in
ignored server properties. Run administrative commands without displaying it:

```powershell
pwsh -File scripts/test-server-command.ps1 'list'
pwsh -File scripts/test-server-command.ps1 'give ReviveOne minecraft:golden_apple 16'
pwsh -File scripts/test-server-command.ps1 'give ReviveTwo minecraft:golden_apple 16'
pwsh -File scripts/test-server-command.ps1 'give ReviveOne minecraft:golden_carrot 16'
pwsh -File scripts/test-server-command.ps1 'give ReviveTwo minecraft:golden_carrot 16'
pwsh -File scripts/test-server-command.ps1 'tp ReviveTwo ReviveOne'
pwsh -File scripts/test-server-command.ps1 'save-all flush'
pwsh -File scripts/test-server-command.ps1 'stop'
```

Close both clients and stop the server before replacing mod jars. `sync-test-mods.ps1`
copies only the three required mods, RagRevival, Carry On if present, and Unlocked
Camera if present. It never installs the compile-only extracted Sable companion
jar because Sable already bundles that dependency.

Live interactive behavior needs two people or switching between both windows.
The task's final report distinguishes compilation/startup checks from feeding,
movement, camera, dragging, and key-hold interactions actually exercised.
For a quick manual check, hold crouch + Use to feed a teammate, then release
crouch to check cancellation. While downed, hold Use without crouching to
self-revive. Observe the eating animation, food crumbs/sounds at the downed head,
paused countdown, and green progress bar; finish to check one-item consumption
and half maximum health. Sable's physical limb poses are unchanged.

## Optional integration harness

The test harness is a separate source set and JAR; it is never packaged in the
distributable mod. It changes the two test players' inventories, health, and
positions, so run it only in the isolated test world. With the server stopped:

```powershell
./gradlew.bat testModJar
Copy-Item build/libs/ragrevival-1.21.1-1.3.0-test-harness.jar .local/server/mods/
pwsh -File scripts/start-test-runtime.ps1
# After both clients join, leave them idle:
pwsh -File scripts/test-server-command.ps1 'ragrevivaltest run'
pwsh -File scripts/test-server-command.ps1 'ragrevivaltest codec'
# Test-only direct revival, useful for checking outline cleanup after a screenshot:
pwsh -File scripts/test-server-command.ps1 'ragrevivaltest revive ReviveOne'
```

The scheduled tests exercise real dedicated-server APIs and native Sable physics
while both clients receive synchronization. Results are logged as
`RAGREVIVAL_TEST PASS/FAIL`. Tests include death interception, item/XP game rules,
teammate crouch requirements, self-revival, feeding cancellation/completion,
native-use animation state, native drag release, dismount locking, movement input,
give-up timing, distinct ordinary ragdolls, lifecycle callbacks, and damage edge
cases. The codec command can run separately. See [1.3.0 results](test-results-1.3.0.md)
for the latest executed checks and [verification history](test-results.md) for
earlier versions, including independent real reconnection/restart checks.

For the optional client geometry probe, also install the harness JAR into the
client mod directories before launching. Put the text `ReviveOne` into
`.local/client-two/ragrevival-geometry.request` while that player is downed and
nearby. The probe writes a geometry JSON report and a Minecraft-rendered screenshot
inside that client directory. Synthetic camera rays are geometry checks; actual
mouse targeting with Unlocked Camera still needs the manual checklist. Remove the
test harness from all instances after stopping them and before normal play.

For a screenshot without requiring a downed target, write a short filename label
such as `outline-wall` into `.local/client-two/ragrevival-visual.request`. The
test-only client probe captures the actual framebuffer into
`screenshots/ragrevival-outline-wall.png` without moving the camera or sending input.

The special label `rescue-hud` draws the production rescue panel with synthetic
0%, 25%, 50%, and 100% feeding snapshots, plus another rescuer's progress, before
capturing it. This previews layout using the current held items and bound keys;
it does not change gameplay state or simulate holding Use.

For the opt-in scroll regression fixture, install the test harness on both
clients and the server, leave the rescuer idle, and use the isolated test world:

```powershell
# Creates a normal same-profile dummy beside the target, expiring after 20 seconds.
pwsh -File scripts/test-server-command.ps1 'ragrevivaltest scroll-dummy ReviveOne'
pwsh -File scripts/test-server-command.ps1 'damage ReviveOne 100 minecraft:generic'
Set-Content .local/client-two/ragrevival-scroll.request ReviveOne
```

With Unlocked Camera loaded, the fixture posts synthetic wheel events through
the actual client event bus and checks camera distance, native grab ownership,
collision suppression, cancellation, and the hotbar gate. Results are written
to `.local/client-two/ragrevival-scroll.json`. Temporary camera/config/input
state is restored before the next tick. Ordinary-grip takeover tests emit
native release packets for their temporary fixture grips, so run only on an idle
test client. This checks event handling, not a physical mouse-wheel gesture.
