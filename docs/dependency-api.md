# Inspected dependency APIs

These are the original build baselines, retained for reproducibility in
**1.3.5**. RagRevival accepts broad NeoForge/Sable versions within Minecraft
1.21.1 through FML's omitted-range default. See [current multiplayer evidence](test-results-1.3.5.md),
[the 1.3.4 loader correction](version-compatibility-1.3.4.md), and
[reproducible compatibility profiles](compatibility-runtime.md).

The production JAR's 20 referenced Sable public fields/methods were resolved
with their exact descriptors through class hierarchies and embedded Companion
libraries in ten published versions: **1.1.1, 1.1.3, 1.2.1, 1.2.2, 2.0.0,
2.0.1, 2.0.2, 2.0.3, 2.0.4, and 2.0.5**. Each matched 20/20 members, with
all nine concrete API classes present. The [machine-readable report](sable-api-matrix-1.3.5.json)
records artifact URLs, verified hashes, dependency metadata, and exact members.
No source adapter change was needed. This static check does not establish
every version's physics behavior or mixin compatibility; executed combinations
are listed separately in the runtime evidence.

The [NeoForge report](neoforge-api-matrix-1.3.5.json) resolves all 50 referenced
NeoForge-owned members across the 28 published releases from 21.1.219 through
21.1.250. It checks exact public descriptors, inheritance and static/instance
consistency, excluding Minecraft patches, separate FML/event-bus APIs and
runtime/mixin behavior. The dedicated-server matrix covers five combinations.

The reviewed Sable 1.x releases require NeoForge 21.1.219+; 2.x requires
21.1.228+. Optional Unlocked Camera requires 21.1.235+ and is excluded from
earlier profiles. Ragdolls 0.7.2 and Reactions 0.7.0 remain mandatory. Future
versions and arbitrary modpacks are outside the verified matrix.

The implementation targets published Minecraft 1.21.1 NeoForge artifacts, downloaded from their original distribution CDNs. Exact URLs and SHA-256 digests are in [dependencies.lock.json](dependencies.lock.json). The three ragdoll dependencies below are required on server and clients; their binaries and research checkouts are not redistributed in this repository. The lock also contains Carry On 2.2.6.13 as an optional runtime integration required only for the compile classpath. `scripts/fetch-dependencies.ps1` fetches and verifies all four so a clean clone can compile; Carry On remains optional when installing RagRevival.

| Dependency | Selected artifact | Inspected source |
| --- | --- | --- |
| Sable | 2.0.3, Modrinth version `1L6XJqnY` | [4bc206ee4718f2a7906e4366963ada98eadd78fa](https://github.com/ryanhcode/sable/tree/4bc206ee4718f2a7906e4366963ada98eadd78fa), release commit |
| Sable: Ragdolls | 0.7.2, CurseForge file `8295399` | [5500881f2ffe4329c6b9e1bf8e283997a620997d](https://github.com/Leo-T22/sable-player-ragdoll/tree/5500881f2ffe4329c6b9e1bf8e283997a620997d), version 0.7.2 source |
| Ragdoll Reactions | 0.7.0, CurseForge file `8281400` | [54ef8cc](https://github.com/Leo-T22/ragdoll-reactions/tree/54ef8cc), 0.7.0 gameplay source; subsequent HEAD `e223bf0bd40a73eec51c883d4dbd9908b40f5da9` changes licensing |

Sable 2.0.3 was chosen because the published Ragdolls 0.7.2 source explicitly compiles against it. Reactions 0.7.0 requires Ragdolls 0.6.9 or later and Sable 1.1.0 or later in its metadata. The selected NeoForge 21.1.249 exceeds the dependency minimums. Newer source HEADs were not used as API contracts: Ragdolls HEAD is 0.7.5 and contains methods absent from the published 0.7.2 JAR. Public adapter signatures were checked with Java 21 `javap` against the actual downloaded JARs. The upstream repositories do not provide a signed artifact-to-source attestation; the selected source/version and the binary signatures match the methods used here, without claiming a byte-for-byte reproducible upstream build.

Sable contains Sable Companion 1.6.0, Veil 4.1.4, and its Rapier native library as nested JARs. No extra external runtime JAR is required for those libraries. The build extracts Companion only for its compile classpath; installing that extracted JAR alongside Sable is unnecessary.

## Ragdoll lifecycle and movement

`RagdollAPI.launch(player, velocity, options, pose)` creates a queued physics body. Supplying an explicit `RagdollPoseSnapshot` avoids the asynchronous client-pose request path. Launch/seat completion still happens on Sable's next physics tick, so the adapter reports readiness only when the seat is actually mounted. Two small accessor mixins provide the cancellation absent from upstream's public API: they remove only this player's pending client-pose requests during downing and release. This prevents a Reactions request created before fatal damage from launching another body after a very short configured feeding interaction.

`RagdollLaunchOptions` supplies `autoSeat(true)`, `lockDismount(true)`, and a nonempty list containing `DespawnCondition.never()`. Empty custom conditions would restore Sable's configured expiry behavior. Existing Reactions ragdolls are converted in place using `RagdollSessionManager.setCustomDespawnConditions` and `setDismountLocked`, preserving their current pose, velocity, native movement inputs, and joints.

The inspected `SablePlayerRagdollNeoForge.onEntityMount` cancels manual dismount and consults `canManualDismount`; the lock covers ordinary crouch/dismount exit. Native H launch requests are suppressed while already ragdolled. The adapter does not alter `RagdollControlHelper`, its WASD torque inputs, or camera control. Ragdoll termination uses `RagdollExpireHelper.expireImmediate(..., true)` so the native seat, body, constraints, invisibility, and network state are cleaned up with explicit world-space player placement. Administrative operations or other mods can still remove a body; the server controller reasserts the downed ragdoll while its independent downed record remains active.

## Real body targeting and reach

Sable physically stores a mounted player's coordinates in a remote plotyard. Treating `Player.position()` as an ordinary world position produces incorrect reach and death positions. The adapter projects it with `Sable.HELPER.projectOutOfSubLevel`; body physics and the player continue to be synchronized by the native seat rather than independent per-tick teleports.

Each player body is six `RagdollPartBlockEntity` instances in separate linked sublevels. The adapter reads each part's actual outline shape and transforms the ray into the part's local coordinates. This respects rotated limbs rather than testing the hidden vanilla player box. Server queries use logical physics poses; client queries use `ClientSubLevelAccess.renderPose()` so the ray follows the displayed interpolated limbs. The server sends the active body's exact linked sublevel UUIDs in the downed-state packet, bounded to six parts. Both sides query only those IDs, so a playerless dummy using the same skin/profile cannot be mistaken for the downed player. Client topology is cleared on revival/death, disconnect, dimension change, and stale state timeout.

`canReach` checks distance from the rescuer's actual world-space eyes to the nearest oriented limb outline against `entityInteractionRange()`. It raycasts through the main level and other Sable sublevels for obstructions. Sable's `BlockGetterMixin.clip` returns plot-local hit positions for sublevel hits; the adapter resolves the hit's containing sublevel and accepts a hit on the target's own limbs, rather than comparing plotyard positions to world positions. An offset camera cannot increase the server interaction reach.

## Dragging

`RagdollPartBlockEntity.startGrab(UUID)` and `stopGrab(UUID)` manage the native Rapier spring constraint. This applies physics to the body and inherits native collision and seat synchronization. The adapter selects the closest real limb, has one drag owner per target, and refreshes the grip only while the controller maintains the crouch lease. Native grips break beyond four blocks; the adapter uses the same limit. Existing ordinary grips are removed when the body becomes downed. `RagdollAPI.setGrabDisabled` is applied to every linked limb because version 0.7.2 checks the clicked limb's tag rather than its root; this prevents the upstream generic grab packet from starting an uncontrolled additional grip on a downed body. Direct `startGrab` is intentionally unaffected by that packet gate. The adapter directly starts only its validated owned grip and sends the upstream grab animation callbacks.

## Reactions distinction

Reactions uses `RagdollAPI.launch` from crash, fall, damage, explosion, and impact detectors; it has no downed-state concept. The revive mod's own persisted downed record is the only source of truth for immunity, timer, feeding, and give-up. Merely being an ordinary Sable/Reactions ragdoll does not enable those rules. Reactions' `canTarget` ignores mounted/already-ragdolled players, and its release listener preserves its ordinary retrigger cooldown after revival.
