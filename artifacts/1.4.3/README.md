# RagRevival 1.4.3

Install `ragrevival-1.21.1-1.4.3.jar` on the server and every client.
Stop each instance before replacing its previous RagRevival JAR.
Use matching Sable and Sable: Ragdolls versions across all instances.

- Skip camera calculations, the block raycast, Sable coordinate calculations,
  and the player scan when the client's downed-state map is empty.
- Skip feeding-state clock/stream work and ragdoll block-entity lookups when
  the client's downed-state map is empty.
- Remove the duplicate save of all players after feeding completes. Revival
  still saves the consumed item and restored player state.

Gameplay rules and network protocol 3 are unchanged from 1.4.2.
Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls, and Ragdoll
Reactions 0.7.0 are required. Install dependencies separately.

Validation: compilation, existing unit tests, and JAR packaging passed. The
packaged mod metadata reports 1.4.3. No FPS/TPS benchmark or new multiplayer
runtime test was performed for this patch.

See the [gameplay and configuration guide](../../README.md) and the
[isolated test instructions](../../docs/runtime.md). `SHA256SUMS.txt` lists
checksums for the distributable and source JARs.
