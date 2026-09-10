# RagRevival 1.3.6 verification

This build removes the crouch requirement for teammate feeding and dragging.
Both actions require continuous Use input; releasing Use now releases the drag.
Changing between standing and crouching does not cancel either interaction.
Both hands must still be empty for dragging; a revival item in either hand
selects feeding. The HUD displays only the rebindable Use key for these actions.

## Build checks

- `gradlew.bat build testModJar` passed using Java 21.
- All 11 JUnit tests passed: five dependency metadata checks, two clock checks,
  and four hold/heartbeat checks; no failures, errors, or skips.
- Updated multiplayer harness compiled successfully. It was not run for this
  build; physical input and gameplay verification are left to the user.
- Production JAR SHA-256:
  `93dd8603744b82d6d1b95d7cbe1ffe3a0333a00cf88dedb89fc860bd00bcf860`.

The multiplayer harness assertions were updated for standing acquisition,
posture changes during active interactions, explicit release, and heartbeat
timeout. Compiling that harness is separate from running it. The previous
[1.3.5 compatibility matrix](test-results-1.3.5.md) is historical evidence;
its 720 checks were not rerun for this build.

## Manual test environment startup

On September 10, 2026, the isolated `runtime-248` dedicated server and both
separate clients started with RagRevival 1.3.6, NeoForge 21.1.248, Sable 2.0.5,
Sable: Ragdolls 0.7.2, Ragdoll Reactions 0.7.0, and Carry On 2.2.6.13.
Both clients also have Unlocked Camera 1.0.0. Installed RagRevival checksums
match the versioned production artifact.

The server log confirms `ReviveOne` and `ReviveTwo` joined the same server at
`127.0.0.1:25585` and completed Sable UDP authentication. These are startup and
connection checks, not confirmation of physical drag/feed input behavior.
The clients still log Sable's known optional `create:flywheel` physics-property
message; it did not prevent either client joining.

## Manual two-player checks

1. Knock one player down with lethal ordinary damage (not `/kill`).
2. With both rescuer hands empty, stand and hold Use on the body. Walk while
   dragging, crouch and stand again, then release Use; the body should drop.
3. Repeat the grab starting while crouched, then stand without releasing Use.
4. With a golden apple or carrot, stand and hold Use to feed. Change posture
   during feeding; it should continue. Release Use once to cancel, then finish
   a fresh feed; only successful completion should consume one item.
5. Check that feeding pauses bleed-out and restores half maximum health.
6. Check Unlocked Camera offset targeting and scroll zoom while dragging.
7. Check self-revival, the unchanged G give-up hint/bar, and Carry On exclusion.

The server and both clients need the same new RagRevival JAR to use the new
input behavior. The test harness is not installed in the manual test instances.
