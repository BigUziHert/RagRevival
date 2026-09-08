# RagRevival 1.1.0

Install `ragrevival-1.21.1-1.1.0.jar` on the server and every client, replacing 1.0.0.
The matching source archive and SHA-256 checksums are alongside it.

Changes:

- One chat announcement per knockdown: "<player> is knocked and needs to be revived!"
- Other players see a gold outline of the actual downed ragdoll through walls.
  Loaded bodies in the same dimension and native 64-block render range qualify;
  normal ragdolls stay unchanged.
- Revival restores half maximum health by default. The new server setting
  `restoredHealthFraction = 0.5` replaces the old fixed-point `restoredHealth`.

Requires Java 21, Minecraft 1.21.1, NeoForge 21.1.249+, Sable 2.0.3,
Sable Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0. Dependencies and the test
harness are not bundled. See the [README](../../README.md) and
[verification evidence](../../docs/test-results-1.1.0.md).
