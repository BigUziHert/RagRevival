# Compatibility update 1.6.1

The supplied Sable Ragdolls 0.8.1 and Ragdoll Reactions 0.8.0 require a RagRevival update. Version 1.6.0's exact Reactions 0.7.0 loader requirement rejects the new pair; widening that range alone is insufficient because Ragdolls removed and changed native APIs.

All checks use isolated copies under `.local/compat-081-080` and `.local/fullpack-compat-081-080`. The original CurseForge instance is read-only input and is never used as a game directory. The previous manual test world is preserved.

## Exact input versions

| Component | Version | SHA-256 |
| --- | --- | --- |
| Sable Ragdolls | 0.8.1 | `52231de8cf20713040e948ec41c0813d8b1eab2e7ec327c1ff5da8bdc3ba33fd` |
| Ragdoll Reactions | 0.8.0 | `aeaaa51c368e7a41589e329762ea87c4f8db258e314c847b84b185b08c285ab3` |
| Sable | 2.0.6 | `9c199157843bf953a47dff625fc5bb41c01a90538d35ad18e6996bbcb8a617c2` |
| Corpse | 1.1.22 | `3733d86452b9ac572e924c2946e15e6b7052cc8fc990911f1c79b36ac3651b9f` |
| Carry On | 2.2.6.13 | `d4f9576b6593db9486a89d35c1d84c31ee89141d99dbae2aa470350ab53c4e09` |

Runtime checks use Minecraft 1.21.1, NeoForge 21.1.265 and Java 21. The reproducible default build remains NeoForge 21.1.249 with the older dependency profile. The modern build uses `./gradlew.bat build testModJar -PragrevivalDepsDir=.local/deps-081-080`.

## Changes

- Permit Reactions versions in `[0.7.0,0.9)`, preserving required installation and load ordering. Versions beyond those actually tested are not guaranteed compatible.
- Resolve the old and new native APIs through one adapter. Ragdolls 0.8.1 stores session, ownership, lifetime and topology on limb block entities instead of `RagdollSavedData`.
- Upgrade RagRevival-marked old corpse limbs before native restoration, retaining their identity and assembly data.
- Track logical limb identity separately from the physical Sable sublevel containing that block. Pose capture, rendering and cleanup use each limb's actual block position, including after relocation onto another vessel.
- Reserve saved active bodies while their native chunks are loading. Explicitly removed bodies are eligible for immediate reconstruction from their saved pose.
- Cache native block discovery per level and tick; do not scan every loaded block separately for each limb query.

Corpse remains the authoritative owner of its inventory, access rules, death history, aging and despawn behavior. Loaded bodies remain native physics ragdolls with normal grabbing and sleeping. This change does not impose a corpse count limit or remove their physics after a timeout.

## Independent full-pack startup blocker

The unmodified copied client crashes with **EMF Attachments Compat 1.2.11** against Ragdolls 0.8.1:

```text
emfattachments.mixins.json:SablePlayerEntityCullingMixin
emfattachments$keepSubLevelPlayersTicking(...): failed injection check (0/1), Scanned 0 target(s)
```

The same exception occurs after removing both RagRevival and its test harness from the copied client. This establishes that the error is independent of this compatibility patch. Client checks therefore temporarily exclude only that copied addon; all original files remain unchanged. The full pack requires a compatible EMF Attachments version or its removal before it can start with the new Ragdolls version.

The dedicated server excludes the pack's client-only rendering addons as documented in the local runtime's `client-only-exclusions.txt`. No test harness is included in the release JAR.

## Verification notes

Release JAR SHA-256: `c87156b4623c4b4dcc7d4d2734f8788cbb32c1f567015c1644c738ad67c6bc9c`.

| Final-build check | Result |
| --- | --- |
| JUnit loader metadata, timer and compatibility tests | 17/17 |
| Updated pair: corpse suite, including lava, void, loot, native grab and Carry On | 88/88 |
| Older Ragdolls 0.7.5 / Reactions 0.7.0: unchanged legacy corpse harness | 88/88 |
| Off-center limb relocation onto a connected stone vessel | 33/33 |
| Stationary / moving Sable platform physics | 60/60 each |
| Actual chunk unload and reload | 15/15 |
| Clean restart / forced stop after saving | 32/32 each |
| Old released build's saved world upgraded to this exact release JAR | 32/32; original twelve native limbs retained |
| Updated pair without Corpse: nearby multiplayer / revival features | 125/125 and 81/81 |
| Copied pack client: native grip / release | 100/100 held ticks; released afterward |
| Copied pack client: native loot menu packets | Both accessories recovered once; 7/7 diamonds recovered; repeated transfers did not duplicate items |

The server test report and exact runtime manifests are in [the final dependency runtime report](compat-ragdolls-0.8.1-1.6.1.md). All final checks above use the release JAR hash. Client input comes from explicit bounded test-harness requests to Minecraft's Use key and Corpse's own menu packets; inventory is handled by Corpse on the server. There is no operating-system screen capture or monitor-capture border. The optional framebuffer images capture the game itself, not the desktop.

The eleven dedicated-server suites total **646 passing assertions, zero failures**. All automated server and client processes created for this update were closed after verification. The previous manual test environment and original modpack remain preserved.

The initial updated candidate exposed a delayed reconstruction after an explicitly removed corpse body; this was fixed and the full corpse suite subsequently passed 88/88. Clean restart and forced process termination after `save-all flush` each passed 32/32. A genuine old-world upgrade was prepared with released RagRevival 1.6.0, Ragdolls 0.7.5 and Reactions 0.7.0, then reopened at the same copied world path with the new pair: 32/32 passed, retaining the original twelve limb IDs and both inventories. Forced-stop evidence covers data that Minecraft had already saved, not unsaved progress.

Independent fresh no-Corpse controls passed nearby-player checks 125/125 and revival-feature checks 81/81 with both the old pair and the updated pair. A fresh Corpse-present run passed the feeding checks; two old terminal-death assertions expect immediate body/seat removal, while the existing Corpse handoff intentionally retains the body and the upstream detach path leaves an empty seat. These are pre-existing handoff differences, not newly introduced revival failures.

The relocation fixture initially left an air gap between its limb and stone vessel. Sable correctly split that disconnected limb into a third body. Diagnostics confirmed that RagRevival's synchronized pose and lookup followed the new canonical block; the fixture's stale destination-body assumption was wrong. The corrected fixture includes a connecting stone block and keeps the off-center placement, identity, physics, inventory and selective-cleanup assertions.

With the independent EMF addon excluded from the copied client, actual client rendering and a bounded Use-key hold exercised native targeting and networking: 100/100 held ticks reported an active native grip, followed by release; the body moved physically. Real survival deaths in lava and the void preserved uniquely tagged Artifacts items equipped through Accessories in `hat[0]` and `necklace[0]`. Both items appeared once in Corpse's Additional Items with their components intact. Recovery uses Corpse's ordinary inventory semantics, not automatic restoration to accessory slots.

Original input verification checked all **1,314 copied source files** against their initial SHA-256 manifest, with **zero changed or missing files**. Evidence and exact dependency hashes are in [`compatibility-1.6.1/`](compatibility-1.6.1/).
