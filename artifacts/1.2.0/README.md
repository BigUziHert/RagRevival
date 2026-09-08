# RagRevival 1.2.0

Install `ragrevival-1.21.1-1.2.0.jar`, replacing the previous RagRevival JAR
on the server and clients. Matching source and SHA-256 checksums are alongside it.

- Golden carrots now revive alongside golden apples through the revival item tag.
  Both require the full feeding interaction and consume one item only on success.
- Valid feeding pauses bleed-out, allowing last-second rescues to finish.
  Canceling resumes the remaining time. Dragging does not pause it, and a feed
  cannot start after the deadline has expired.
- Rescue prompts use short labels, item icons, and rebind-aware keycaps.
  The existing downed/name/timer text is retained; its countdown freezes during feeding.

Revival still restores half of maximum health by default. Offline time and
server downtime still count after the feeding interaction is canceled.

Requires Java 21 / Minecraft 1.21.1 / NeoForge 21.1.249 and pinned Sable 2.0.3,
Sable Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0. Dependencies and the test
harness are not bundled. See the [README](../../README.md) and
[verification evidence](../../docs/test-results-1.2.0.md).
