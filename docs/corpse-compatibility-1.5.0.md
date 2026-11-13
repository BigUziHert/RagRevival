# Corpse compatibility in 1.5.0

The provided `Modded Minecraft` instance was read only. Development, copied
dependencies, test configurations, and test worlds are under this repository's
ignored `.local/` folder. Nothing was installed into the supplied instance.

## Behavior and resource limits

The original `corpse:corpse` entity owns all inventory and gameplay state.
RagRevival never moves the inventory into a Sable entity. Downing is unchanged;
only final death runs Corpse's normal inventory capture. Native owner/operator
permissions, skeleton access, equipment display configuration, original-slot
transfer, death history, creative recovery, `keepInventory`, and expiration
continue through the Corpse implementation.

RagRevival uses temporary playerless Sable physics and saves six relative limb
centers and rotations on the original corpse. Native Corpse player/skeleton
renderers and equipment layers are rendered at those transforms. Right-clicks
on active Sable limbs are forwarded to the same Corpse inventory after server
reach and obstruction checks. The saved pose supplies the corpse's bounds after
physics ends. The downed body's final pose is captured before releasing its seat.
During active physics, Sable supplies movement on both sides and Corpse's duplicate
gravity/move branch is suspended. Its base tick, age, skeleton stage, inventory,
and expiry still run. This prevents the client corpse from independently falling
away from its server position and breaking Corpse's nearby-UUID menu lookup.

Defaults in `ragrevival-server.toml`:

| Setting | Default | Purpose |
| --- | --- | --- |
| `maxCorpseRagdolls` | 8 | Global simultaneous physics cap (six Sable parts per body) |
| `corpsePhysicsTicks` | 200 | At most 10 seconds of physics at 20 TPS |
| `corpseRagdollRange` | 48 | Only simulate near a player; release when no player is near |

After settling, a corpse keeps its pose with **zero Sable physics parts**. It
still has its normal Corpse entity and native tick, so aging, gravity, fluid
floating, and configured despawning continue. It does not resume articulated
physics when revisited. New corpses beyond the cap use a lying six-part pose.
Setting the cap to zero keeps all corpse presentation static. No chunk tickets
are added. The cap concerns corpse bodies only; ordinary Sable/Reactions and
downed-player ragdolls retain their existing behavior.

Physics also retires near the void floor or in water/lava, leaving Corpse's own
movement in charge. Its default void clamp and lava protection remain intact;
deliberately enabling Corpse's `fallIntoVoid` or `lavaDamage` keeps those native
destructive options. The six build-height staging cells used by Sable must be
empty before temporary physics can spawn.

Removal, unloading, expiry, distance, and clean shutdown release active physics.
At handoff, tracking of the corpse's own limbs is cleared and small floor
penetrations are lifted clear before native entity movement resumes. Sable's
physics colliders are thinner than its visible outline; using that outline
unchanged could otherwise put the frozen corpse inside a thin platform.
During an active simulation, Corpse's separate vanilla movement step is skipped
on both sides while the rest of its native tick still runs. This prevents client
gravity from drifting the visible corpse away from the server's physics position
and breaking the native inventory screen's nearby-entity lookup. Native movement
resumes when physics retires.
Pose NBT survives reload without allocating another body. Marked orphan limbs
restored after an interrupted shutdown are reclaimed after a brief load grace;
unrelated playerless ragdolls are ignored. Frozen bounds are cached rather than
recomputing all transforms each tick.

## Source and scope

Integration was inspected against [Corpse's 1.21.1 source at ba5b53f1](https://github.com/henkelmax/corpse/tree/ba5b53f1)
and the installed `corpse-neoforge-1.21.1-1.1.22.jar`. Its download and SHA-256
are recorded in [the dependency lock](dependencies.lock.json). Corpse remains
an optional compile dependency and is never redistributed in RagRevival.

RagRevival 1.5.0 uses protocol 4 on every installation. Matching server/client
versions are required because corpse entities gain synchronized pose data.

## Verification

The dedicated-server probe passed **69 assertions with zero failures** on both
NeoForge **21.1.249** and the supplied pack's **21.1.265**, each in 957 server
ticks. Both used the pack's Corpse **1.1.22**, Sable **2.0.6**, Sable Ragdolls
**0.7.5**, Reactions **0.7.0**, and Carry On **2.2.6.13** on Minecraft **1.21.1**.
See the [249 log](corpse-checks-1.5.0-neoforge-249.log),
[265 log](corpse-checks-1.5.0-neoforge-265.log), and
[machine-readable versions, hashes, coverage, and limits](corpse-runtime-1.5.0.json).

The probe physically dropped native server players into the void and into a
constructed lava basin. It checked terminal death, retained inventory and
protection after subsequent ticks, plus Corpse's opt-in destructive lava setting.
Other checks covered owner/skeleton access, original-slot transfer, native death
history recovery availability, empty/forced expiry, and entity NBT unload/reload.
With `keepInventory`, the native empty corpse is allowed; it contains no duplicate
loot. A stress fixture retained **120 full corpses** while limiting active physics
to **two bodies**, then verified that physics expired without deleting inventory.
Orphan cleanup preserved an unrelated dummy, and occupied Sable assembly staging
cells remained unchanged. Geometry checks covered 48 yaw/face combinations and
malformed-pose rejection.

A client check exposed a floor handoff regression: Sable's visible limb bounds
slightly penetrate the supporting surface even when its smaller physics colliders
have settled correctly. Reusing those bounds for vanilla movement made the corpse
fall through a raised platform after physics retired. The fix clears small floor
penetrations when handing movement back to Corpse. The
[failing trajectory](corpse-floor-1.5.0-baseline.log) dropped from a platform at
Y=96 to the lower platform at Y=70; the
[fixed trajectory](corpse-floor-1.5.0-fixed.log) stayed at Y=96 for 60 ticks after
retirement. Both final full suites check that transition with an ordinary native
ragdoll as a control, and also check a captured downed pose with the physics budget
set to zero. A second active corpse is saved with the observed small support
penetration and reloaded through the engine removal/NBT path; it remains supported
for 100 ticks, keeps all inventory, and allocates no new physics body.

These are real server and native-physics checks using temporary players and packet
sinks. They do not measure frame rate, exercise physical mouse input, launch the
entire supplied modpack, or prove recovery after an abrupt process kill. The reload
check uses the actual entity serialization and engine removal APIs; it is not a
full server-restart or actual chunk-eviction test. Client visual/input verification
is recorded in [the client checks and screenshots](corpse-client-checks-1.5.0.md).

The integration harness command is `/ragrevivaltest corpse-compat`; it is only
for disposable test worlds. `/ragrevivaltest corpse-visual <player>` prepares
lootable player and skeleton fixtures for client inspection.
