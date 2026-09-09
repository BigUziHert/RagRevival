# RagRevival 1.3.5

Install `ragrevival-1.21.1-1.3.5.jar` on the server and every client,
replacing the previous RagRevival JAR while the instances are stopped.
Source and SHA-256 checksums are alongside it. Network protocol 3 and
gameplay are unchanged.

This release keeps broad NeoForge/Sable acceptance within Minecraft 1.21.1
and adds durable dependency-parser regression tests. Eleven JUnit checks
pass. All 20 Sable members referenced by the production JAR match ten
published versions from 1.1.1 through 2.0.5; no adapter change was needed.
All 50 referenced NeoForge-owned members also match across 28 published
releases from 21.1.219 through 21.1.250. Five real two-player server runs
passed 720 checks total, with the combinations recorded in the
[1.3.5 verification report](../../docs/test-results-1.3.5.md).

Minecraft 1.21.1, Java 21, NeoForge, Sable, Sable: Ragdolls 0.7.2, and
Ragdoll Reactions 0.7.0 remain required. Reviewed Sable 1.x releases require
NeoForge 21.1.219+, and 2.x requires 21.1.228+. Carry On is optional;
Unlocked Camera is optional and requires NeoForge 21.1.235+. Dependencies
retain their own restrictions and are not bundled.

API matches and tested profiles do not guarantee every NeoForge patch,
future Sable release, or other modpack. See the [API matrix](../../docs/sable-api-matrix-1.3.5.json)
and [runtime reproduction instructions](../../docs/compatibility-runtime.md).
