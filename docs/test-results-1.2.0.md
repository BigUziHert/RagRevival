# RagRevival 1.2.0 verification

Tested 2026-09-08 on Java 21.0.6, Minecraft 1.21.1, NeoForge 21.1.249,
Sable 2.0.3, Sable Ragdolls 0.7.2, Ragdoll Reactions 0.7.0, and Carry On
2.2.6.13. Both real clients also loaded the unchanged Unlocked Camera 1.0.0
private source build at `c13cfa3d`.

Release JAR SHA-256:
`59b5a2e5b1687c4d1bfabfcb783aded3f4b86c69e28cd92e4d6d36bbcbf6950e`.

## Results

- `gradlew.bat build testModJar` passed. Six JUnit tests passed.
- The dedicated-server integration suite passed **76 checks, zero failures,
  894 server ticks** with ReviveOne and ReviveTwo connected in separate clients.
- Golden-carrot feeding passed cancellation without consumption, a fresh full
  feeding duration after cancellation, half-health revival, exactly one carrot
  consumed on success, and no duplicate consumption from late packets.
- A feed started with **300 milliseconds** remaining kept the target alive past
  the original deadline and completed the full 32-tick interaction successfully.
- Valid feeding preserved remaining time within 10 milliseconds. Cancellation
  retained the starting remaining time within 20 milliseconds, then the clock
  decreased again. Dragging did not pause it.
- Same-tick repeated input and rejected contenders did not change the deadline.
  An already-expired target rejected a new feed without extending its deadline.
- Rescuer logout and downing callbacks canceled feeding and resumed the target's
  countdown. Countdown death after canceled feeding still handled drops and XP.
- Actual live-client screenshots were inspected for the golden-carrot revive
  hint and empty-handed drag hint. Item icons/keycaps fit the compact panel;
  the existing downed name/time line remained intact above it.

The suite also retained its checks for golden apples, configured health fractions
and modified maximum health, lock contention, reach/stack/heartbeat cancellation,
native ragdoll movement and drag/release, mob targeting and damage immunity,
give-up, death/game rules, totems, void, administrative kill, lifecycle callbacks,
ordinary ragdoll distinction, Carry On pickup cancellation, and packet codecs.

## Method and limits

The harness invokes real server gameplay APIs while two running clients receive
state and physics synchronization. It uses a synthetic third server actor for
lock contention. It does not simulate physical mouse/key holds. Logout/downing
pause checks call lifecycle/gameplay methods; this release did not physically
restart the server during an active feed. Clean-stop pause settlement and the
absence of a persisted pause flag were reviewed in source. The original real
reconnect/restart evidence is in [1.0.0 verification](test-results.md).

The timer interpolation change was reviewed in source and the server pause was
measured by the suite. A human should still check the HUD during a physical
last-second hold, release, and reach cancellation. Camera shoulder/freelook
aiming and mouse-wheel gestures remain manual checks. The unchanged scroll
integration's prior live-client event tests are in
[1.1.1 verification](test-results-1.1.1.md); they were not rerun for this release.

Local ignored evidence: `.local/test-1.2.0-passed.log`, Gradle XML test results,
and `.local/client-two/screenshots/ragrevival-rescue-carrot.png` /
`ragrevival-rescue-empty.png`. Reproduce with the [runtime instructions](runtime.md).
The release JAR was inspected: it contains neither the test harness nor
third-party dependency classes.

## Runtime left running

Restarted with the versioned 1.2.0 release and the test harness removed.
All three installed JAR hashes match the release above. RCON confirmed both
players connected; both visible Minecraft windows reported multiplayer and
were responding. Both players were supplied golden apples and golden carrots.

| Process | PID | Address / identity |
| --- | --- | --- |
| Dedicated server | 37168 | `127.0.0.1:25575` |
| Client one | 32096 | `ReviveOne` |
| Client two | 40592 | `ReviveTwo` |

Required dependencies and Carry On remain installed everywhere; Unlocked Camera
remains installed, unmodified, on both clients. Startup still logs upstream
`ClientLevel` distribution messages and Sable's optional `create:flywheel`
block-tag error with Create absent, as in the
[original dependency startup](optional-dependency-startup.md). These did not
prevent either client from joining or the multiplayer suite from passing.
