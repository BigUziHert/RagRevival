# RagRevival 1.1.1 verification

Tested 2026-09-08 on Java 21.0.6, Minecraft 1.21.1, NeoForge 21.1.249,
Sable 2.0.3, Sable Ragdolls 0.7.2, Ragdoll Reactions 0.7.0, and Carry On
2.2.6.13. Both real clients also loaded the unchanged Unlocked Camera 1.0.0
private source build at `c13cfa3d`.

Release JAR SHA-256:
`6cc05aaed62fdd1ba865d62023f14f72525e759c084c0388d3e6974ca0629369`.

## Results

- `gradlew.bat build testModJar` passed. Six JUnit tests passed.
- Both clients and the dedicated server loaded the new release, including the
  client-only grab-input mixin.
- The dedicated-server regression suite passed **58 checks, zero failures,
  790 server ticks** with both clients connected.
- The new live-client scroll fixture passed **30 checks, zero failures** using
  real synchronized downed limbs and a separately spawned ordinary same-profile
  dummy. The actual Unlocked Camera listener ran through NeoForge's event bus.

The scroll fixture verified that the first wheel event clears a recognized
phantom native grab before native NORMAL-priority cancellation. While our drag
owns input, a wheel-up event changed Unlocked Camera's target distance from
**4.0 to 3.478261**; wheel-down after Use release returned it to **4.0**.
Both events were consumed by zoom before vanilla could change the hotbar.

It also verified native downed-limb target exclusion, collision suppression while
crouch-drag continues after Use release, cleanup on the native tick, retention
of already-canceled events, vertical hotbar protection with the camera inactive,
horizontal-only passthrough, and normal idle scroll behavior.

The separate ordinary same-profile dummy remained natively targetable and
retained native grab/scroll behavior while idle. An existing ordinary grip
yielded on the first scroll when a rescue took input ownership; zoom then ran.
Native target acquisition was rejected while dragging or feeding a downed player.

The server suite separately verified feeding/item consumption, half-health and
custom maximum-health restoration, native dragging/release, mobility/dismount
lock, immunity/mob targeting, ordinary ragdoll conversion, countdown/give-up,
inventory/XP gamerules, totem/void/kill handling, lifecycle callbacks, and codecs.

## Method and limits

These are programmatically posted scroll events in a running real client, with
temporary client input/camera fields restored before the next tick. They are not
physical mouse-wheel gestures or a visual camera-mode test. The native wheel
blocking baseline temporarily removed the exact target snapshot so the same
physical limb classified as ordinary; its native cancellation prevented zoom.
The restored downed state then exercised the fix. Takeover tests emitted native
release packets for fixture-seeded local grips on an idle test rescuer.

An ordinary mob ragdoll was not loaded during this run, so the optional separate
mob-grab probe was deferred. The same original native mob targeting branch is
retained when no rescue owns input. Other camera versions, shader packs, and
physical shoulder/freelook/held-input combinations remain manual checks.

Local ignored evidence: `.local/test-1.1.1-scroll.json` and
`.local/test-1.1.1-passed.log`. Reproduce with the [runtime instructions](runtime.md).
The test harness is separate and never included in the distributable JAR.

## Runtime left running

Restarted with the versioned 1.1.1 release and the test harness removed.
All three installed JAR hashes match the release above. RCON confirmed both
players connected and both visible Minecraft windows reported multiplayer.

| Process | PID | Address / identity |
| --- | --- | --- |
| Dedicated server | 10624 | `127.0.0.1:25575` |
| Client one | 6016 | `ReviveOne` |
| Client two | 30268 | `ReviveTwo` |

Required dependencies and Carry On remain installed everywhere; Unlocked Camera
remains installed, unmodified, on both clients.
