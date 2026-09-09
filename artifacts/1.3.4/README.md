# RagRevival 1.3.4

Replace the previous RagRevival JAR with `ragrevival-1.21.1-1.3.4.jar` on
the server and clients while those instances are stopped. Source and SHA-256
checksums are alongside the distributable.

This release removes 1.3.3's explicitly empty NeoForge and Sable dependency
range fields, selecting FML's unbounded default. The empty values did not
behave like omitted fields in FML 4.0.43 and still rejected the reported
installation. The earlier claim that 1.3.3 fixed that rejection was incorrect.

Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls 0.7.2, and Ragdoll
Reactions 0.7.0 remain required. Dependencies retain their own requirements;
broad version acceptance does not guarantee every API version works. The
build baseline remains NeoForge 21.1.249 and Sable 2.0.3. Gameplay, HUD,
and network protocol 3 are unchanged. Dependencies are not bundled.

See the [range correction and targeted loader validation](../../docs/version-compatibility-1.3.4.md).
Loader parsing is separate from game startup or multiplayer verification;
[1.3.1](../../docs/test-results-1.3.1.md) remains the latest multiplayer-tested
release.
