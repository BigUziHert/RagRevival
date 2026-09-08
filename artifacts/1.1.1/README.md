# RagRevival 1.1.1

Install `ragrevival-1.21.1-1.1.1.jar`, replacing the previous RagRevival JAR.
Matching source and SHA-256 checksums are alongside it.

Fixes Unlocked Camera zoom during crouch-dragging. Sable's generic client grab
no longer duplicates the rescue drag or consumes its mouse wheel. Zoom works
while Use is held and after releasing Use while crouch stays held. Existing
ordinary grips yield when a rescue takes input ownership; ordinary grips when
idle keep their controls. Unclaimed vertical scroll still cannot change the
hotbar during a rescue drag. Unlocked Camera itself is unchanged.

Requires the same Java 21 / Minecraft 1.21.1 / NeoForge 21.1.249+ and pinned
Sable 2.0.3, Sable Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0 dependencies.
Dependencies and test harness are not bundled. See the [README](../../README.md)
and [verification evidence](../../docs/test-results-1.1.1.md).
