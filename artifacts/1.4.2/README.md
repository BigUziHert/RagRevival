# RagRevival 1.4.2

Install `ragrevival-1.21.1-1.4.2.jar` on the server and every client.
Stop each instance before replacing its previous RagRevival JAR.
Use matching Sable and Sable: Ragdolls versions across all instances.

- Every new knockdown deducts 25% of the player's current XP points, rounded
  down to a whole point. The player keeps their inventory while downed.
- Golden apples, golden carrots, enchanted golden apples, and drinkable Healing II
  and Regeneration II are the default held revival items. Honey bottles,
  glistering melon slices, Totems of Undying, and weaker potions are excluded.
- A player who is up can instantly revive a teammate by directly hitting their
  ragdoll with Splash Healing II or Splash Regeneration II. The successful
  impact revives one player, even if bodies overlap. A miss uses ordinary splash
  behavior.

Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls, and Ragdoll
Reactions 0.7.0 are required. Install dependencies separately. The network
protocol is 3.

See the [gameplay and configuration guide](../../README.md) and the
[isolated test instructions](../../docs/runtime.md).
