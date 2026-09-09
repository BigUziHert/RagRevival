# RagRevival 1.3.0 verification

Tested 2026-09-08 on Java 21.0.6, Minecraft 1.21.1, NeoForge 21.1.249,
Sable 2.0.3, Sable Ragdolls 0.7.2, Ragdoll Reactions 0.7.0, and Carry On
2.2.6.13. Both real clients also loaded the unchanged Unlocked Camera 1.0.0
private source build at `c13cfa3d`.

Release JAR SHA-256:
`3ce332ff7d5c78ee49e721bb1775f1d8a3e8e26b62ee5a988d280e2f2b37069d`.

## Results

- `gradlew.bat build testModJar` passed; six JUnit tests passed.
- The final dedicated-server suite passed **143 checks, zero failures,
  1,439 server ticks**, with ReviveOne and ReviveTwo connected in separate clients.
- Standing teammate feeding was rejected. Releasing crouch during feeding
  canceled without consumption and resumed the downed timer.
- Mounted self-revival worked without crouching, with golden apples and golden
  carrots, including an offhand carrot with the main hand empty. Cancellation
  reset progress without consumption; completion restored half health and
  consumed exactly one item from the correct hand.
- Self/teammate ownership contention passed in both acquisition orders.
  Repeated packets could not advance progress or grant duplicate consumption.
  Downed players could not feed another player or drag themselves.
- Give-up completed normally during an unfinished self-feed, clearing ownership
  and consuming no revival item.
- Native item-use state and correct hand were verified during feeding, with
  cleanup on cancellation and success. A 64-tick self-feed exceeded vanilla's
  32-tick eating duration without early consumption, food, saturation, or buffs.
- Ordinary golden-apple completion after teammate cancellation and successful
  self-revival consumed one item and applied normal nutrition and effects.
- The suite retained the prior checks for damage/mob immunity, movement,
  dragging, server reach validation, death/game rules, totems/void/kill,
  lifecycle callbacks, configured health, Carry On exclusion, and codecs.
- Live Minecraft rendering of the compact prompts was visually checked using
  synthetic progress snapshots: teammate crouch/Use keycaps, self Use-only hint,
  integrated timer, and green bottom fill at 25%, 50%, and 100% fit correctly.
- A real downing was also visually inspected from the downed client's Sable
  camera: the self-revival prompt, item/key, countdown, and give-up hint were
  readable. The player was revived with the test-only cleanup command afterward.

## Method and limits

The harness calls actual server gameplay methods while both clients receive
network and physics updates. The new self/teammate contention cases use the two
real players; the older third-contender check uses a synthetic server actor.
Ordinary food checks call native start and completion in one server callback,
testing the mixin's routing after cleanup rather than a physical 32-tick hold.
The real-client HUD preview uses synthetic snapshots without changing gameplay.

This run did not simulate physical mouse/key holds, restart during an active
self-feed, or visually measure the eating transform over time. Lifecycle
callbacks and the unchanged persistent clock were exercised; prior actual
reconnect/restart evidence remains in [1.0.0 verification](test-results.md).
Offset-camera physical targeting and zoom combinations remain manual checks.

Sable hides seated first-person hands and renders rigid limbs. Self-revival
therefore uses mouth crumbs and chewing sound, without an arm-to-mouth pose.
Native eating motion is visible where vanilla hands render. Food effects are
suppressed during revival, and tagged non-food items do not activate native
tool actions. Mouth positioning, plotyard particle/sound delivery, and the
animation hooks were inspected in [dependency source](eating-animation.md).

Local ignored evidence: `.local/test-1.3.0-passed.log`, Gradle XML results, and
`.local/client-two/screenshots/ragrevival-rescue-hud.png` and
`ragrevival-self-ready-clear.png`. Reproduce with
[runtime instructions](runtime.md). The test harness is never in the release JAR.

## Runtime left running

Both clients and the dedicated server were restarted with the versioned release
and the harness removed. All three installed JAR hashes match the release above;
the release archive contains neither test classes nor dependency classes. RCON
confirmed both players connected at full health; both visible Minecraft windows
were responding and reported multiplayer. Golden apples and carrots are ready.

| Process | PID | Address / identity |
| --- | --- | --- |
| Dedicated server | 31712 | `127.0.0.1:25575` |
| Client one | 3396 | `ReviveOne` |
| Client two | 32160 | `ReviveTwo` |

Required dependencies and Carry On remain installed everywhere; Unlocked Camera
remains on both clients. The upstream nonfatal distribution and optional
`create:flywheel` tag messages documented in
[dependency startup](optional-dependency-startup.md) remain outside this change.
