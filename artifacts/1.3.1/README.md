# RagRevival 1.3.1

Install `ragrevival-1.21.1-1.3.1.jar`, replacing the previous RagRevival JAR
on the server and every client. Network protocol 3 is unchanged; use matching
versions for the current presentation. Source and SHA-256 checksums are
alongside the distributable.

- A teammate holds the revival food while feeding, with crumbs and chewing
  sounds at the downed player's head. Native eating use is reserved for
  self-revival, with the existing protection against normal food consumption
  and effects. Sable continues to hide seated first-person hands and render
  rigid limbs.
- The downed player's prompt is one two-row card: self-revival and the timer
  above, then a G keycap and **Hold for 5s** below. A shared thin green track
  along its bottom edge fills for revival or G, with G progress taking
  priority. The give-up hint and progress are contained in the card.

Teammate feeding still requires continuous crouch + Use; self-revival needs
only Use. Golden apples and golden carrots use the same configurable tag,
feeding duration, and exclusive target ownership. Valid feeding pauses
bleed-out, cancellation resumes it, and successful feeding consumes exactly
one item and restores half maximum health by default.

For a manual check, feed a teammate while watching both hands and the downed
mouth effects, then cancel by releasing crouch. Down again to check self-feeding
and the card's G hint, green progress, cancellation, and five-second give-up.

Requires Java 21 / Minecraft 1.21.1 / NeoForge 21.1.249 and pinned Sable 2.0.3,
Sable Ragdolls 0.7.2, and Ragdoll Reactions 0.7.0. Dependencies and the test
harness are not bundled. See the [README](../../README.md) and
[verification evidence](../../docs/test-results-1.3.1.md).
