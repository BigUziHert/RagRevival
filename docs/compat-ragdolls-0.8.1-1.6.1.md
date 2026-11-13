# Ragdolls 0.8.1 / Reactions 0.8.0 runtime verification

Tested 2026-11-12 using **RagRevival 1.6.1**, production SHA-256 `c87156b4623c4b4dcc7d4d2734f8788cbb32c1f567015c1644c738ad67c6bc9c`. Every final result below uses that exact production binary. All checks passed, and all owned test processes were stopped afterward.

The runtime used Minecraft 1.21.1, NeoForge 21.1.265, Java 21.0.6, Sable 2.0.6, Corpse 1.1.22, and Carry On 2.2.6.13. The modern pair is Sable Ragdolls **0.8.1** / Ragdoll Reactions **0.8.0**; the regression pair is **0.7.5 / 0.7.0**. Tests ran in `.local/compat-081-080`, on loopback ports 25631/25632 and 25635/25636, with native server-player fixtures and packet sinks. These checks used no GUI or screen capture. The original CurseForge instance and prior test/manual worlds were not modified.

## Final results

| Check | Native pair | Passed | Server ticks | Evidence |
| --- | --- | ---: | ---: | --- |
| Corpse compatibility | 0.8.1 / 0.8.0 | 88/88 | 1,387 | [Log](compat-corpse-1.6.1.log) |
| Limb relocation into another connected physical vessel | 0.8.1 / 0.8.0 | 33/33 | 186 | [Log](compat-relocation-1.6.1.log) |
| Stationary-at-death Sable platform | 0.8.1 / 0.8.0 | 60/60 | 991 | [Log](compat-platform-stationary-1.6.1.log) |
| Moving-at-death Sable platform | 0.8.1 / 0.8.0 | 60/60 | 991 | [Log](compat-platform-moving-1.6.1.log) |
| Actual distance-driven chunk unload and return | 0.8.1 / 0.8.0 | 15/15 | 320 | [Log](compat-chunk-1.6.1.log) |
| Clean full-process restart | 0.8.1 / 0.8.0 | 32/32 | 200 after restart | [Log](compat-restart-clean-1.6.1.log) |
| Forced termination after completed save, then restart | 0.8.1 / 0.8.0 | 32/32 | 200 after restart | [Log](compat-restart-crash-1.6.1.log) |
| Old saved world upgraded to the modern pair | 0.7.5 / 0.7.0 → 0.8.1 / 0.8.0 | 32/32 | 200 after upgrade | [Log](compat-upgrade-1.6.1.log) |
| Corpse compatibility with the older native APIs | 0.7.5 / 0.7.0 | 88/88 | 1,387 | [Log](compat-old-pair-1.6.1.log) |
| Nearby-player revival, without Corpse | 0.8.1 / 0.8.0 | 125/125 | 917 | [Log](compat-nearby-1.6.1.log) |
| Revival items, permissions, and protection, without Corpse | 0.8.1 / 0.8.0 | 81/81 | 376 | [Log](compat-features-1.6.1.log) |

The [machine-readable report](compat-ragdolls-0.8.1-1.6.1.json) records all jar and harness hashes, per-run process identities, summaries, manifests, and raw-log hashes. The full raw evidence remains in `.local/compat-081-080/evidence`.

## What the checks exercise

The Corpse suite covers real falls into lava and the void, inventory conservation and native transfer, owner/skeleton permissions, native expiry, Carry On pickup rejection, long-lived articulated physics, rest followed by a real limb impulse, native grab packets and spring movement, grip release, entity reload, and cleanup. Twelve corpses retain all 72 native parts beyond the obsolete timer/cap settings. The body remains physical after 420 ticks of rest and responds to perturbation.

The relocation fixture moves a limb with Sable's real block-moving operation to an off-center position on another connected physical vessel. It verifies that logical limb identity survives while the physical sublevel and block position change; synchronized pose and interaction bounds follow the actual limb. Native grabbing, release, Corpse inventory access, exact item transfer, and removal remain functional. Removing the corpse leaves every unrelated vessel block intact.

The platform tests compare the corpse with an ordinary native playerless ragdoll. They verify unchanged native part identities, rest and impulse response, translation, yaw, pitch, support removal, inventory preservation, and independent cleanup. In the final moving run, removal of the supporting platform drops the corpse from Y=98.0450 to 92.6448 and the native control from Y=98.2318 to 92.8753 within twenty ticks. No platform assertion was relaxed.

The chunk test moves a registered observer outside view distance using the engine's normal player chunk-tracker update. The actual world chunk unloads, the corpse leaves active entity storage, and all six limbs leave active sublevel storage. A plain vanilla entity control also unloads. Returning restores the original corpse, inventory, articulated physics, and six limbs. The test does not explicitly request chunk unloading or remove the corpse during observation.

The no-Corpse suites confirm optional-mod startup and core revival behavior: overlapping/adjacent downed players, timed self-feeding and teammate feeding, exact item consumption, ordinary ragdoll expiry, seat/neighbor isolation, logout, terminal death, XP deductions, real splash-potion impacts, command permissions, mob grace, and environmental/player damage behavior.

## Real restart and upgrade proof

| Case | Before PID | After PID | Persisted run UUID |
| --- | ---: | ---: | --- |
| Clean restart | 7820 | 10396 | `7e61e081-1d8c-4fe1-aaa0-98ee2efaadd2` |
| Forced restart after save | 10396 | 38236 | `50185f60-071d-4a39-a3cc-0d3f30b3cb74` |
| Old-world upgrade | 33892 | 33484 | `22680855-15c5-4f6b-8c88-aaa4989c9664` |

The first two cases reopen `.local/compat-081-080/server/world-final-081-080`. The upgrade case reopens `.local/compat-081-080/server/legacy-world-final` at the identical path. That world was copied from the preserved 0.7.5 test world, then opened with the unchanged RagRevival 1.6.0 release and old 0.7.5 / 0.7.0 native mods to prepare and save fresh old-format fixtures. After a clean stop, only the copied runtime's jars were upgraded to the final 1.6.1 binary and 0.8.1 / 0.8.0 pair. The source world stayed unchanged; matching source/copy hashes are recorded before preparation.

Each case saves a rested skeleton with sparse equipment and an active player corpse. A real limb impulse produces nonzero saved motion. The manifests record full inventory NBT and item components, display equipment slots, entity/owner/death-history identities, world position, all six limb transforms and velocities, and native root/part IDs. The restarted process must load the exact saved pose and position before simulation resumes, retain both original roots and all twelve original parts, preserve inventory/equipment/skeleton state, and avoid duplicate corpses, limbs, or item drops. Subsequent motion must remain within the supporting-position tolerance.

Forced termination is limited to a verified owned Java process after `saveEverything` completes. It does not test recovery of never-saved changes or interruption during filesystem writes. Final primary PID 33484, old-pair control PID 10144, and modern no-Corpse control PID 32912 were all stopped cleanly and verified absent.

## Diagnostic runs retained

- The unchanged 1.6.0 release rejects Reactions 0.8.0 through its exact 0.7.0 dependency requirement. Its baseline loader failure is preserved separately.
- The first migrated candidate passed 86/88 corpse checks; known removed roots waited too long to recreate physics after entity reload. The final build passes both reload checks without extending their deadlines.
- Running mob/food fixtures on Peaceful difficulty invalidated those fixtures through ordinary despawning and hunger recovery. Normal difficulty with natural mob spawning disabled passes all feature checks.
- Initial nearby tests shared a world with earlier corpse fixtures. Fresh no-Corpse old/new controls both pass 125/125, and a fresh Corpse-present run passes every feeding assertion. That older core suite still reports two terminal-cleanup expectation failures with Corpse present because it expects the native body/seat to disappear; the Corpse handoff retains them. Those diagnostic failures are not relabeled as passes. The dedicated Corpse suite separately verifies the handoff and final body cleanup.
- An early relocation fixture placed its limb across a one-block air gap. Sable split it into a third physical body. Canonical block-entity diagnostics proved the compatibility correctly followed that new body, while the fixture still expected its original vessel. A real connecting stone block fixes the intended fixture without changing the relocation assertions; all 33 then pass.
- One earlier mixed-world moving-platform run failed only the ordinary native control's fall assertion. The final fresh-world run records both trajectories and passes the unchanged assertion.

The old-pair regression uses the preserved, unmodified 1.6.0 test harness compiled for the old native APIs. No-Corpse runs use a test-only jar with optional Corpse/Accessories probe subscribers omitted; the retained core test bytecode is unchanged. These harness variants and their hashes are explicit in the JSON report.

## Limits

This is a focused dedicated-server integration matrix, not a complete modpack playthrough or an exhaustive test of every possible vessel/contact configuration. Full-pack launch, client controls/rendering, and real Accessories checks are recorded separately. The simultaneous physics case contains twelve bodies; it is not a performance benchmark for hundreds of loaded ragdolls. Native rest is measured through transforms and velocities, not an exposed native sleeping-state flag.
