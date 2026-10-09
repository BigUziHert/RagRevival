# RagRevival 1.4.0 verification

Checked October 9, 2026 with Java 21, Minecraft 1.21.1, NeoForge 21.1.249,
Sable 2.0.3, Sable: Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0.

## Build and packaging

- `gradlew.bat build testModJar` passed.
- All 15 existing JUnit tests passed: dependency metadata, pending-pose
  compatibility, the downed clock, and hold/input-lease accounting.
- Production and source JARs are under `artifacts/1.4.0`, with SHA-256 sums.
  The production JAR includes the command, item predicate, and updated resources;
  it contains no integration harness classes.
- Production JAR SHA-256:
  `0c55c4901e0c50ddb6ac6ecfc63358dcfdbdf0b543b45573b1f384a0789cdc30`.

## Dedicated server behavior

The opt-in `ragrevivaltest revive-features` probe passed **87 checks, zero
failures**, over 475 server ticks in a fresh, loopback-only test world.
The [recorded check output](revival-feature-checks-1.4.0.log) contains each result.
The server shut down cleanly and saved all dimensions after the final run.

The fixture uses native `ServerPlayer` objects, Sable physics, the real command
dispatcher, damage events, targeting mixin, and revival manager. Temporary
players have in-memory packet channels and packet sinks; their normal player
and item-use updates run, without requiring Minecraft clients. The probe
refuses to start with real players online and removes its temporary players
and mobs on completion.

Verified behavior:

- `/revive` is registered, requires permission level 2, and rejects non-operators,
  missing players, healthy players, and dead players. Successful command revival
  restores configured health and grants grace immediately.
- Command revival cancels active teammate feeding and self-feeding, clears use
  state, and consumes no item.
- Both original foods and all six new item families qualify. Healing I/II and
  normal/extended/strong Regeneration potions qualify. Water, awkward, harmful,
  swiftness, empty, splash, lingering, and custom-effect potions do not.
  Every item presented by the shared HUD example provider qualifies.
- Each newly added item completes a real timed self-revival. A partial hold
  consumes nothing, completion consumes exactly one item and grants grace,
  and late input cannot consume another item. Normal food/potion effects do
  not run during revival. Honey-bottle teammate feeding also starts correctly.
- Revived players cannot become direct mob targets or pass a mob target search.
  Brain attack targets and retaliation references are cleared on the next tick.
  Player-source target queries still see them.
- Mob melee and damage from an arrow owned before revival are blocked during
  grace, including tick 199. Grace ends exactly at tick 200, at which point
  mob targeting, target searches, and arrow damage work again.
- Environmental and player damage remain active during grace. `/kill` is
  terminal and clears protection; `/revive` cannot resurrect the dead player.

## Scope and manual checks

This is server integration coverage using simulated connections, not a full
two-client gameplay or rendering test. Client/server compilation and shared
item eligibility are checked; actual held mouse input, key remapping, visual
icon cycling, and the compact HUD layout still need in-game review. The full
historical multiplayer suite and dependency matrix were not rerun for 1.4.0.
Command cancellation of dragging uses the same reviewed cleanup path, but the
focused probe specifically exercises cancellation of feeding.

For a quick manual check, equip each new item and hold Use to revive a teammate.
Then down yourself without a valid held item and confirm the equip hint shows
one cycling icon instead of an item list. Equip a valid potion and verify its
icon stays fixed. Finally, revive beside a skeleton and confirm it ignores you
for ten seconds, then can target you again. Use matching 1.4.0 JARs on both sides.
