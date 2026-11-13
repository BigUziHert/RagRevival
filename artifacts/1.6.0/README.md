# RagRevival 1.6.0

Install `ragrevival-1.21.1-1.6.0.jar` on the server and every client, replacing
the previous RagRevival JAR while each instance is stopped. This version uses
protocol 5 and rejects older RagRevival peers. Minecraft 1.21.1, Java 21,
NeoForge, Sable, Sable: Ragdolls, and Ragdoll Reactions 0.7.0 remain required.
Corpse is optional; third-party dependencies are not bundled.

Corpses retain native Sable physics while loaded. Existing downed limbs and
joints transfer directly at death, continue reacting after settling, and use
the native grab spring. Server restarts preserve and adopt the same native
bodies. The former ten-second freeze, eight-body cap, and proximity freeze
are removed; their old configuration keys are ignored.

- Stand with an empty main hand and hold Use/right-click to drag.
- Release Use to let go.
- Crouch-Use or Use with an item to open the original Corpse inventory.

Corpse permissions also apply to dragging. Loot, history, equipment, skeleton
age, despawn, and configured lava/void protection stay with the original corpse.
Carry On pickup of corpses and their limbs is blocked to prevent duplicate loot
and keep crouch-Use available for the inventory.
Native sleep/wake and chunk loading control physics work. Each loaded corpse
retains six parts, so a large concentration still has a resource cost.

See [implementation, test evidence, and limits](../../docs/corpse-native-1.6.0.md).
All tests run outside the user's supplied CurseForge instance. Nothing is
installed there. The separate test harness is excluded from the release JARs.
`SHA256SUMS.txt` covers the distributable and source JARs.
