# RagRevival 1.4.1

Install `ragrevival-1.21.1-1.4.1.jar` on the server and every client,
replacing the previous RagRevival JAR while the instances are stopped.
Use matching Sable and Sable: Ragdolls versions on clients and server.

Fixes nearby ragdoll cleanup removing another downed player's invisible seat.
Previously, one player's self-revival could let a neighboring player appear
to walk around while their body remained downed and interactions stayed blocked.
Cleanup now only removes seats belonging to the expiring ragdoll's own sublevel.
The protection also applies to command revival, death, logout, and ordinary
ragdoll expiration.

Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls, and Ragdoll
Reactions 0.7.0 are required. Dependencies are not bundled. Network protocol
3, revival controls, items, and mob grace remain unchanged.

See [verification notes](../../docs/test-results-1.4.1.md) for reproduced failures,
patched results, and the remaining client gameplay check. The test harness is
not included in this distributable.
