# Eating presentation in 1.3.0

The inspected Minecraft/NeoForge 1.21.1 sources are the pinned Gradle source
artifact, `build/moddev/artifacts/neoforge-21.1.249-sources.jar`. Sable Ragdolls
0.7.2 sources were inspected in the existing local dependency checkout.

`LivingEntity.startUsingItem` synchronizes the used hand and use flags. Food
items then use vanilla first-person eating motion. `FeedingUseMixin` replaces
the use tick during an owned feed, prevents vanilla completion/release effects,
and suppresses the item's stop callback until managed use is cleared. Ordinary
item use follows the original methods. The server alone completes revival and
consumes the selected stack.

Visual use ticks loop within the item's native duration. They are independent
of `feedingTicks`: vanilla `ItemInHandRenderer.applyEatTransform` divides by
native duration and raises the ratio to the 27th power, so using a long custom
feeding duration directly would distort the animation. Native use starts only
for EAT/DRINK animations; tagging a shield or bow cannot activate blocking or
charging as part of a revival.

Crumbs and chewing sound are sent every four feeding ticks from the actual
downed head. The exact server-synchronized head part is selected, then local
coordinates `(0.5, 0.38, 0.77)` are transformed through its logical physics pose.
The inspected head renderer puts its face at local Z `0.734375`, making this a
point just outside the mouth. Sable's particle `ServerLevelMixin`, plot
`PlayerListMixin`, and client sound-distance handling project plotyard positions
for delivery and attenuation. Camera behavior remains unchanged.

Sable's `RagdollCameraHelper` hides first-person hands throughout seating.
`RagdollPartBlockEntityRenderer` draws independent rigid limbs without applying
vanilla humanoid animation. Therefore self-revival has mouth crumbs and sound,
not an arm bending toward the mouth. Moving those physical limbs would require
a separate physics pose controller. Vanilla standing third-person players also
do not have a distinct EAT arm pose; the held item, crumbs, and sound supply that
view's feedback. Custom non-food tagged items use crumbs/sound only.

See [1.3.0 verification](test-results-1.3.0.md) for runtime checks and their limits.
