# RagRevival 1.1.0 verification

Tested on 2026-09-08 with Minecraft 1.21.1, NeoForge 21.1.249, Java 21.0.6,
Sable 2.0.3, Sable Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0.
Carry On 2.2.6.13 was installed on the dedicated server and both real clients;
Unlocked Camera 1.0.0 was installed on both clients.

Release JAR SHA-256:
`ec092680f94f389669e790c05c210cf50c6fd2e200ce87069d1af4fcb39da815`.

## Build and upgrade

- `gradlew.bat build testModJar` passed with Java 21.
- Six JUnit tests passed, with zero failures/errors.
- Both clients and the dedicated server loaded 1.1.0 successfully, including the
  new client-only renderer mixin.
- The existing server config automatically migrated from `restoredHealth = 6.0`
  to `restoredHealthFraction = 0.5`.
- The release JAR contains the outline classes/mixin and localized announcement,
  with no test harness or third-party classes.

## Actual rendered outline and chat

A stone wall was placed between ReviveOne and ReviveTwo in the isolated test
world. ReviveOne was downed with ordinary lethal damage. The real Minecraft
framebuffer showed a gold silhouette through the opaque wall, matching the
body's initial and settled ragdoll poses. This was checked in both spectator and
survival modes for the rescuer, with Unlocked Camera loaded.

After invoking the real revival handler through the test-only command, a second
framebuffer capture showed the outline removed while the wall remained. Server
health was exactly **10.0 HP**, with natural regeneration temporarily disabled.

The client's chat log and screenshot showed:
`ReviveOne is knocked and needs to be revived!`
An additional damage attempt while downed was rejected as invulnerable and
produced no second knockdown announcement. Lifecycle callback checks likewise
did not announce a fresh knockdown.

The geometry probe found all six synchronized limbs behind the wall;
`vanillaHitResult` was `BLOCK` and the actual camera picker selected no feeding
target. The rescue outline therefore did not bypass existing interaction checks.

Ignored local evidence files:

- `.local/client-two/screenshots/ragrevival-outline-wall.png`
- `.local/client-two/screenshots/ragrevival-outline-wall-settled.png`
- `.local/client-two/screenshots/ragrevival-outline-wall-survival.png`
- `.local/client-two/screenshots/ragrevival-outline-cleared.png`
- `.local/outline-wall-geometry.json`
- `.local/test-1.1.0-visual-client.log`

## Dedicated-server regression suite

Final run: **58 passed, 0 failed in 790 server ticks**, with both real clients
connected and the exact release JAR above installed. The separate test harness
invokes actual server gameplay APIs; it is not shipped in the release.

Successful completed-feeding checks verified:

- Normal maximum 20 HP → **10 HP** at the default fraction 0.5.
- Increased maximum 40 HP → **20 HP** at fraction 0.5.
- Increased maximum 40 HP → **10 HP** at configured fraction 0.25.
- Exactly one apple consumed per successful feeding; canceled feeding consumes
  none, and repeated/competing input cannot duplicate consumption.

The suite also passed damage immunity, mob targeting, native dragging/release,
downed movement/dismount lock, give-up timing, countdown/normal death game rules,
ordinary ragdoll distinction/conversion, totems, administrative/void deaths,
lifecycle callbacks, persisted deadline serialization, and three packet-codec
checks. The expanded harness restores its health/config/gamerule overrides.

Two preliminary runs exposed a fixture issue: teleporting the rescuer during
reach tests triggered ordinary Ragdoll Reactions and correctly prevented feeding.
The final harness uses that dependency's public short-lived suppression API
during the run to prevent artificial teleport impacts. Explicit ordinary-body
launch tests remain active. No reaction settings or production behavior were
changed to make the tests pass. Natural regeneration is disabled only during
the fixture's exact-health assertions and then restored.

The final raw server log is kept locally in `.local/test-1.1.0-passed.log`.

## Scope and remaining manual checks

The outline uses native Minecraft outline postprocessing on Sable's existing
limb render pass. The inherited block-entity render range is 64 blocks; limbs
must be loaded and in the viewer's dimension. It does not change player teams,
glowing potion effects, camera transforms, or ordinary ragdoll rendering.

Physical held-mouse feeding, camera shoulder/freelook modes, G rebinding, and
dragging feel still benefit from the [two-player checklist](../README.md#two-player-checklist).
Shader packs and alternative rendering mods were not tested. Original lifecycle,
totem, Carry On, and reach evidence is in [1.0.0 results](test-results.md).

## Runtime left running

The server and both visible Minecraft windows were restarted with the versioned
1.1.0 release and **no test harness**. RCON confirmed both players connected;
all three installed RagRevival JARs matched the release SHA-256.

| Process | PID | Address / identity |
| --- | --- | --- |
| Dedicated server | 26048 | `127.0.0.1:25575` |
| Client one | 36388 | `ReviveOne` |
| Client two | 36348 | `ReviveTwo` |

The temporary test wall was removed, both players were restored to survival,
and normal natural regeneration was restored. Required dependencies and Carry
On remain installed everywhere; Unlocked Camera remains on both clients.
