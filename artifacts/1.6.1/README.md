# RagRevival 1.6.1

Compatibility update for the supplied **Sable Ragdolls 0.8.1** and **Ragdoll Reactions 0.8.0**, using Minecraft 1.21.1, Sable 2.0.6 and NeoForge 21.1.265 in runtime testing.

Install `ragrevival-1.21.1-1.6.1.jar` in place of the older RagRevival JAR on both the server and every client. Do not install the sources JAR. Third-party dependencies are not bundled. The original CurseForge instance was not modified.

The update adapts to native per-block ragdoll identities, preserves marked corpses from the old storage format, and tracks actual limb positions when Sable moves or merges blocks. Corpse keeps its ordinary inventories, ownership and loot menus. Dragging continues through native Sable physics.

**Separate pack blocker:** EMF Attachments Compat **1.2.11** fails its `SablePlayerEntityCullingMixin` injection with the new Ragdolls version, including when RagRevival is absent. The pack needs a compatible version of that addon or its removal before startup. It was excluded only in the isolated client used for compatibility testing.

See [verification and limitations](../../docs/compatibility-1.6.1.md) and `SHA256SUMS`. The test harness is excluded from this release.
