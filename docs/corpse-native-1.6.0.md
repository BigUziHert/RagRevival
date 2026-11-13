# Native corpse ragdolls — 1.6.0

Corpses now retain native Sable articulation throughout their loaded lifetime.
The ten-second timer, eight-body cap, and proximity-based permanent freeze from
1.5.x are removed. Their three configuration keys remain readable for existing
configurations but are explicitly deprecated and ignored.

## Physics and persistence

Final death transfers an existing downed ragdoll directly into a playerless
native session. Its six bodies, joints, positions, rotations, and momenta remain
intact. A death without an existing ragdoll assembles a native playerless body.
Sessions use `PlayerlessDespawnRule.never()`; Corpse retains control of inventory,
ownership, death history, skeleton age, empty/forced despawn, and deletion.

Settled ragdolls use Sable/Rapier's normal sleep and wake behavior. No custom
static-pose sleep replaces physics. Native grabs, impacts, and supporting-body
motion can affect the limbs. The client renders from Sable's interpolated limb
transforms, with saved transforms as a fallback while native packets arrive.

Clean server shutdown saves the native bodies intact. On reload, the corpse
reserves and adopts its six existing owner-marked parts after native topology
and physics handles are available. This avoids spawning overlapping replacement
limbs during recovery. Snapshot NBT additionally preserves per-limb linear and
angular velocities. Legacy saved poses without native parts can reconstruct a
body, restoring motion once after Sable's deferred launch.

Only chunks Sable considers ready for physics can assemble a body. No corpse
chunk tickets are added. Unloading clears runtime ownership and grips; native
limbs leave active physics storage and can resume from held world data. Deleting
the corpse releases its limbs. Loading can resume/reconstruct them. Incomplete saved bodies
remain reserved while their native parts load, retaining the inventory and pose.
The integration never destroys loot to meet a physics budget.

Each loaded corpse therefore has six native physics parts. Native sleep reduces
solver work but does not eliminate all actor, rendering, or memory overhead.
There is no FPS claim or guarantee that arbitrarily many loaded bodies are free.

## Controls

- Empty main hand, standing, held **Use**: Sable's native grab spring.
- Releasing **Use**: native release.
- Crouch-Use or Use with an item: original Corpse inventory GUI.

The existing native grab packets and callbacks are retained. Server checks add
Corpse owner/operator/skeleton permissions, initial reach and line of sight,
and valid player/body identity. Subsequent spring movement and distance release
use native behavior. Death, logout, dimension changes, item switching, body
removal, and shutdown release the corresponding grip. Ordinary and downed
ragdoll controls retain their existing paths.

Carry On cannot pick up the inventory-owning Corpse entity or its marked
physical limb blocks. Live client testing found that Carry On otherwise
intercepts crouch-Use and can copy entity NBT while Corpse drops its original
loot during removal. The narrow pickup guards preserve the native loot GUI
and prevent that duplication path; other Carry On entities and blocks remain
eligible under their normal rules.

## Corpse safety

Water and lava keep articulated native physics, including Sable's buoyancy and
drag. Corpse's optional destructive lava setting remains in effect. With native
Corpse void protection enabled, the six bodies are lifted together at minimum
world height and downward velocity is canceled; lateral/angular motion remains.
Enabling Corpse's destructive void option keeps its configured behavior.
These inventory-protection rules intentionally remain part of Corpse gameplay.

## Verification scope

Release JAR SHA-256:
`ce9ebc2749d7f3086d6ae292323e720bb2954851390d704dc0674be83b90b1c4`.

- Build and **16 JUnit checks** pass.
- [Native platform suites](corpse-sublevels-1.6.0.md): **60/60 stationary**
  and **60/60 moving-death checks**, comparing the corpse with an ordinary
  native ragdoll. Original limbs settle, respond to impulses, remain articulated
  on translated/rotated/tilted support, fall when it is removed, and keep loot.
- [Dedicated compatibility suite](corpse-checks-1.6.0-neoforge-265.log):
  **88/88 checks**, including 12 native corpse bodies beyond the former cap,
  continued physics beyond the former timeout, native rest/impulse/drag,
  permission and reach rejection, actual lava/void deaths, inventory transfer,
  entity unload/reload, and real Carry On pickup rejection without copied loot
  or detached limb blocks.
- [Clean and forced-stop restarts](corpse-restart-checks-1.6.0.md):
  **32/32 checks each**. Both reopen the same world in another Java process,
  preserve the same native root/limb IDs, and verify loot, equipment, history,
  saved pose/velocity, and absence of extra bodies or duplicate drops. Forced
  termination is after a completed save, not during a filesystem write.
- [Real distance-driven chunk eviction and return](corpse-chunk-checks-1.6.0.md):
  **15/15 checks**. Leaving the area unloads the corpse and all six active
  native parts; returning restores the original corpse/root, inventory,
  articulation, and stable rest. A plain vanilla entity supplies the chunk
  tracking control. This is actual chunk eviction rather than a direct removal call.
- [Startup without Corpse](no-corpse-startup-1.6.0.json) passes on NeoForge
  21.1.249, followed by clean shutdown.
- [Client checks](corpse-native-client-1.6.0.md) verify native targeting,
  packets, grab acknowledgement, physical movement, release, and regrab with
  a bounded test-only Use-key hold. Actual mouse clicks verify crouch looting,
  looting with an item, and exact inventory transfer. The held-input limitation
  and all trace evidence are documented; no direct physics grip is injected.
  Skin/skeleton equipment and normal living-player rendering also pass.

Tests use isolated runtimes outside the supplied CurseForge instance, with its
relevant Minecraft 1.21.1, NeoForge 21.1.265, Sable 2.0.6, Ragdolls 0.7.5,
Reactions 0.7.0, Corpse 1.1.22, and Carry On 2.2.6.13 versions. The original
modpack directory is unchanged. The complete modpack, shaders, arbitrary ship
designs, and interrupted disk writes are outside this verification.

Matching 1.6.0 installations are required on server and clients; protocol 5
rejects older peers because input and native limb-rendering behavior changed.
