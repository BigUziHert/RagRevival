# Compatibility source inspection

Inspected on 2026-09-08. Required Sable dependency versions and APIs are documented separately in `dependency-api.md`.

## Unlocked Camera

The private repository was successfully cloned using the existing authenticated Git credential helper without opening a login prompt or exporting credentials:

- Repository: <https://github.com/BigUziHert/unlocked-camera>
- Source commit: `c13cfa3db55f1b1a6ac672d15b017aafd6a68670`
- Version: `unlockedcamera` 1.0.0, Minecraft 1.21.1, minimum NeoForge 21.1.235.
- Build: unmodified checkout, JDK 21, `gradlew.bat build --no-daemon`, successful.
- Test artifact: `.local/deps/unlockedcamera-1.0.0.jar`; client-only, deliberately absent from the dedicated server.

Inspected `client/UnlockedCameraClient.java`, `mixin/GameRendererMixin.java`, `client/SableCompat.java`, metadata, build configuration, and README. The mod's `cameraRayPick` uses `Minecraft.gameRenderer.getMainCamera().getPosition()` and `Camera.getLookVector()` for the crosshair ray. It extends ray length by camera setback, clips blocks/entities, and filters reach against the real player. Its Sable helper handles plot-space hits and its camera code yields to a Sable vehicle camera. Shoulder offset, camera zoom, first-person freelook, and perspective behavior belong entirely to Unlocked Camera.

Rag Revival leaves those camera classes, settings, mixins, and rendered perspective unchanged. It selects only downed bodies along the current rendered camera ray. The required Sable adapter transforms that ray into each ragdoll limb's local space and clips its actual rotated outline, so selection is not tied to an upright player's collision box. Block obstruction locations are projected out of Sable plot space before comparing distances. Input packets carry the chosen player UUID; the server independently validates real-world player reach, state, items, and interaction continuity.

This establishes source-level integration and a successful build of the exact private camera revision. Rendering and aiming at both shoulder offsets, freelook, and moving ragdolls still require the two-client manual checklist; a compile is not visual compatibility validation.

## Carry On

- Upstream: <https://github.com/Tschipp/CarryOn/tree/1.21.1>
- Source commit: `9f1cf66c8fe4c4192f62b0b15da30b5f6e845f9a`
- Test version: `carryon-neoforge-1.21.1-2.2.6.13.jar`, mod version 2.2.6.
- Publisher metadata: <https://modrinth.com/mod/carry-on/version/PV8oLZ1q>
- Download: <https://cdn.modrinth.com/data/joEfVgkn/versions/PV8oLZ1q/carryon-neoforge-1.21.1-2.2.6.13.jar>
- SHA-512 verified: `e097b11d6f14e0957bab6c0276b81542e786cb86b9c31d9f25975f8c9fb04a734188b8790683298bbc5c695dc3de7d6e947616a20ad26d4a728a3beb0bb78667`.

Inspected NeoForge `CommonEvents` and `EntityPickupEvent`, and common `PickupHandler`, `CarryOnDataManager`, `CarryOnData`, `PlacementHandler`, and `CarryOnCommon`.

The exact APIs used are:

- `tschipp.carryon.events.EntityPickupEvent`: cancellable NeoForge event with public `ServerPlayer player` and `Entity target` fields. It fires before entity/player pickup.
- `CarryOnDataManager.getCarryData(Player).isCarrying()`: occupancy check for carried blocks, entities, and players.
- `CarryOnCommon.onRiderDisconnected(Player)`: clears a carrier's state when this player is carried.
- `PlacementHandler.placeCarried(ServerPlayer)`: Carry On's normal release/death placement path, used before downing a carrier so existing carried objects are not silently lost.

Carry On ordinarily requires both hands empty and its pickup key pressed. Player carrying calls `otherPlayer.startRiding(carrier, true)` and marks the carrier's data `CarryType.PLAYER`. Rag Revival refuses feeding/dragging by an occupied carrier and cancels Carry On pickup involving a downed player or a busy rescuer. Its client reserves use input on a downed body before vanilla/Carry On processing. Crouch-drag uses Sable's physics grip rather than creating a vanilla passenger relationship. The server rechecks occupancy while dragging/feeding. Ordinary, non-downed Carry On interactions remain available.

All optional Carry On references are inside a nested class loaded only when mod ID `carryon` is present. The mod is a compile-only dependency of Rag Revival, never bundled in the distributable JAR. Client and server test installations use the same selected optional version. Manual tests should enable Carry On's `pickupPlayers` setting when checking player pickup exclusion.

## Dragging and camera scroll (1.1.1)

Sable Ragdolls 0.7.2's `RagdollGrabClient` polls the raw Use key every client tick,
independently of the canceled interaction event. It can optimistically set a
native `activePos` on a downed limb even though the server rejects that generic
grip. Its NORMAL-priority mouse-scroll listener then cancels the event before
Unlocked Camera's LOW-priority zoom listener runs. On release, that duplicate
native grip also sends a release packet against the same limb used by our drag.

RagRevival 1.1.1 prevents native client target acquisition for exact synchronized
downed limbs and while a revival interaction owns the input. A native grip whose
limb becomes downed is cleared locally, without sending a release packet (server
downing already removed the old generic grip). The native `isGrabbing` collision
predicate includes our drag, including when Use is released but crouch stays held.

Unlocked Camera's code, settings and listener remain unchanged. A LOWEST-priority
RagRevival listener consumes only vertical wheel input still unclaimed during
our drag; this prevents vanilla hotbar changes if camera zoom is inactive or the
camera mod is absent. Already canceled input stays canceled, and horizontal-only
input is left alone. Ordinary native player/mob/dummy grabs retain their normal
targeting, release, collision grace, and scroll behavior.

## Scope of evidence

Private repository access and the optional camera build succeeded. Source-level hooks are known for the pinned optional versions. This document does not assert that untested releases or every physics/camera combination are compatible. Runtime results, including dedicated-server startup and multiplayer checks, are recorded in the main testing documentation.
