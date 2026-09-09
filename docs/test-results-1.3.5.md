# RagRevival 1.3.5 verification

Tested 2026-09-08 with Java 21.0.6 and Minecraft 1.21.1. The production
JAR keeps the existing gameplay adapter and omitted NeoForge/Sable range
fields. New durable tests exercise FML's actual dependency parser.

The same release JAR is installed across the five profiles. SHA-256:
`e03f414c431e9ee98c65333f426281c2eeaae60292fb60e479be334e722e7863`.

## Build and loader regression

`gradlew.bat build testModJar` passed. **Eleven JUnit tests passed**, with
zero failures, errors, or skipped tests: six clock/hold tests and five
dependency metadata tests using FML 4.0.43 and NightConfig.

The metadata tests read the production resource, reproduce the explicit-empty
range rejection, and confirm the omitted default accepts NeoForge 21.1.248 /
21.1.249 and Sable 2.0.3 / 2.0.5. They also retain mandatory dependencies
on both sides, exact Minecraft 1.21.1 scope, and optional Carry On. See the
[original loader correction](version-compatibility-1.3.4.md) for the earlier
packaged-JAR parser investigation.

## Real multiplayer matrix

Each completed row used a dedicated server and two separately launched real
clients, `ReviveOne` and `ReviveTwo`. Sable Ragdolls 0.7.2, Ragdoll Reactions
0.7.0, and Carry On 2.2.6.13 were installed on all three instances. The
test harness was installed only for validation.

| NeoForge | Sable | Dedicated-server suite | Client geometry | Unlocked Camera scroll |
| --- | --- | --- | --- | --- |
| 21.1.249 | 2.0.3 | 144 passed, 0 failed; 1,439 ticks | Six parts; 4/4 rays hit | 30/30 synthetic checks passed |
| 21.1.248 | 2.0.5 | 144 passed, 0 failed; 1,439 ticks | Six parts; 4/4 rays hit | 30/30 synthetic checks passed |
| 21.1.219 | 1.1.1 | 144 passed, 0 failed; 1,439 ticks | Six parts; 4/4 rays hit | Omitted: camera requires 21.1.235+ |
| 21.1.228 | 2.0.5 | 144 passed, 0 failed; 1,439 ticks | Six parts; 4/4 rays hit | Omitted: camera requires 21.1.235+ |
| 21.1.250 | 2.0.5 | 144 passed, 0 failed; 1,439 ticks | Six parts; 4/4 rays hit | 30/30 synthetic checks passed after fixture reset |

All five dedicated-server runs passed: **720 checks, zero failures**.
The 250 scroll probe initially timed out because its required fixture state
was unavailable, including a downed rescuer. After restarting the clients
and resetting the actors, it passed 30/30. The isolated rescuer was placed
in creative mode to protect that event-only fixture from further damage;
the 144-check gameplay suite used survival players. The precondition failure
is retained in `250-scroll-fixture-precondition.json` in the local evidence.

The server suite exercises downing, native movement/seat locking, mob and
damage immunity, inventory/XP death rules, teammate crouch requirements,
self-revival and offhand use, cancellation, exact item consumption, target
ownership, countdown pause/expiry, give-up priority, native drag, actual
server reach, ordinary ragdoll distinction, totems, void/kill, lifecycle
callbacks, guarded food effects, Carry On exclusion, and packet codecs.

Geometry uses actual synchronized limbs with four synthetic eye/offset rays.
Scroll checks post synthetic wheel events through the real client event bus
with Unlocked Camera 1.0.0, private source commit `c13cfa3d`, and verify zoom,
native grip ownership, collision behavior, and hotbar protection. These
checks do not simulate physical mouse gestures or establish every camera
mode's appearance.

## Actual restart on the reported combination

On NeoForge 21.1.248 / Sable 2.0.5, all three processes were stopped and
restarted while ReviveOne was downed. The persisted deadline remained
exactly **1788918314230**. Remaining time fell from **89.154 seconds** before
restart to **6.603 seconds** afterward. The timer did not reset; offline
and server downtime counted. This is a real process restart, separate from
the suite's lifecycle-callback checks.

## Public API coverage

The production JAR references 20 public Sable members. Exact descriptors
resolved through candidate class hierarchies and embedded Companion JARs
matched **20/20** for each of **1.1.1, 1.1.3, 1.2.1, 1.2.2, 2.0.0, 2.0.1,
2.0.2, 2.0.3, 2.0.4, and 2.0.5**, with all nine concrete API classes present.
The [API matrix](sable-api-matrix-1.3.5.json) records hashes, URLs, exact
members, and upstream dependency requirements. No adapter change was needed.

The same inspection resolved **50/50 NeoForge-owned members** for every
one of the **28 published releases from 21.1.219 through 21.1.250**. Patches
237, 239, 245 and 246 are not listed in the official Maven metadata. Exact
public descriptors, inherited resolution and static/instance consistency
were checked. The [NeoForge API matrix](neoforge-api-matrix-1.3.5.json)
records artifacts and results. This audit excludes Minecraft-patched
methods, separate FML/event-bus APIs, mixin application and runtime behavior.

This is static API coverage, not ten complete gameplay runs. Reviewed Sable
1.x releases require NeoForge 21.1.219+, while 2.x requires 21.1.228+.
RagRevival's broad range policy does not override these or other mods'
requirements. These results do not guarantee every NeoForge patch, future
Sable release, or arbitrary modpack.

## Log review and remaining manual checks

The completed runs had no blocking RagRevival exceptions or API linkage /
mixin application failures. The test-only 250 fixture timeout is described
above. Sable
logs missing optional Create/Flywheel properties and discards stale movement
packets around ragdoll removal/recreation. Baseline/219 server mixin probing
catches and skips virtual client-only Sable targets. The 219 clients each
logged one Veil ImGui disposal/desync pair before connecting, then continued
rendering and completed the suite. Fixture teleports also produce movement
warnings. These messages are retained in the evidence rather than treated
as RagRevival failures.

Manual verification remains for physical crouch/Use/G holds and rebinding,
offset-camera aiming while moving, drag feel, mouth effects/audio, and
interactions in other modpacks. Sable's hidden seated hands and rigid limbs
retain the limitations in [eating presentation](eating-animation.md).

Reproduce with [compatibility runtime instructions](compatibility-runtime.md).
Ignored evidence is under `.local/compat/evidence-1.3.5`, including profile
gameplay logs, geometry/scroll JSON, and restart snapshots; profile directories
retain client/server logs. The harness is excluded from the distributable.

## Final manual runtime

NeoForge 21.1.248 / Sable 2.0.5 is running with RagRevival 1.3.5 on both
clients and the server, required dependencies and Carry On everywhere,
and Unlocked Camera on the clients. The harness was removed from all
profiles after stopping them. Installed release hashes on all three final
instances match the artifact above. RCON confirmed both players connected;
both game windows are responding and display multiplayer.

| Process | PID | Address / identity |
| --- | --- | --- |
| Dedicated server | 30528 | `127.0.0.1:25585` |
| Client one | 36564 | `ReviveOne` |
| Client two | 43124 | `ReviveTwo` |

The final profile is `.local/compat/runtime-248`; all other test profiles
are stopped. Both players have golden apples, golden carrots and a sword.
Difficulty is normal with ambient mob spawning disabled for quiet manual
testing. Both players are conscious; their downed data is empty.
