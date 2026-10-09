# RagRevival 1.3.7

Install `ragrevival-1.21.1-1.3.7.jar` on the server and every client,
replacing the previous RagRevival JAR while the instances are stopped.
Source and SHA-256 checksums are alongside it.

This release removes the exact Sable: Ragdolls 0.7.2 dependency pin that
blocked startup after upgrading to 0.7.5. RagRevival's loader metadata now
accepts all Ragdolls versions. The mod is still required on both sides,
and its own dependency requirements still apply.

Pending pose cleanup also handles Ragdolls 0.6.9 and 0.7.0, which lack the
extra index present in newer releases. The complete 0.6.9 combination still
fails in required Ragdoll Reactions 0.7.0 due to its missing mob API; see
the verification notes for supported startup combinations.

Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls, and Ragdoll
Reactions 0.7.0 are required. Dependencies are not bundled. Network protocol
3 and the standing/crouching drag and feeding controls from 1.3.6 are retained.

See [verification notes](../../docs/test-results-1.3.7.md) for the versions
checked and the limits of the compatibility evidence. Removing a version
restriction does not guarantee every past or future upstream API is compatible.
