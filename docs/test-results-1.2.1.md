# RagRevival 1.2.1 verification

Tested 2026-09-08 on Java 21.0.6, Minecraft 1.21.1, NeoForge 21.1.249,
Sable 2.0.3, Sable Ragdolls 0.7.2, Ragdoll Reactions 0.7.0, and Carry On
2.2.6.13. Both clients also loaded the unchanged Unlocked Camera 1.0.0
private source build at `c13cfa3d`.

Release JAR SHA-256:
`b78ce44dd745eab8d78f3675da644e6638969d8861d1a57910aced0c950f3225`.

## Results

- `gradlew.bat build testModJar` passed; six JUnit tests passed with no failures.
- Both separate clients connected to the dedicated server with the 1.2.1 release.
- The production prompt renderer was inspected in a live Minecraft framebuffer
  at 25%, 50%, and 100% feeding progress. Its thin green bottom line filled to
  the corresponding widths without overlapping the keycap, label, or timer.
- The timer appeared as `0:54` inside the panel, separated from the action by a
  subtle divider. The separate player-name/downed sentence is removed.
- The preview also showed the tag-based equip hint and another rescuer's
  feeding state. Layout and color were visually checked.
- Code review confirmed that the downed player's own HUD/give-up display,
  bound-key handling, timer pause, and server gameplay logic were unchanged.

The opt-in `rescue-hud` screenshot draws the real production panel with known
synthetic snapshots and the client's current items/keys. It does not simulate
physical input or feed a player. Its local ignored evidence is
`.local/client-two/screenshots/ragrevival-rescue-hud.png`; reproduction is in
[runtime instructions](runtime.md).

The unchanged gameplay paths retain the prior
[76-check multiplayer evidence](test-results-1.2.0.md). That suite was not rerun
for this HUD-only release. Manual verification remains: hold a golden apple or
carrot on a downed teammate, watch the bottom line fill while the integrated
countdown stays paused, release to reset the fill and resume the clock, then
complete a feed. Check the combined timer also while crouch-dragging with the
user's rebound controls. Physical offset-camera input remains a manual check.

## Runtime left running

The preview harness was removed and its client restarted. Both clients and the
server run the versioned 1.2.1 JAR, with matching hashes and no test harness.
RCON confirmed both players connected; both Minecraft windows were responding
and reported multiplayer. The release archive contains no test or dependency
classes. Required dependencies and Carry On remain installed everywhere;
Unlocked Camera remains on both clients.

| Process | PID | Address / identity |
| --- | --- | --- |
| Dedicated server | 33356 | `127.0.0.1:25575` |
| Client one | 36708 | `ReviveOne` |
| Client two | 37216 | `ReviveTwo` |
