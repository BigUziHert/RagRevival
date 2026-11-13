# Persistent native ragdolls on Sable platforms

Tested 2026-11-11 in `.local/corpse-sublevels-final`, an isolated dedicated server on ports 25613/25614. The user's CurseForge instance was not modified. The test server was stopped after both runs.

Runtime: Minecraft 1.21.1, NeoForge 21.1.265, Sable 2.0.6, Sable Ragdolls 0.7.5, Ragdoll Reactions 0.7.0, Corpse 1.21.1-1.1.22, Carry On 2.2.6.13, RagRevival 1.6.0. Production SHA-256: `ce9ebc2749d7f3086d6ae292323e720bb2954851390d704dc0674be83b90b1c4`.

## Native physics fixtures

`CorpseSublevelProbe` assembles a real 35×35 Sable platform and drives it through native physics constraints. A registered server-player fixture is lethally damaged and enters the real downed state. Terminal death must transfer the **same six native limb UUIDs** to the authoritative Corpse entity. An unrelated ordinary playerless ragdoll, using `PlayerlessDespawnRule.never()`, receives the same per-limb transforms and global linear/angular velocities, translated six metres sideways, as a physical control.

- Stationary-at-death run: **60/60 passed**, 991 server ticks. Exact original limb identities persist beyond the former ten-second cutoff. Both corpse and native control reach zero measured linear/angular velocity without removing any physics. A native single-arm impulse wakes the body and changes limb orientation: 59.21° rotation and 24.91 cm center displacement for the corpse, versus 59.09° and 24.82 cm for its ordinary native control. Subsequent platform translation, 40° yaw, 8° pitch, and support removal preserve articulation and all seven inventory diamonds. Removing the support makes both bodies fall. Deleting the Corpse then releases its six limbs while leaving the unrelated native control intact.
- Moving-at-death run: **60/60 passed**, 991 server ticks. The platform translates fourteen metres at 1 m/s, spanning downed death and continuing past the former physics cutoff. The same rest, impulse, movement, rotation, support-removal, inventory, and cleanup checks run afterward. Both runs measure angular response in the native control as well as the corpse. A single-arm impulse produces 46.41° rotation and 13.25 cm center displacement for the corpse, versus 44.82° and 8.95 cm for its ordinary native control.
- Bounds checks verify that the authoritative movement box contains the complete pose and remains horizontally centered on its anchor. No fixture is allowed to expose Sable's off-world plot coordinates as the corpse location.

## Rendering

`CorpseRagdollRenderer` now resolves each active limb's client sublevel UUID and uses Sable's own `renderPose(partialTick)` center, quaternion, and scale. It subtracts the same interpolated entity anchor used by vanilla/Sable rendering, including support-local interpolation on a moving deck. Saved transforms remain a fallback while native sublevel packets are unavailable. This removes the former world-aligned frozen-pose behavior; loaded corpses retain native articulated motion.

## Rest and wake interpretation

The installed Sable API does not expose an authoritative native sleeping-state query. Tests measure unchanged poses and low/zero velocities, then apply a native impulse and verify actual motion; they do not claim to read a sleep-state flag. No age, distance, or body-budget threshold converts a loaded corpse to a static model.

Native contact may rotate an arm about its support point with less than five centimetres of center displacement. The final impulse assertion accepts meaningful rotation or translation and separately checks angular articulation in both corpse and ordinary native control. A diagnostic moving-deck run measured 3.65° corpse-arm rotation versus 3.94° for the native control; an earlier translation-only minimum incorrectly rejected both.

## Diagnostic findings retained separately

The initial test fixture accidentally retained a large falling velocity before teleporting its fake player to the death point. Recreating that captured articulated state in a new native body produced sustained oscillation. Copying the same state into an ordinary native ragdoll without a Corpse proxy reproduced the oscillation, ruling out the authoritative entity as its source. The final normal-death fixture clears this accidental velocity, and the production death path directly transfers the original native bodies and constraints. Diagnostic logs preserve the earlier observations; they are not the final acceptance runs.

Evidence: `corpse-sublevel-stationary-1.6.0.log` and `corpse-sublevel-moving-1.6.0.log`. Commands: `ragrevivaltest corpse-sublevel` and `ragrevivaltest corpse-sublevel-moving`, using the separate test harness in an empty isolated server. Client input/rendering and process restart checks are documented separately. These platform tests do not claim coverage of every vessel, arbitrary collision speed, or every possible contact arrangement.
