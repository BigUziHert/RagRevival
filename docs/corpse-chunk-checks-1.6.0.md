# Distance-driven chunk loading — 1.6.0

**15/15 checks passed** in 320 server ticks on production SHA-256 `ce9ebc2749d7f3086d6ae292323e720bb2954851390d704dc0674be83b90b1c4`, Minecraft 1.21.1 / NeoForge 21.1.265, isolated server PID 511204.

`CorpseChunkProbe` creates a native corpse at `(576.5,128,576.5)`, outside spawn chunks, and a plain vanilla armor stand in a separate nearby chunk. The registered player starts within the server's five-chunk view distance. No vanilla force-load ticket is installed. After the body settles, the player moves to `(0.5,128,0.5)`.

Because the fixture has a packet-sink connection, it explicitly calls the normal `ServerChunkCache.move(player)` tracker update that a real client's movement/teleport acknowledgement would trigger. It never requests a chunk unload or removes the corpse during the observation. A first fixture iteration omitted this tracker update and therefore retained stale player tickets; that harness issue was corrected without changing production code.

Within twenty ticks of departure, the real world chunk reports unloaded, the corpse leaves active entity storage, its compatibility binding is gone, and none of its six native parts remain in active sublevel storage. The plain vanilla control also unloads. The engine's entity-leave event is observed. That event occurs at tracking-end, before a removal-reason enum necessarily exists, so the test combines the event with actual chunk/entity storage checks rather than assuming callback timing.

Returning the player restores the original corpse UUID, inventory NBT, original native root ID, and exactly six native parts. After 200 ticks, the body has a valid articulated pose, lies within 0.25 blocks of its original support, and has no sustained artificial joint motion.

Observed final trajectory:

- Before departure: `(576.4979294705063,127.98335637176957,575.2981800877676)`.
- After return and settling: `(576.4979277251439,127.9831777438216,575.2981800862807)`.
- Displacement: approximately `0.00018` blocks; maximum native limb speed: `0.0`.
- Original and resumed native root: `3363d8f1-a91d-4bf2-87a3-f0fe723cddcf`.

See the [complete check log](corpse-chunk-1.6.0-neoforge-265.log). Raw evidence is retained in `.local/corpse-compat/native-carryon-1.6.0`. The isolated server was stopped cleanly after testing, and its process was verified absent. This establishes inactive distant storage and correct resumption in this fixture. It does not imply a global body cap or prove performance for hundreds of simultaneously loaded native bodies. Full process/disk persistence is covered separately by the [restart checks](corpse-restart-checks-1.6.0.md).
