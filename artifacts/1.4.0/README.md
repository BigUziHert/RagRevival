# RagRevival 1.4.0

Install `ragrevival-1.21.1-1.4.0.jar` on the server and every client,
replacing the previous RagRevival JAR while the instances are stopped.

- Operators with permission level 2 or higher and the server console can use
  `/revive <PlayerName>` to revive one online downed player immediately.
- Revival items now include honey bottles, glistering melon slices, totems of
  undying, enchanted golden apples, drinkable Healing I/II potions, and
  normal/long/strong Regeneration potions, alongside golden apples and carrots.
- The compact rescue hint shows the held valid item or cycles a single
  eligible example every two seconds, including correctly colored potions.
- Every successful revival grants 200 server ticks of mob protection
  (ten seconds at 20 TPS). Mobs drop their target, cannot retarget the player,
  and cannot damage them during grace, including arrows already in flight.

Item revival still requires holding Use and consumes one item on completion.
The command uses the same health restoration and rescue cleanup without
consuming an item. PvP and environmental damage keep their normal rules.
Grace ends on logout, death, or another knockdown and is not saved across restarts.

Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls, and Ragdoll
Reactions 0.7.0 are required. Dependencies are not bundled. Network protocol
3 and standing/crouching drag and feeding controls are retained.

See [verification notes](../../docs/test-results-1.4.0.md) for checks and their
limits, and [local runtime instructions](../../docs/runtime.md) for the
optional `ragrevivaltest revive-features` probe and historical two-client suite.
