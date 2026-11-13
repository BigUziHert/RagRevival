# Corpse client verification — 1.5.0

These checks used an isolated client and dedicated server under `.local/corpse-compat`, with Minecraft 1.21.1, NeoForge 21.1.265, Corpse 1.1.22, Sable 2.0.6, Sable Ragdolls 0.7.5, Ragdoll Reactions 0.7.0 and Carry On 2.2.6.13. The opt-in test harness created real Corpse entities from `Death.fromPlayer`; mouse and keyboard input exercised the normal game UI. The user's CurseForge instance was read only. This was a focused dependency test, not a run of the complete modpack.

## Rendering and settled-body interaction

Verified on 2026-11-11 with release-JAR SHA-256 `4175ba3a0cd42b2c69e4409dd351441a0383eec876a505537ff5487e1a1c475f`:

- A skinned body and an aged skeleton fell from y=73.5 onto a smooth-stone platform at y=70. Both showed diamond chestplates and off-hand shields. The skeleton fixture reapplied its cosmetic equipment after the native NBT age edit, avoiding Corpse's existing sparse-equipment serialization behavior.
- Both reached a lying pose. After the 200-tick physics period, the server reported `active: false` and both anchors remained at y=70. The client rendered the same settled poses.
- Right-clicking the settled skeleton opened the native Corpse GUI. Its armor slot contained the chestplate, off-hand slot contained the shield, and hotbar slot 8 contained seven diamonds. Clicking **Transfer Items** emptied the corpse and equipped/transferred exactly those items. A second click produced no additional items; server inventory remained seven diamonds, one chestplate and one shield.
- The same mouse-driven open/transfer check passed for the settled skinned corpse. The before/after GUI screenshots below are native Minecraft F2 captures.
- F5 rear and front views showed the living player with all body parts, skin, chestplate and shield intact after rendering the corpses. No per-limb model visibility or scale leaked into the living player.

| Evidence | Image |
| --- | --- |
| Skinned and skeleton bodies during the active simulation period | [active-armored.png](corpse-visual-1.5.0/active-armored.png) |
| Same bodies after physics retirement | [settled-armored.png](corpse-visual-1.5.0/settled-armored.png) |
| Native inventory before transfer | [loot-before.png](corpse-visual-1.5.0/loot-before.png) |
| Native inventory after transfer | [loot-transferred.png](corpse-visual-1.5.0/loot-transferred.png) |
| Living player after corpse rendering and looting | [living-player-after.png](corpse-visual-1.5.0/living-player-after.png) |

## Active-body regression

The first active-body mouse test exposed duplicate client gravity: one client corpse drifted from y=70.65 to y=-24.32 while the server body remained at y=69.98. Corpse's native nearby-UUID menu lookup then failed. The final implementation skips only Corpse's extra movement/gravity block during active Sable simulation; native entity base ticks, age, skeleton conversion and despawn continue. The final verification below uses a later JAR containing that fix, an immediate active-pose flag and deferred restored-pose floor clearance; the renderer is unchanged from the screenshots above.

Final verification passed with release-JAR SHA-256 `0c8759315eec01f9d9608df0f106d779a328b4bb5633990a8bb20c12add525da` on both the client and server:

- A normal right-click opened `CorpseInventoryScreen` while both new corpses still had `active: true`. The server independently reported `1b` for both active flags. The client positions remained near y=69.98, within Corpse's native menu lookup range. See the [active inspection](corpse-visual-1.5.0/active-client-inspection.json) and [native loot-screen capture](corpse-visual-1.5.0/final-active-loot.png).
- All twelve client Sable limb block entities had `ragrevival:corpse_part=true`, confirming the marker reached the client and suppressed their duplicate native rendering.
- The GUI remained open across physics retirement. The [settled inspection](corpse-visual-1.5.0/settled-client-inspection.json) shows both authoritative corpse entities at y=70, both `active: false`, both still in native lookup range, and zero remaining native limb sublevels.
- Clicking **Transfer Items** succeeded after that transition. Server inventory contained exactly seven diamonds in slot 8, a chestplate in armor slot 102, and a shield in off-hand slot -106. The [transfer capture](corpse-visual-1.5.0/final-transfer.png) shows the corpse empty and the diamonds in their original player slot.
- Returning to the world showed both supported, correctly rendered bodies. The looted skinned body subsequently expired through Corpse's normal empty timer; the [final settled capture](corpse-visual-1.5.0/final-settled.png) shows the remaining armored skeleton. No advanced-screen or mixin application failure occurred in this final client run.

The owned isolated client was stopped after verification. The server test owner then shut down the disposable dedicated server.

Visual observation covered falling, resting, armor, the skeleton model, inventory interaction and normal player rendering. It did not measure animation frame times or test third-party shader/render pipelines. Server-side lava, void, ownership, persistence and physics-budget assertions are recorded separately in [corpse-compatibility-1.5.0.md](corpse-compatibility-1.5.0.md).
