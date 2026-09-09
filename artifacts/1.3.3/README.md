# RagRevival 1.3.3

Replace the older RagRevival JAR with `ragrevival-1.21.1-1.3.3.jar` in the
server and every client's `mods` folder while those instances are stopped.
Source and SHA-256 checksums are alongside the distributable.

RagRevival no longer imposes a version range on NeoForge or Sable. This
removes its startup rejection of NeoForge 21.1.248 and Sable 2.0.5. Minecraft
1.21.1, Java 21, Sable, Sable: Ragdolls 0.7.2 and Ragdoll Reactions 0.7.0
are still required. Dependencies retain their own requirements. Gameplay,
the compact HUD, and network protocol 3 are unchanged.

Version acceptance does not guarantee every past or future API is compatible.
See the [source/API inspection and its limits](../../docs/version-compatibility-1.3.3.md).
Assembly succeeded; no automated or gameplay tests were run, preserving
the user's earlier request. Third-party dependencies are not bundled.
