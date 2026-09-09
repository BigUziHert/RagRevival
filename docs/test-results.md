# Verification evidence

This page records the original **1.0.0** release. See [1.1.0 verification](test-results-1.1.0.md)
for chat/outline/percentage health and [1.1.1 verification](test-results-1.1.1.md)
for the drag/zoom fix. [1.2.0 verification](test-results-1.2.0.md) covers golden
carrots, compact prompts, and feeding pause. See [1.2.1 verification](test-results-1.2.1.md)
for the combined rescue prompt and bottom-edge revival bar. See
[1.3.0 verification](test-results-1.3.0.md) for teammate crouch requirements,
self-revival and eating animation. See [1.3.1 verification](test-results-1.3.1.md)
for teammate held-food presentation, the combined give-up card, and the latest
test runtime.

Final live dedicated-server run: 2026-09-08, Minecraft 1.21.1, NeoForge 21.1.249, Java 21.0.6.
Installed main JAR SHA-256: `0b8639ce9f6c86f2c7b13694f563f3787ae23263d41933e8919f8d8b88031e1b`.

Two separately launched real clients, ReviveOne and ReviveTwo, connected over loopback. Required mods and Carry On were installed on server and both clients; Unlocked Camera was installed on both clients. The opt-in test harness was installed for this run.

Result: **50 passed, 0 failed in 556 server ticks**.

Build verification: `gradlew.bat build` passed with Java 21. Six JUnit tests passed for persistent deadline arithmetic, hold reset, heartbeat timeout, duplicate packet resistance, and stalled-server hold accounting. The three packet-codec checks also passed in a real NeoForge server; [separate optional-dependency and codec evidence](optional-dependency-startup.md) records that run. Script syntax and staged diff checks passed. The versioned release JAR contains no test harness or third-party dependency classes.

The harness invokes actual server gameplay APIs while both clients receive state and physics synchronization. The target-lock contention check uses a synthetic third server actor. Login/logout checks in this suite invoke lifecycle callbacks; independent real reconnection/restart checks are recorded below. Physical mouse/key holds and Unlocked Camera user interaction are not established by these server API checks.

During harness development, fixture issues were found and corrected: rebinding the connection after server-driven respawn, waiting out vanilla spawn immunity, and preventing ambient flat-world slimes from interrupting feeding. The final run was performed after those fixture conditions were satisfied.

- PASS: lethal damage enters downed state
- PASS: downing prevents normal death
- PASS: inventory and XP remain intact when downed
- PASS: existing mob target is cleared
- PASS: downed player mounted in native Sable ragdoll
- PASS: world-space physics body bounds available
- PASS: downed player ignores damage
- PASS: mob cannot acquire downed player as a new target
- PASS: downed dimension transfer is rejected
- PASS: normal player save and NBT serialization preserve exact deadline
- PASS: logout callback retains persisted downed state
- PASS: login callback retains original deadline
- PASS: lifecycle callbacks do not reset elapsed time
- PASS: server can reach real Sable body from nearby player
- PASS: feeding starts for held tagged item
- PASS: duplicate input packets cannot finish feeding instantly
- PASS: target lock rejects simultaneous second server actor
- PASS: releasing feeding cancels without consuming
- PASS: missing input heartbeat expires feeding lease
- PASS: moving out of real server reach cancels without consuming
- PASS: replacing the held stack cancels feeding
- PASS: continuous feeding revives target
- PASS: successful feeding consumes exactly one golden apple
- PASS: successful feeding clears rescue lease
- PASS: revival restores configured health
- PASS: late repeated packets cannot consume again
- PASS: revival releases native Sable seat
- PASS: revival returns actual player from plotyard to body world position
- PASS: crouching empty-handed rescuer acquires native drag
- PASS: releasing crouch releases drag
- PASS: rescuer logout callback cancels feeding
- PASS: rescuer becoming downed cancels feeding
- PASS: releasing G cancels give-up progress
- PASS: give-up does not complete before 100 continuous ticks
- PASS: 100-tick give-up performs terminal death once
- PASS: terminal death respects keepInventory true for items and XP
- PASS: death respawn clears downed state
- PASS: expired server deadline performs terminal death without redowning
- PASS: countdown death drops inventory with keepInventory false
- PASS: countdown death loses XP with keepInventory false
- PASS: ordinary Sable ragdoll remains distinct from downed state
- PASS: lethal damage converts ordinary ragdoll in place
- PASS: downed native seat rejects manual dismount
- PASS: Carry On pickup of downed target is canceled
- PASS: Carry On pickup by downed actor is canceled
- PASS: native movement input preserves downed seat and state
- PASS: native movement input allows observable body motion while downed
- PASS: totem resolves lethal damage before downing
- PASS: void damage performs normal terminal death
- PASS: administrative kill performs normal terminal death

## Real reconnection and restart

A downed ReviveOne was kicked, its client process closed, and a new client process joined under the same offline identity. The exact persisted deadline stayed **1788908106593**; observed remaining time fell from 111.021 seconds to 81.941 seconds.

The dedicated server was then stopped cleanly and all three processes relaunched using the final distributable JAR with the test harness removed. Both clients rejoined. The same deadline was still **1788908106593**, with 17.403 seconds remaining. These are real connection/process restarts, separate from the callback checks above.

## Live client geometry and HUD

Both clients observed six synchronized limb boxes. Four rays from the real player eye and synthetic left/right/raised camera offsets all intersected the real body without an intervening blocker and within the rescuer's actual reach. In the initial aimed capture, the real current-camera picker selected ReviveOne even though vanilla's hit result was a block. A later capture showed the settled prone body; after it moved, the unchanged camera aim correctly selected no target.

Minecraft-rendered screenshots were inspected from `.local/client-one/screenshots/ragrevival-geometry.png` (downed player HUD) and `.local/client-two/screenshots/ragrevival-geometry.png` (visible body). These local images are not shipped with the mod. Unlocked Camera was loaded, but actual offset-mode mouse feeding and visual camera behavior still require manual verification. Synthetic offsets are geometry tests, not a substitute for that interaction.

## Final running runtime

Final main JAR SHA-256: `0b8639ce9f6c86f2c7b13694f563f3787ae23263d41933e8919f8d8b88031e1b`. The same JAR is installed in all three instances.

- Dedicated server: PID 14740, loopback `127.0.0.1:25575`, running.
- ReviveOne: PID 20328, `.local/client-one`, visible Minecraft window and connected.
- ReviveTwo: PID 35420, `.local/client-two`, visible Minecraft window and connected.
- Both players reset healthy, standing two blocks apart, each holding 16 golden apples.
- Required dependencies and Carry On installed on all three. Unlocked Camera installed on both clients. Test harness removed from all three.
- `keepInventory=false`, `doImmediateRespawn=false`, normal difficulty. Natural mob spawning disabled in the isolated test world so flat-world slimes cannot interrupt manual trials; explicit `/summon` still works.

Manual work remains: physical G release/hold behavior and Controls rebinding, sustained right-click cancellation with actual mouse input, crouch-drag movement feel and release, real Unlocked Camera offset targeting, Carry On key interactions, and two human rescuers racing. The automated tests establish the server API behavior and synchronization without asserting these unperformed human-input trials.
