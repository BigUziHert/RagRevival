# Native corpse client checks — 1.6.0

Work is isolated to `.local/corpse-compat/client` and `.local/corpse-sublevels`; the user's CurseForge instance is read-only throughout.

The client uses Minecraft 1.21.1, NeoForge 21.1.265, Corpse 1.1.22, Sable 2.0.6, Sable Ragdolls 0.7.5, Reactions 0.7.0, and Carry On 2.2.6.13. The final candidate under test has SHA-256 `ce9ebc2749d7f3086d6ae292323e720bb2954851390d704dc0674be83b90b1c4`.

## Regression discovered during actual input

On the earlier `4aa31d951269cba357f8ef28c392db6bcd40f160fd81bf308ad741c5d5d98ea6` candidate, crouch-use invoked Carry On before Corpse's interaction handler. Carry On serialized the corpse and removed it; Corpse removal dropped the inventory while Carry On retained the saved inventory. [The captured frame](corpse-native-client-1.6.0/carryon-regression-before-fix.png) shows the carried body and the dropped diamonds, chestplate, and shield. The isolated fixture was cleared immediately.

The final candidate rejects Carry On's entity pickup event for the inventory-bearing corpse, and its block pickup break callback for marked native corpse limbs. Failed pickup leaves the ordinary Corpse interaction available. Other Carry On targets retain their existing behavior.

## Verified client behavior

| Check | Evidence and result |
| --- | --- |
| Native grab, movement, and release | [200-tick trace](corpse-native-client-1.6.0/native-grab-trace.json): 100 ticks with Use held, 100 native active-position samples, and 100 server grab acknowledgements. The torso moved from `(203.4915,69.9807,199.3022)` to `(202.1677,69.9084,199.9387)`. The final sample has Use released, no active position, and no server grip. |
| Regrab after settling | [Second trace](corpse-native-client-1.6.0/native-regrab-trace.json): 150 held samples and 150 server acknowledgements, followed by normal release. The viewing direction was changed through the isolated server during this run. Native physics moved and rotated the same body again. |
| Articulated skin, skeleton, and equipment | [Settled body after dragging](corpse-native-client-1.6.0/native-settled-after-grab.png) shows independently posed limbs, a correctly attached chestplate, and the skeleton fixture in the background. |
| Actual crouch-use mouse click | Opened the [native Corpse inventory](corpse-native-client-1.6.0/crouch-use-loot.png), instead of Carry On pickup. This was repeated after moving the body. |
| Actual Transfer Items mouse click | [Transfer complete](corpse-native-client-1.6.0/transfer-complete.png): the corpse inventory emptied and [server inventory data](corpse-native-client-1.6.0/transferred-inventory.txt) contains exactly seven diamonds, one equipped chestplate, and one offhand shield. |
| Actual use with a nonempty main hand | Selected the diamonds and clicked the skeleton without crouching; [its native inventory opened](corpse-native-client-1.6.0/skeleton-held-item-loot.png). The [inspection](corpse-native-client-1.6.0/skeleton-loot-inspect.json) records the held item, `shiftDown:false`, and the inventory screen. |
| Living player rendering after corpse rendering | [F5 view](corpse-native-client-1.6.0/normal-player-model.png) shows the complete normal player, armor, and held items, with the skeleton in the same frame. |

## Exact input scope

The Windows computer-use API supports click and drag gestures but provides no held-button duration. Its drag events were processed between Minecraft ticks: the read-only trace recorded no held Use samples. In the isolated test profile only, Use was temporarily assigned to the left button and Attack to the right button, with low sensitivity and raw input disabled so absolute window coordinates did not fling the camera. Crouch temporarily used a toggle. Actual mouse clicks verified both looting controls and Transfer Items.

The sustained grab tests therefore used an explicit, bounded **synthetic Use-key hold in the test harness**. It changes only `keyUse.setDown` for 100 or 150 ticks; ordinary Sable client targeting, network packets, server validation, and native constraints perform the grab. It never calls `startGrab`, applies impulses, or edits corpse poses. The hold clears on timeout, GUI opening, disconnect, or an error. Each trace sample identifies the injected input with `testOnlyHeldInput`; the trace descriptions explicitly state this scope. These are native client-path tests, not evidence of a physical held-right-button gesture.

A test-fixture issue was also found and fixed: constructing a connectionless fake player with the real player's UUID replaced the shared advancement owner. Transfer then encountered a recipe notification with a null connection. The harness now restores the real advancement owner and supplies the fixture connection. The [earlier crash report](corpse-native-client-1.6.0/fixture-crash-before-harness-fix.txt) is retained; the successful transfer above uses the corrected harness with the same production JAR.

The owned client and server closed normally at 12:35 local time. [Final client log](corpse-native-client-1.6.0/client.log) and [server log](corpse-native-client-1.6.0/client-scene-server.log) are preserved. Both test processes exited, the system Caps Lock toggle was restored, and the isolated profile's complete `options.txt` was restored byte-for-byte from its pre-test backup; both SHA-256 values are `7703710a14a50523b3859fc8da7d0b8a8f32d3c5ed79df38f6261b76f3d68f50`.
