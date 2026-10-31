# RagRevival 1.4.1 verification

Checked October 31, 2026 with Java 21, Minecraft 1.21.1, NeoForge 21.1.249,
and Ragdoll Reactions 0.7.0 in the isolated, loopback-only dedicated server.

## Report and cause

Two players were downed beside each other. One self-revived, and the other
could appear to walk around while their body stayed on the floor and breaking
blocks or attacking remained blocked. Actually reviving the second player
restored normal behavior. The reported setup used RagRevival 1.4.0,
Sable 2.0.6, Ragdolls 0.7.5 on the server, and Ragdolls 0.7.2 on the client.

The native Ragdolls cleanup queried seats within the expiring ragdoll's plot
bounds. Sable expands entity queries across intersecting sublevels, so this
lookup also returned nearby bodies' seats. Cleanup then discarded those foreign
seats while their players' downed state and registered bodies remained intact.
The server regression reproduced this independently of any client version mismatch.

`RagdollSeatCleanupMixin` filters that lookup by the seat's containing sublevel
UUID. Native cleanup still removes the expiring body's seat and body, restores
its rider, and fires the existing lifecycle callbacks. Other seats survive.

## Build and packaging

- `gradlew.bat build testModJar` passed with Java 21.
- All 15 JUnit tests passed, with zero failures or errors.
- Production and source JARs plus SHA-256 sums are in `artifacts/1.4.1`.
- The production JAR contains the cleanup mixin and its registration, and no
  test harness classes. Network protocol 3 is unchanged.
- Production SHA-256:
  `e2303d4d019015f47723986ae7b5d8a6653754210b704f507bc7e4b67bbf5802`.

## Dedicated server regression

The new `ragrevivaltest nearby-revival` probe uses temporary native server
players, packet sinks, and real Sable physics. It refuses to run with real
players online. The same compiled harness was run against the original 1.4.0
artifact and the patched 1.4.1 artifact with Sable 2.0.6 / Ragdolls 0.7.5:

| RagRevival | Passed | Failed | Server ticks |
| --- | ---: | ---: | ---: |
| Original 1.4.0 | 84 | 12 | 649 |
| Patched 1.4.1 | 96 | 0 | 649 |

See the [original failures](nearby-revival-baseline-1.4.0.log) and
[patched check output](nearby-revival-checks-1.4.1.log).

The patched build also passed all **96 checks** with the build-baseline pair,
Sable 2.0.3 / Ragdolls 0.7.2, over 649 ticks; see
[baseline-dependency check output](nearby-revival-checks-1.4.1-build-baseline.log).
The test server shut down cleanly after each run. Temporary test harnesses were
removed and the local server's original mod files restored afterward.

The checks cover:

- Overlapping and adjacent downed players, timed self-revival, and subsequent
  teammate feeding with one item consumed per successful revival.
- Original neighboring seat survival, the same passenger, the same registered
  root and six body parts, and unchanged downed/alive state, checked on the
  revival tick and again afterward.
- Command revival, terminal death, logout, and ordinary ragdoll expiration.
- Correct removal of the expiring body's own seat and session.
- Overlapping fixtures explicitly prove that the native unfiltered query
  includes the foreign seat, so successful preservation exercises the fix.

The existing `ragrevivaltest revive-features` probe also passed **87 checks,
zero failures**, over 475 ticks with Sable 2.0.6 / Ragdolls 0.7.5. It covers
revival items, command permissions, use-state cancellation, health restoration,
mob protection and grace expiration. See its [recorded output](revival-feature-checks-1.4.1.log).

## Dependency evidence and limits

Sable 2.0.6 was downloaded from its [official NeoForge release](https://modrinth.com/mod/sable/version/fg9dTRz9),
with both publisher SHA-512 and SHA-1 verified. Its entity-query bytecode matches
the 2.0.3 build baseline. Ragdolls 0.7.5's expiry helper matches the 0.7.2 baseline.
The mixin's target method and invocation descriptors were also checked against
the 11 cached Ragdolls binaries from 0.5.0 through 0.7.5. These signature checks
do not expand the previously documented supported runtime combinations.

This is native server integration coverage with simulated connections. It does
not exercise physical mouse input, rendered movement, or the reported mixed
client/server versions. A two-client gameplay check remains: use matching
Ragdolls 0.7.5 and Sable 2.0.6 on both sides, install RagRevival 1.4.1, down both
players next to each other, self-revive one, and confirm the other stays attached
to their body until separately revived.
