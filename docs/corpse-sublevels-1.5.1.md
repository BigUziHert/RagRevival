# Corpse compatibility: native Sable platform checks

Tested on 2026-11-11 in the isolated `.local/corpse-sublevels` dedicated server. The user's CurseForge instance was not changed.

Runtime: Minecraft 1.21.1, NeoForge 21.1.265, Sable 2.0.6, Sable Ragdolls 0.7.5, Ragdoll Reactions 0.7.0, Corpse 1.21.1-1.1.22, Carry On 2.2.6.13, RagRevival 1.5.1. Production JAR SHA-256: `c6cebc64113bb55b712aafee9e4d622e6383bbfc5c756e73dc7d80715a34d7dd`.

The fixtures use Sable's actual `assembleBlocks` API and native all-axis physics constraints. They do not mock sublevel transforms or collision. Registered server-player fixtures receive lethal damage, enter the normal downed state, and complete terminal death through RagRevival. A second native Corpse with the original bounding box provides a movement control.

## Results

- Stationary-at-death fixture: **40/40 passed**, 801 server ticks. A 13×13 platform starts at 30° yaw. After death and 200 ticks of native ragdoll simulation, the frozen corpse stays still, follows four blocks of translation, remains supported through a further 40° yaw and 8° pitch, and survives removal of the supporting sublevel. Inventory stays at seven diamonds, exactly one authoritative corpse is created, and world coordinates never become plot-storage coordinates. No marked corpse-limb sublevels remain after retirement or support removal.
- Moving-at-death fixture: **40/40 passed**, 801 server ticks. A 35×35 deck translates fourteen blocks at 1 m/s, continuously spanning downed death, native corpse physics, and the transition to a frozen corpse. The later translation, rotation, pitch, and support-removal checks are repeated. This variant additionally enumerates native Sable sublevels to verify no marked corpse limbs remain after retirement or support removal.
- Actual entity bounds contain every saved limb and remain horizontally centered on the entity position after retirement, stationary ticks, translation, and rotation.

## Regression reproduced and fixed

The 1.5.0 baseline drifted on an unmoving rotated deck while the original native Corpse control stayed fixed. Sable reconstructs entity feet from the bounding box's horizontal center and adds the difference as inherited motion. The old torso-centered pose box was asymmetric, so its offset became artificial movement on every tick. Expanding the box symmetrically around the existing anchor fixes this without changing the saved pose or its appearance. The `makeBoundingBox()` override also preserves those bounds when Sable uses its raw position setter.

Both final suites were rerun after adding the owner-specific collision filter used for restored orphan limbs. Genuine unrelated support-platform collision and tracking still pass.

## Observed behavior and limits

- Active native physics can slide on a moving deck. In the moving-death run the deck traveled about seven metres while the active body's world X changed only about 0.2 metres. The large test deck allows this physical sliding without confusing it with the frozen-corpse drift regression. Narrow or fast-moving decks can therefore let an active ragdoll slide off; the compatibility layer does not glue active physics to its support.
- After physics retirement, the corpse anchor follows Sable support movement. Its saved model orientation remains in world space, matching native Corpse's model behavior. It does not turn the six saved limb orientations with the deck.
- These are dedicated-server physics, state, and inventory checks. They do not prove client appearance, arbitrary vessel designs, high-speed collisions, every pitch/roll angle, chunk-unload persistence, or a restart while the supporting vessel is moving. Separate restart and client checks cover their documented scenarios.

Evidence: `corpse-sublevel-baseline-1.5.0.log`, `corpse-sublevel-stationary-1.5.1.log`, and `corpse-sublevel-moving-1.5.1.log`. Reproduction commands: `ragrevivaltest corpse-sublevel` and `ragrevivaltest corpse-sublevel-moving`, using the separate test harness in an empty isolated server.
