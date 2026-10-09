# RagRevival 1.3.7 verification

## Dependency correction

RagRevival 1.3.6 required exactly Sable: Ragdolls 0.7.2 using
`versionRange="[0.7.2]"`. FML therefore rejected an installed 0.7.5 before
Minecraft could start. Version 1.3.7 omits that field, using FML's unbounded
default. An explicitly empty range is not equivalent and must not be used.

Sable: Ragdolls is still required on both clients and the dedicated server,
with `ordering="AFTER"`. Minecraft remains exactly 1.21.1, Ragdoll Reactions
remains exactly 0.7.0, and Carry On remains optional. The dependency lock and
compile baseline retain Ragdolls 0.7.2 for reproducible builds. Runtime
permission to install another version is separate from the build baseline.

The adapter also handles an older API difference: Ragdolls 0.6.9 and 0.7.0
store pending pose requests only in `PENDING`, while later releases add a
`PENDING_PLAYERS` index. Requiring a mixin accessor for that missing field
would fail on the older releases. The index is now resolved once and cleared
only when present; the required request-map and player-ID accessors remain.
Unexpected field types or access failures are not silently ignored.

## Build and loader checks

- `gradlew.bat build testModJar` passed using Java 21 on September 18, 2026.
- All 15 JUnit tests passed with no failures, errors, or skips: six loader
  metadata tests, three pending-pose compatibility tests, two clock tests,
  and four hold/heartbeat tests. The new adapter tests cover an absent index,
  selective cancellation in the live index, and rejection of an incompatible
  existing field.
- The real FML 4.0.43 dependency parser reproduces the old pin accepting
  0.7.2 and rejecting 0.7.5, then accepts 0.7.5 with the production metadata.
- The Ragdolls dependency resolves to `IModInfo.UNBOUNDED`. Representative
  range checks accept 0.6.9, 0.7.0, 0.7.2, 0.7.3, 0.7.4, 0.7.5, 0.8.0,
  and 1.0.0. These are loader acceptance tests; they do not claim each
  version exists or has been run in Minecraft.
- The packaged production JAR has version 1.3.7, the unrestricted required
  Ragdolls dependency, and no test harness classes.
- Production JAR SHA-256:
  `a616cd8e78a979ba4d01e162eb058ecb1d75a4795bd569e442a629912e89609e`.

## Published binary API checks

The [binary API matrix](ragdolls-api-matrix-1.3.7.json) records official
artifact URLs, hashes, dependency metadata, and exact member descriptors for
all 16 published releases returned by Modrinth's Minecraft 1.21.1 NeoForge
version listing. The final 1.3.7 production JAR was checked against each one.

Ragdolls **0.6.9, 0.7.0, 0.7.2, 0.7.4, and 0.7.5** each matched all
28 referenced Ragdolls-owned public members, all 10 required private mixin
targets, and all 17 referenced API classes/interfaces. The optional
`PENDING_PLAYERS` field is absent in the first two and present in the last
three. These checks cover descriptors and static/instance consistency;
they do not execute client mixins or prove gameplay behavior.

The official [0.7.5 release](https://modrinth.com/mod/sable-ragdolls/version/0.7.5)
was downloaded from its Modrinth CDN URL and verified against the published
SHA-512. Its SHA-256 is
`69611deff1c2b18ae0ae7dbaea24cc14f4b69f0368297653933ae9b33adf2d2b`.
The exact renderer `renderLayers` invocation targeted by the outline mixin
was also verified in this binary's bytecode.

The other 11 older published releases lack required APIs. They are already
outside Ragdoll Reactions 0.7.0's Ragdolls `[0.6.9,)` requirement. No upstream
dependency metadata was modified to bypass those requirements.

## Dedicated server and live adapter checks

The final production JAR was run on Minecraft 1.21.1, Java 21, NeoForge
21.1.249, Sable 2.0.3, and Ragdoll Reactions 0.7.0 in separate fresh worlds
bound to loopback. Only official published dependency binaries were used.
The [runtime report](ragdolls-startup-1.3.7.json) records hashes, timestamps,
log paths, and outcomes.

| Ragdolls | Reached server `Done` | Live adapter probe | Clean shutdown |
| --- | --- | --- | --- |
| 0.6.9 | No: upstream Reactions failure | Not reached | Exited during mod loading |
| 0.7.0 | Yes | Passed; optional index absent | Saved all dimensions, exit 0 |
| 0.7.2 | Yes | Passed; optional index present | Saved all dimensions, exit 0 |
| 0.7.5 | Yes | Passed; optional index present | Saved all dimensions, exit 0 |

A separate test-only probe forced bridge and async-request class initialization,
verified the live pending-map accessor, applied the `PendingLaunch` accessor
mixin, and invoked pose cancellation with synthetic UUIDs. Newer versions
removed the target UUID from the optional live index while retaining another
player. The probe is not included in the distributable. All test processes
were stopped; existing user runtime directories and worlds were untouched.

The three successful runs had no fatal linkage or mixin application failures.
Their nonfatal upstream client-class probes, refmap warnings, and optional
`create:flywheel` messages are distinct from the tested dependency failure.
The probe wrote its PASS marker to stdout, so the aggregate report reads
`console.log` as well as `logs/latest.log`; the initial runner's combined
ready flag missed that marker and stopped at its three-minute bound.

## Scope

Ragdolls **0.6.9** passes the static RagRevival API check but fails actual
startup with required Ragdoll Reactions 0.7.0. Reactions throws
`NoClassDefFoundError: dev/leo/sableplayerragdoll/mob/api/MobRagdollEndEvent`
from its mod constructor. Its declared `[0.6.9,)` requirement is therefore
broader than its actual API compatibility. This is an upstream incompatibility;
the 0.6.9 combination is not a supported runtime based on these results.

Network protocol 3 and gameplay controls are unchanged from 1.3.6. The multiplayer
harness compiled; its full gameplay matrix and physical client input checks
were not rerun for this release. Historical evidence is linked from
[1.3.6 verification](test-results-1.3.6.md) and
[1.3.5 verification](test-results-1.3.5.md).

All Sable: Ragdolls versions are accepted by RagRevival's loader declaration.
Each dependency's own Minecraft, NeoForge, Sable, and other requirements still
apply. In particular, required Ragdoll Reactions 0.7.0 itself requires Ragdolls
0.6.9 or later in metadata and fails with 0.6.9 as described above; older
Ragdolls releases also lack APIs used by RagRevival.
Future incompatible API changes can require adapter work; unrestricted
metadata alone cannot establish universal runtime compatibility.
