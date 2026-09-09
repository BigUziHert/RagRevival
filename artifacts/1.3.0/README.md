# RagRevival 1.3.0

Install `ragrevival-1.21.1-1.3.0.jar`, replacing the previous RagRevival JAR
on the server and every client. This release uses network protocol 3; all
instances must run matching versions. Matching source and SHA-256 checksums
are alongside the distributable.

- Reviving a teammate requires holding crouch + Use continuously. Standing up
  cancels feeding without consuming the revival item.
- Downed players can hold Use with a revival item to revive themselves.
  Self-revival does not require crouching.
- Feeding plays the held-item eating animation, with crumbs and eating sounds
  at the downed body's head. These are cosmetic: food hunger, saturation, and
  buffs are suppressed. Sable hides seated first-person hands and keeps rigid
  limbs, so self-revival uses mouth crumbs/sound without an arm-to-mouth pose.
- The downed player has a compact self-revival prompt, a give-up key hint, and
  their G hold progress. Revival progress fills the panel's green bottom edge.

Golden apples and golden carrots use the same configurable revival item tag.
Both kinds of feeding use the configured duration, pause bleed-out, restore
half maximum health by default, and consume exactly one item after success.
Cancellation resumes the countdown. Self-revival, teammate feeding, and dragging
share exclusive target ownership, so competing interactions cannot consume
duplicate items. Giving up and void death retain priority during feeding.

To check manually, cancel teammate feeding by releasing crouch, then finish a
feed. Down again and self-revive using only Use. Observe animation and sound,
timer pause/resume, and successful consumption with each revival item.

Requires Java 21 / Minecraft 1.21.1 / NeoForge 21.1.249 and pinned Sable 2.0.3,
Sable Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0. Dependencies and the test
harness are not bundled. See the [README](../../README.md) and
[verification evidence](../../docs/test-results-1.3.0.md).
