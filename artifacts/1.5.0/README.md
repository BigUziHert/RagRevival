# RagRevival 1.5.0

Install `ragrevival-1.21.1-1.5.0.jar` on the server and every client.
Stop each instance before replacing its previous RagRevival JAR. This release
uses protocol 4 and rejects older RagRevival peers before joining.

Corpse is optional. With Corpse installed on both sides, final player deaths
leave a Sable ragdoll while the original Corpse entity retains its items,
ownership rules, menus, transfer-to-original-slots, skeleton stage, death
history, and despawn settings. Knockdowns still allow revival; they do not
create inventory corpses.

At most eight corpse ragdolls simulate simultaneously by default, for at most
200 ticks each. Their six limb poses remain visible and saved after all Sable
physics parts are released. Old corpses and unloaded chunks do not restart
physics. The limit applies across dimensions and can be configured. Lava and
void safety use Corpse's own movement and settings.

Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls, and Ragdoll Reactions
0.7.0 remain required. Download dependencies separately; none are bundled.

See [implementation and test evidence](../../docs/corpse-compatibility-1.5.0.md).
The final build passed 69 native server assertions on both NeoForge 21.1.249 and
21.1.265, including lava, void, 120 corpses under a two-body physics cap, floor
handoff and saved-pose reload. Real client input verified active and settled
looting, original-slot transfer, armor, skeleton rendering and normal player
rendering. Tests used the relevant dependencies in isolated instances; the
complete user modpack was not launched or modified.
`SHA256SUMS.txt` covers the distributable and source JARs. The separate integration
test harness is not included in the distributable.
