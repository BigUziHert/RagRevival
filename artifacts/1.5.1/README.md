# RagRevival 1.5.1

Install `ragrevival-1.21.1-1.5.1.jar` on the server and every client, replacing
the previous RagRevival JAR while each instance is stopped. Protocol remains 4.
Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls, and Ragdoll Reactions
0.7.0 are required. Corpse is optional; download dependencies separately.

This patch fixes corpse drift on Sable platforms and when saved physics parts
reload. The corpse's collision box now stays centered on its saved anchor and
remains correct when Sable updates its position directly. Every rendered limb
is still enclosed; no saved-pose migration is required.

The corpse also ignores collisions with its own marked temporary Sable limbs,
including limbs restored after an interrupted shutdown. Those limbs are still
reclaimed by the existing orphan cleanup; other bodies and platforms continue
to collide normally.

Displayed equipment now retains explicit slot indices across server restarts,
including empty slots between a shield and chestplate. This supplements Corpse's
cosmetic serialization without moving or modifying its actual loot inventory.
It cannot reconstruct cosmetic slot positions already lost in an older save.

The corpse remains the original inventory-owning Corpse entity. Temporary
physics retains the existing global cap, timeout, and distance limits. A loaded
corpse keeps its saved pose without starting another physics body. Dragging is
not implemented; normal right-click opens the native Corpse inventory. Settled
corpses follow a moving platform's position, but their saved limb orientations
remain world-aligned rather than articulating again as the platform turns.

See [restart, sublevel, and regression evidence](../../docs/corpse-persistence-1.5.1.md).
Tests use isolated copies of the relevant dependencies. The supplied CurseForge
instance was not launched or modified. `SHA256SUMS.txt` covers the distributable
and source JARs; the test harness is excluded from both release artifacts.
