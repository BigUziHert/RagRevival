# Version compatibility in 1.3.4

The reported FML 4.0.43 installation still rejected RagRevival 1.3.3's
NeoForge and Sable dependencies, displaying blank required ranges. That
release used `versionRange=""`, incorrectly treating a present empty value
as equivalent to an omitted range. The earlier claim that 1.3.3 removed
the startup block is withdrawn.

## Range correction

RagRevival 1.3.4 omits `versionRange` for NeoForge and Sable, selecting
FML's canonical unbounded default. Both dependencies remain required on both sides.
Minecraft remains exactly 1.21.1, with Java 21, JavaFML 4, Sable: Ragdolls
0.7.2, and Ragdoll Reactions 0.7.0 required. Other mods retain their own
dependency restrictions.

In the inspected [FML 4.0.43 source JAR](https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.43/loader-4.0.43-sources.jar), `IModInfo.UNBOUNDED` is created from a
single-space version specification. `ModInfo.ModVersion` uses that default
only when the `versionRange` field is absent. A present empty string goes
through range parsing instead and accepts no versions. This distinction
was missed in 1.3.3's metadata review.

The build/download baseline stays NeoForge 21.1.249 and Sable 2.0.3. The
[earlier dependency API inspection](version-compatibility-1.3.3.md) remains
source evidence for the reported NeoForge 21.1.248 and Sable 2.0.5 versions;
it does not establish universal compatibility with older or future APIs.
Gameplay, the HUD, and network protocol 3 are unchanged.

## Targeted loader validation

Assembly succeeded. Java 21 loaded the actual FML 4.0.43 and 4.0.44 JARs
in separate processes with Maven Artifact 3.8.5. Each check read the
packaged `META-INF/neoforge.mods.toml` using NightConfig's TOML parser and
constructed FML's actual `ModInfo.ModVersion` through `NightConfigWrapper`.
Results for both loader versions:

- Reproduced 1.3.3 rejecting NeoForge 21.1.248 and Sable 2.0.5.
- Confirmed 1.3.4 selects the loader's actual `IModInfo.UNBOUNDED` instance
  for both dependencies and accepts those two installed versions.
- Also confirmed range acceptance for NeoForge 21.1.1, 21.1.219, 21.1.228,
  21.1.249 and 21.1.999, and Sable 1.1.0, 1.1.1, 2.0.3 and 3.0.0.
  These values exercise range semantics, not API support claims.
- Confirmed all required dependencies still use `REQUIRED` and `BOTH`,
  Minecraft 1.21.1 is accepted while 1.21 and 1.21.2 are rejected,
  Ragdolls 0.7.2 and Reactions 0.7.0 are accepted, and Carry On is optional.

The focused checks passed; logs are kept in the ignored local research
directory at `.local/compat/loader/metadata-4.0.43.log` and
`metadata-4.0.44.log`. No Minecraft instance was started or changed and no
multiplayer test was run. The [1.3.1 report](test-results-1.3.1.md) remains
the latest multiplayer evidence on the original build baseline.
