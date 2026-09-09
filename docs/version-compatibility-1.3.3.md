# Version compatibility in 1.3.3

**Superseded by [1.3.4](version-compatibility-1.3.4.md).** The explicit empty
ranges shipped in 1.3.3 still caused dependency rejection under FML 4.0.43.
An empty value is not equivalent to omitting the range in that loader. The
earlier claim that 1.3.3 removed the startup block was incorrect.

The reported server failed during dependency validation: RagRevival 1.3.2
required NeoForge `[21.1.249,21.2)` and Sable `[2.0.3]`, whereas the server
had NeoForge 21.1.248 and Sable 2.0.5. No RagRevival gameplay code had run.

## Loading policy

RagRevival 1.3.3 declares an explicit empty `versionRange` for NeoForge and
Sable. The default described in NeoForge's
[dependency configuration reference](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/#dependency-configurations)
was incorrectly applied to a present-but-empty value. This did not produce
the intended unbounded range in FML 4.0.43.
Both dependencies remain required on both sides. Minecraft remains exactly 1.21.1;
Sable: Ragdolls 0.7.2 and Ragdoll Reactions 0.7.0 remain required. The Java
21 and JavaFML 4 requirements are unchanged. No dependency is bundled.

Use 1.3.4 for the corrected range declaration. A broad declaration does not
override other mods' requirements or promise that every historical or
future API works. For example, Sable 2.0.3 and 2.0.5 themselves require
NeoForge 21.1.228 or later. Builds for other Minecraft versions are outside
this mod's scope. Keep the same mod versions on the server and clients.

The build/download lock remains on NeoForge 21.1.249 and Sable 2.0.3 to
preserve the previously tested, reproducible baseline; it is distinct from
the runtime version policy.

## Inspected NeoForge sources

The official [21.1.248 source JAR](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.248/neoforge-21.1.248-sources.jar)
and [21.1.249 source JAR](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-sources.jar)
each contain 953 Java files. Comparing every Java entry found identical
names and contents, including the events, configuration and networking
APIs RagRevival uses. This is source evidence for removing the 21.1.249
minimum, not a server startup or gameplay test of 21.1.248.

| Artifact | SHA-256 |
| --- | --- |
| NeoForge 21.1.248 sources | `b4b912457549ff586873ce78b39b08af96f11a0e3a54c6b77576c6a25907083f` |
| NeoForge 21.1.249 sources | `59d680299ee7c02f296875dfc772e56d1b3fef952068d0fc76cf1d2916d35d3d` |

## Inspected Sable 2.0.5 API

Inspected the published [Sable 2.0.5 NeoForge JAR](https://cdn.modrinth.com/data/T9PomCSv/versions/U678xqle/sable-neoforge-1.21.1-2.0.5.jar)
and upstream [release source at `6966d2928340de7631abcecf8549904b877df0a8`](https://github.com/ryanhcode/sable/tree/6966d2928340de7631abcecf8549904b877df0a8),
tag `mc1.21.1-2.0.5-neoforge`. The JAR's SHA-256 is
`c8710f85bc780bbf523e6726ceaaf76dd0b8c61479db497b382c6bb34317e7ab`.
The download also matched the SHA-512 published by Modrinth.

Java 21 `javap -public -s` output was identical to 2.0.3 for `Sable`,
`ActiveSableCompanion`, `SubLevelContainer`, `SubLevel`, `ServerSubLevel`
and `SubLevelPhysicsSystem`. These cover RagRevival's direct Sable API
usage for projections, sublevels and physics poses. Its nested Companion
1.6.0 is byte-identical to our compile dependency (SHA-256
`873633e35046e3761b277ff8a1ecad0d55d9a3014fa81a0b084c9aecba1f3bed`),
covering `ClientSubLevelAccess.renderPose()` and the `Pose3dc` transforms.
No adapter change was indicated by this API inspection.

Sable 2.0.5 bundles Veil 4.3.2, compared with 4.1.4 in 2.0.3. RagRevival
does not call Veil directly, but this library difference and actual physics
behavior were not exercised at runtime.

Older versions are not universally interchangeable: Ragdolls 0.7.2 and
Reactions 0.7.0 independently require Sable 1.1.0 or later. The inspected
Sable 1.1.1 bundles Companion 1.5.0 and enforces its own upper limit on that
library. Removing RagRevival's pin cannot remove those upstream limits.

## Release verification scope

`gradlew.bat assemble` succeeded for 1.3.3 and produced the distributable
and source JARs. The packaged `META-INF/neoforge.mods.toml` was inspected,
but its empty ranges were mistakenly treated as unrestricted. Assembly and
text inspection did not establish the loader's actual range semantics.
No automated or gameplay tests were run, preserving the user's earlier
request. Existing test clients and the local server were not changed.
The [1.3.1 report](test-results-1.3.1.md) remains the latest executed
multiplayer evidence, using NeoForge 21.1.249 and Sable 2.0.3.
