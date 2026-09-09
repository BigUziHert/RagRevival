# RagRevival 1.3.1 verification

Tested 2026-09-08 on Java 21.0.6, Minecraft 1.21.1, NeoForge 21.1.249,
Sable 2.0.3, Sable Ragdolls 0.7.2, Ragdoll Reactions 0.7.0, and Carry On
2.2.6.13. Both clients also loaded the unchanged Unlocked Camera 1.0.0
private source build at `c13cfa3d`.

Release JAR SHA-256:
`293670bc25bc33abfc0ad210b3462a08b10ad9968d65c54707a598f1027008f2`.

## Results

- `gradlew.bat build testModJar` passed; six JUnit tests passed.
- The dedicated-server suite passed **144 checks, zero failures, 1,439 server
  ticks**, with ReviveOne and ReviveTwo connected in separate real clients.
- Teammate feeding started without native item use. After eight active ticks,
  the rescuer still owned the feed and held the same four apples, with no native
  eating animation. Success still consumed exactly one item; cancellation
  consumed none. Patient-mouth particle and sound code is unchanged.
- Self-revival, offhand use, its guarded eating state, extended feeding duration,
  nutrition/effect suppression, and ordinary eating after cleanup all passed.
- The retained suite covered give-up release/completion and priority during
  feeding, crouch cancellation, target locks, timer pause/death, movement,
  native drag, server reach, inventory/XP rules, totems/void/kill, lifecycle
  callbacks, Carry On exclusion, and packet codecs.
- The production renderer was visually checked in a real Minecraft framebuffer
  with known snapshots: the downed player's two-row card contains both self-use
  and G controls, with no separate G hint or progress bar. Both use the same
  thin green bottom track. A 50% give-up snapshot overrode simultaneous 25%
  feeding progress only on the local downed player's card.

## Method and limits

The gameplay harness invokes real server methods while both clients receive
network and physics updates. The HUD screenshot uses synthetic snapshots passed
to the production renderer, without changing gameplay state. Neither mechanism
simulates physical key/mouse holds. The no-eating check verifies server native
use state; the removal of both native animation starts was also reviewed in
source. Existing patient-mouth effects are preserved without new positioning
or physics changes.

Manual checks: crouch-feed a teammate and verify the rescuer keeps the food held
while crumbs appear on the downed body. Down yourself, check the integrated G
hint, start and cancel self-revival, then briefly hold/release G before completing
a five-second give-up. The same bottom track should reset and fill for the
selected action. Camera gestures and physical audio/animation appearance remain
manual checks. Sable's hidden seated hands and rigid limbs remain as documented
in [eating presentation](eating-animation.md).

Local ignored evidence: `.local/test-1.3.1-passed.log`, Gradle XML results, and
`.local/client-two/screenshots/ragrevival-rescue-hud.png`. Reproduce with
[runtime instructions](runtime.md). The test harness is excluded from the release.

## Runtime left running

The server and both clients were restarted with the versioned 1.3.1 release and
the test harness removed. Installed hashes match the release above. Both
Minecraft windows are responding and report multiplayer; RCON confirmed both
players connected. Required dependencies and Carry On remain everywhere, with
Unlocked Camera on both clients. Apples and carrots are ready for manual checks.

| Process | PID | Address / identity |
| --- | --- | --- |
| Dedicated server | 39852 | `127.0.0.1:25575` |
| Client one | 39448 | `ReviveOne` |
| Client two | 27592 | `ReviveTwo` |
