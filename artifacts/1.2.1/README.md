# RagRevival 1.2.1

Install `ragrevival-1.21.1-1.2.1.jar`, replacing the previous RagRevival JAR
on the server and clients. Matching source and SHA-256 checksums are alongside it.

- The rescuer sees the remaining `m:ss` countdown inside the compact item/key/action
  prompt. The separate player-name/downed sentence is removed.
- Revival progress fills a green bar along the bottom edge of that same prompt.
- The downed player's own HUD and give-up progress are unchanged.

Golden apples and golden carrots still revive through the item tag. Valid feeding
still pauses bleed-out, cancellation resumes it, and successful revival restores
half of maximum health by default.

Requires Java 21 / Minecraft 1.21.1 / NeoForge 21.1.249 and pinned Sable 2.0.3,
Sable Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0. Dependencies and the test
harness are not bundled. See the [README](../../README.md) and
[verification evidence](../../docs/test-results-1.2.1.md).
