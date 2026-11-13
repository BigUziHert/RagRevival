# Corpse persistence across a real server restart

Clean shutdown and abrupt-stop recovery both pass **26/26 checks** on the final 1.5.1 binary, SHA-256 `c6cebc64113bb55b712aafee9e4d622e6383bbfc5c756e73dc7d80715a34d7dd`. This test closes the actual dedicated Java server and opens the same world in a new Java process. It does not substitute an entity NBT round trip for a server restart.

The isolated world is `C:\Users\caleb\Documents\ChatGPT\RagRevival\.local\corpse-compat\neo265\world`. The user's CurseForge instance was read-only throughout. Runtime versions are Minecraft 1.21.1, NeoForge 21.1.265, Java 21.0.6, Corpse 1.1.22, Sable 2.0.6, Sable Player Ragdolls 0.7.5, Ragdoll Reactions 0.7.0, and Carry On 2.2.6.13.

`CorpseRestartProbe` prepares two actual Corpse entities on a solid platform: an already settled skeleton with sparse visual equipment (shield and chestplate) and a player corpse still using all six native Sable physics parts. Each has uniquely named inventory items, armor/offhand items, extra items, an owner UUID, a death-history UUID, and a six-part pose. Before shutdown, it writes a manifest into that same world, then flushes the world save. The manifest includes complete entity NBT, inventory NBT, equipment slots, exact positions, and the IDs of all six temporary parts.

The clean case sends the ordinary `stop` command. The probe observes that production shutdown retired the active body before the final world save and records the final pose. The abrupt case force-terminates only the verified isolated server PID after a completed save, with one active body and six marked native parts. It does not test an interrupted filesystem write or a loss of data that had never been saved.

After reopening the same directory, the check requires a different Java PID and the original saved run UUID, loads the fixture chunks, and observes 200 server ticks. Its 26 assertions cover:

- Two original corpse entity UUIDs, with no missing or duplicate corpses.
- Exact main, armor, offhand, and extra inventory NBT, including item components.
- Exact equipment display slots, native skeleton state, and death-history records.
- All six persisted limb transforms, with the temporary active flag cleared.
- Original X/Z positions within 0.02 blocks, floor-safe Y within 0.15 blocks, and no new physics allocation.
- No old native parts in either loaded or held sublevel storage, no orphan ragdoll topology, and no duplicate fixture item drops.

## Baseline failures found and retained

The original 1.5.0 binary (`0c8759315eec01f9d9608df0f106d779a328b4bb5633990a8bb20c12add525da`) passed 25/26 clean-restart checks. It preserved inventory and corpse identity but compacted sparse equipment slots on reload: shield and chestplate moved into the first two display slots. A fully equipped corpse passed, which is why a sparse-equipment case matters. Abrupt-stop recovery also showed position drift while saved temporary parts were loading; inventory, pose, history, and eventual orphan cleanup still passed.

Intermediate 1.5.1 binary `6343bd585ff72c80ded029ef3dec6de15f0e642f3ad7caae05855770f4d80ff9` fixed sparse equipment and passed clean restart 26/26 (PID 519216 to 522080, run `3e4cddc2-2abd-48ce-ada4-4caaac2ca4dc`). Its abrupt case retained a position failure (25/26, PID 522080 to 519304): the saved active corpse moved from `(10.4997523, 239.9840220, 1.3004128)` to `(10.7524392, 240, 1.9320417)` before the first post-start command. The original 69-check Corpse regression passed on this binary.

## Final process-boundary results

The targeted own-part collision filter fixes the remaining abrupt-restart drift. The failure assertions remained unchanged.

| Case | Prior PID | New PID | Persisted run UUID | Checks |
| --- | ---: | ---: | --- | ---: |
| Ordinary server stop | 519948 | 523860 | `c4d56f40-792b-4f3b-9bbf-625186b6b87b` | 26/26 |
| Forced termination after completed save | 523860 | 521924 | `47b3c3f9-5fc9-4184-ad08-e772c47fd4f3` | 26/26 |

The active corpse's X/Z coordinates are exactly unchanged in both final cases. Clean shutdown changes Y from `240.001` to `240.0` as the corpse rests on its platform. Abrupt recovery raises the saved active position from `239.97675289103847` to `240.0` to clear the native limb collider's small floor penetration. Both sparse and full equipment retain their exact display slots. Both authoritative corpse entities retain all inventory NBT and pose transforms, and all six pre-shutdown native parts and their topology records are absent after recovery.

Evidence: [runtime hashes, world, processes, manifests, and fixture IDs](corpse-restart-runtime-1.5.1.json), [clean preparation](corpse-restart-1.5.1-clean-before.log), [clean reopened-world checks](corpse-restart-1.5.1-clean-after.log), [abrupt preparation](corpse-restart-1.5.1-crash-before.log), and [abrupt reopened-world checks](corpse-restart-1.5.1-crash-after.log). Full raw logs and binary/JSON manifests remain under `.local/corpse-compat/restart-final-1.5.1`.

The [original compatibility regression](corpse-checks-1.5.1-neoforge-265.log) also passed **69/69 checks** in 957 server ticks on this same final binary, including real falls into lava and the void, native loot transfer, 120-corpse budget stress, temporary-part cleanup, and floor retirement/reload. The owned isolated server (PID 521924) was then stopped cleanly.

## Reproduction and scope

With the optional test harness installed in the isolated server, run `ragrevivaltest corpse-restart-prepare`, wait for `RAGREVIVAL_RESTART READY`, issue `stop`, restart the same server/world, and run `ragrevivaltest corpse-restart-check`. For the abrupt case, use `corpse-crash-prepare`, wait for the completed save and active-body status, terminate only the verified owned test process, restart, then use the same check command.

This exercises real process boundaries, native world/entity/sublevel saves, native Sable physics, and native Corpse history/inventories. It is a focused integration runtime using the pack's exact relevant mod versions. It does not claim a full-modpack world playthrough, client reconnection testing for this patch, interrupted-disk-write recovery, or exhaustive interactions with every mod. Ordinary distance-driven chunk unloading was not separately exercised in this restart probe.
