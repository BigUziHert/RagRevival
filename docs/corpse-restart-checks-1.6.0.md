# Native Corpse persistence verification — 1.6.0

The final production binary `ce9ebc2749d7f3086d6ae292323e720bb2954851390d704dc0674be83b90b1c4` passed **88/88** compatibility checks, **32/32** clean process-restart checks, and **32/32** abrupt process-restart checks. Unlike 1.5.1, this version retains the original native Sable bodies and joints across server restarts. A persisted pose is not a permanent replacement for physics.

Runtime: Minecraft 1.21.1, NeoForge 21.1.265, Java 21.0.6, Corpse 1.1.22, Sable 2.0.6, Sable Player Ragdolls 0.7.5, Ragdoll Reactions 0.7.0, and Carry On 2.2.6.13. All tests ran outside the user's read-only CurseForge instance. Earlier 1.5.0/1.5.1 artifacts, worlds, and evidence were preserved.

## Continuous native physics

The [88-check integration run](corpse-checks-1.6.0-neoforge-265.log) completed in 1,387 server ticks. It checks actual deaths in lava and the void, native inventory transfer and expiry, native skeleton permissions, and these new behavior requirements:

- Twelve corpses retain all 72 native parts beyond thirteen seconds while the obsolete cap/timer settings are deliberately set to two bodies and twenty ticks.
- A body remains physical after 420 ticks of rest. A real native limb velocity impulse changes its position and articulated transforms.
- The ordinary Sable grab packet rejects another player under owner-only access, permits skeleton access, rejects an occupied main hand and an out-of-reach request, and accepts the owner.
- Native spring dragging moves the body. Release, re-grab, and unloading a held corpse clean up the grip correctly.
- Entity unload/reload preserves loot, resumes physics, and removes stale parts. A captured downed body remains physical even with the obsolete cap set to zero.
- Carry On's actual entity-pickup handler reaches its event callback and is canceled without copying the corpse, moving inventory, removing the entity, or dropping items. The marked native limb's block-break event is also canceled without altering that limb.

These are real server-side native physics and packet-handler checks using registered `ServerPlayer` fixtures with packet sinks. Client mouse-input verification is separate.

The Carry On entity test uses Creative mode because its ordinary Survival size gate rejects the native two-block-wide Corpse before invoking the event callback. The test must reach that callback to prove the compatibility guard itself works. The native limb test invokes the same break event directly; it does not claim to execute Carry On's block-pickup handler against the remote Sable plot coordinates.

## Full process restarts

Both runs reopen the exact same world directory: `C:\Users\caleb\Documents\ChatGPT\RagRevival\.local\corpse-compat\neo265\world-native-1.6.0-carryon`.

| Case | Prior PID | Reopened PID | Persisted run UUID | Checks |
| --- | ---: | ---: | --- | ---: |
| Ordinary `stop` | 506332 | 516820 | `bc951e96-4d19-4149-bca2-d7393c8f6e02` | 32/32 |
| Forced termination after a completed save | 516820 | 511204 | `e8d030c0-6abb-4323-bd21-563b60cba122` | 32/32 |

Each run contains a rested skeleton with sparse equipment and a full-equipment player corpse. Both use native physics, with twelve parts total. One limb receives a real impulse before the save, and the probe verifies nonzero saved limb velocity. The world manifest records entity UUIDs, death-history UUIDs, complete inventory NBT, equipment slots, world positions, all limb transforms and velocities, and original native part/root IDs.

Clean shutdown clears Java runtime bindings while retaining all twelve native world parts for normal Sable persistence. The abrupt test kills only the verified isolated Java PID after `saveEverything` has completed; the original body parts are already saved. It does not test interrupted filesystem writes or recovery of never-saved data.

On reopen, the probe proves a different Java process and the same persisted world/run UUID. An entity-join listener records exact saved positions, transforms, and velocities before resumed physics. After 200 ticks it requires unchanged inventories, equipment slots, entity/owner/history identities, skeleton state, and a valid articulated pose. Both original native roots and all twelve original parts must be adopted, with six parts per body and no duplicates. The native bodies must settle within 0.25 blocks of their saved supporting positions. Final observed displacement was below 0.001 blocks in both cases.

This caught an intermediate implementation that spawned replacement native bodies alongside old saved parts: structural persistence passed, but the recorded trajectories showed roughly one block of unwanted movement. Retaining and adopting the original bodies removed that movement; the final stricter position guard passes.

Evidence: [exact runtime hashes and process/manifests](corpse-restart-runtime-1.6.0.json), [clean preparation](corpse-restart-1.6.0-clean-before.log), [clean reopened-world checks](corpse-restart-1.6.0-clean-after.log), [abrupt preparation](corpse-restart-1.6.0-crash-before.log), and [abrupt reopened-world checks](corpse-restart-1.6.0-crash-after.log). Full raw logs and manifests are retained in `.local/corpse-compat/native-carryon-1.6.0`; earlier candidate evidence is preserved in separate directories.

## Scope

The separate [distance-driven chunk test](corpse-chunk-checks-1.6.0.md) verifies actual world-chunk unloading and native-body inactivity/resumption. These focused integration checks use the pack's exact relevant mod versions; they do not claim a full-modpack playthrough, exhaustive interactions with every mod, or a hundreds-of-active-bodies performance benchmark. The simultaneous native-physics test contains twelve bodies, not the mostly-frozen 120-corpse stress case from the previous version.
