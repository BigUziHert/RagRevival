# Corpse persistence and Sable follow-up — 1.5.1

The original 1.5.0 verification used entity serialization and simulated orphan
cleanup. This follow-up adds actual dedicated-server process shutdown/restart,
forced termination after a completed disk save, and native assembled Sable
platforms. All work and test worlds are outside the supplied CurseForge instance.

## Fixes found by the additional tests

1. An asymmetric collision box made Sable repeatedly convert the box's offset
   into movement. A corpse could drift even on a stationary rotated deck. The
   box now encloses the same saved pose symmetrically around its existing X/Z
   anchor. It also survives Sable's raw position setter through a shared cached
   `makeBoundingBox()` override.
2. Restored temporary physics limbs could push their own corpse before orphan
   cleanup ran. Collision filtering now excludes only a corpse's own marked
   limbs, identified by its UUID. Stale tracking of those limbs is cleared.
   Real platforms, unrelated bodies, and players retain their collision behavior.
3. Corpse's cosmetic equipment serialization omits empty entries, shifting
   sparse armor/off-hand slots after loading. Additional NBT stores explicit
   cosmetic slot indices and restores them through Corpse's synchronized
   equipment field. Actual death inventory, ownership, and history remain in
   the original Corpse implementation.

The existing global active-body cap, timeout, range limit, saved-pose format,
and protocol 4 remain unchanged. Loading a corpse does not allocate another
physics body. The cosmetic fix protects new saves; it cannot reconstruct slot
positions already lost in an older save.

## Test evidence

Final production JAR SHA-256:
`c6cebc64113bb55b712aafee9e4d622e6383bbfc5c756e73dc7d80715a34d7dd`.
The dedicated integration runtime uses Minecraft 1.21.1, NeoForge 21.1.265,
Corpse 1.1.22, Sable 2.0.6, Sable Ragdolls 0.7.5, Reactions 0.7.0,
and Carry On 2.2.6.13. Java build and all 16 existing JUnit checks pass.

- Clean shutdown and same-world restart: **26/26 passed**.
- Forced termination after `save-all flush`, then same-world restart:
  **26/26 passed**, including exact X/Z restoration, floor-safe Y, sparse
  displayed equipment, unchanged loot/history/pose, no duplicate drops, and
  reclamation of all six saved native limbs and their topology.
- Native Sable platform, stationary at death: **40/40 passed**.
- Native Sable platform, moving at death and through physics retirement:
  **40/40 passed**. Both scenarios preserve loot and world coordinates through
  later translation, yaw rotation, pitch, and removal of the supporting platform.
- [Full Corpse regression](corpse-checks-1.5.1-neoforge-265.log): **69/69 passed**
  on the final binary, including actual lava and void falls, inventory transfer,
  permissions, skeleton/expiry behavior, floor handoff, saved-pose reload,
  orphan cleanup, and 120 corpses under a two-body physics budget.
- [Startup without Corpse](no-corpse-startup-1.5.1.log) on NeoForge 21.1.249
  also passes with the final JAR. A vanilla armor stand exercised the Sable
  movement path; the optional hooks caused no class-linkage or mixin failure.

- [Actual restart methodology and results](corpse-restart-checks-1.5.1.md),
  including retained failing baselines and exact process identities.
- [Sable platform methodology and results](corpse-sublevels-1.5.1.md), including
  a native Corpse control, stationary and moving deaths, translation, yaw,
  pitch, support removal, inventory, bounds, and released native limbs.
- [Previous client rendering/input verification](corpse-client-checks-1.5.0.md)
  and [original compatibility coverage](corpse-compatibility-1.5.0.md) remain
  historical evidence for 1.5.0, rather than new client checks of 1.5.1.

## Dragging and limits

Dragging is not implemented in this release. Right-click retains Corpse's
inventory interaction. It can be added with a separate control that temporarily
wakes the selected saved body, applies normal owner/access and reach checks,
and releases it back to a saved pose afterward. Such a feature needs to share
the active-body budget and handle disconnects, distance, and physics expiry;
removing the native grab-disable flag alone is insufficient.

Active ragdolls can physically slide on a moving deck. After retirement the
corpse anchor follows supporting-platform movement, while the saved limb
orientations remain world-aligned, as with native Corpse model orientation.
This does not simulate new articulation for old corpses. Testing uses isolated
copies of the relevant installed dependencies, not the complete modpack. See
each evidence document for its limits, including untested moving-vessel
restarts, arbitrary high-speed impacts, and actual distant chunk eviction.
