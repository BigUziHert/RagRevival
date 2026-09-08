# Dedicated server without Carry On

Verified on 2026-09-08 with Java 21 and NeoForge 21.1.249 in an isolated local instance at `.local/no-carryon`, listening only on `127.0.0.1:25577`. The main two-client test environment was not changed. The isolated server used its own world, configuration and mods directory, with a read-only-use junction to the already installed official NeoForge libraries.

Installed top-level mods were exactly:

- `ragrevival-1.21.1-1.0.0.jar`
- `sable-neoforge-1.21.1-2.0.3.jar`
- `sable_player_ragdoll-1.21.1-0.7.2.jar`
- `ragdoll_reactions-1.21.1-0.7.0.jar`

Carry On, Unlocked Camera, and the test harness were absent. Sable's own nested libraries loaded normally. The tested RagRevival artifact SHA-256 was `0b8639ce9f6c86f2c7b13694f563f3787ae23263d41933e8919f8d8b88031e1b`.

The dedicated server reached `Done (1.489s)!` at 22:51:02 UTC. A `stop` command was sent through its redirected standard input, followed by the normal save sequence ending in `All dimensions are saved`. The Java process exited with code **0**, and the temporary server port was no longer listening. No third test server was left running.

There were no missing Carry On class errors or optional-dependency startup failures. The log did contain nonfatal `ClientLevel` distribution warnings during upstream mixin discovery and Sable's `Unknown block: create:flywheel` physics-property messages. Those messages also occur in the main dependency installation and did not prevent startup or clean shutdown; this is therefore a successful startup test, not a claim of an entirely warning-free upstream stack.

This establishes that Carry On can be omitted without preventing dedicated server loading. It does not replace the separate gameplay tests with Carry On installed. Local evidence is retained in `.local/no-carryon/result.json`, `logs/latest.log`, and `console.log`; logs, libraries, and the test world are excluded from Git.

## State packet codec checks

At 22:55:57 UTC on the same date, the isolated instance was started again with the optional test harness added. The console command `ragrevivaltest codec` ran `StatePayloadProbe` inside the real NeoForge dedicated-server runtime. All **3 checks passed**, with **0 failures**:

- The state packet roundtrip preserves all six exact body-part UUIDs and consumes the entire buffer.
- Clear-state packets contain no stale body topology.
- Negative, seven-part, and maximum-integer part counts are rejected before allocating or reading IDs.

The second instance also reached `Done`, saved all dimensions on the console `stop` command, and exited with code **0**. Process 30420 and port 25577 were confirmed closed afterward. Local evidence is in `.local/no-carryon/codec-result.json` and `codec-console.log`. The first startup test remains the harness-free optional-dependency check; the later run adds codec evidence without changing the main test server or clients.
