# RagRevival

Cooperative player revival for **Minecraft 1.21.1 / NeoForge 21.1.249 / Java 21**.

Lethal damage puts a survival/adventure player into a locked Sable ragdoll. Their inventory and XP stay with them, mobs drop their target, and further damage is blocked. Native ragdoll movement remains available. Another player can feed or drag them; ordinary Ragdoll Reactions tumbles remain ordinary ragdolls.

## Install

Install RagRevival and all three pinned dependencies in **both clients and the dedicated server**:

| Mod | Required version |
| --- | --- |
| [Sable](https://modrinth.com/mod/sable) | 2.0.3 |
| [Sable: Ragdolls](https://www.curseforge.com/minecraft/mc-mods/sable-ragdolls) | 0.7.2 |
| [Ragdoll Reactions](https://www.curseforge.com/minecraft/mc-mods/ragdoll-reactions) | 0.7.0 |

The latest versioned distributable and source JAR are in [`artifacts/1.1.1`](artifacts/1.1.1). Third-party dependencies are downloaded separately; they are not embedded or committed. Keep Sable Ragdolls enabled. Its native libraries are already included inside Sable.

Optional compatibility test versions: **Carry On 2.2.6.13** on server and clients; **Unlocked Camera 1.0.0**, private source commit `c13cfa3d`, on clients only. See [source inspection and compatibility evidence](docs/compatibility-research.md). Other dependency versions are deliberately not advertised as verified.

## Play

- **Downed:** use the movement normally provided by Sable Ragdolls. Its stand-up/dismount controls cannot end the downed state.
- **Find a teammate:** chat announces "<player> is knocked and needs to be revived!" once per knockdown. Other players see a gold outline around the actual downed body through walls in the same dimension, within Sable's native render range (64 blocks, with limbs loaded). The outline ends when the player is revived, dies, or disconnects; ordinary ragdolls have no rescue outline. Seeing the outline does not let you feed through walls.
- **Feed:** aim at the visible body and hold Use Item/right-click with a golden apple in either hand. Feed for 32 server ticks (1.6 seconds at 20 TPS). Releasing the button, losing reach, changing the stack, changing dimension, disconnecting, or becoming downed cancels progress. Only successful feeding consumes an apple, including in creative mode. One rescuer owns a target at a time.
- **Drag:** with both hands empty, crouch and right-click the body. Keep crouching to drag; right-click may be released. Releasing crouch releases the native physics grip. A body cannot be fed, dragged, and carried simultaneously.
- **Zoom while dragging:** Unlocked Camera receives the mouse wheel normally. If no camera/interaction mod claims a vertical scroll, RagRevival consumes it to keep the hotbar from switching to a held item and interrupting the drag. Ordinary Sable grabs keep their own controls.
- **Give up:** hold **G** for 100 continuous server ticks (five seconds at 20 TPS). Releasing G cancels. Rebind it under **Options → Controls → Key Binds → RagRevival**.
- The HUD shows the downed countdown, feeding progress, and give-up progress to the relevant player.
- Successful revival restores **half of maximum health** by default: five hearts for a normal ten-heart player, scaling with maximum-health modifiers.

Feeding reserves that right-click until release, so holding it after revival does not eat another apple. A short server input lease cancels abandoned interactions; loss of window focus or opening a menu also releases the client interaction. Server stalls do not let packet spam accelerate either hold.

## Server configuration and item tag

Edit `config/ragrevival-server.toml` on the server (or `<world>/serverconfig/ragrevival-server.toml` if using a world-specific override):

| Setting | Default | Meaning |
| --- | --- | --- |
| `downedSeconds` | 120 | Real-time death countdown, captured when downing begins |
| `feedingTicks` | 32 | Continuous feeding time; captured when feeding begins |
| `restoredHealthFraction` | 0.5 | Fraction of the player's current maximum health restored on revival (0.01–1.0) |

Version 1.1.0 replaces the old `restoredHealth` fixed health-point setting. NeoForge corrects existing configs to add `restoredHealthFraction = 0.5`; the old value is no longer used. To change the percentage, edit the new setting while the server is stopped.

Replace the revival item with a datapack overriding `data/ragrevival/tags/item/revival_items.json`:

```json
{"replace":true,"values":["minecraft:golden_apple"]}
```

This is an item tag, not a hard-coded golden-apple check. Enchanted golden apples are not accepted unless added. The revival action restores configured health; it does not apply the item's normal food effects.

## Lifecycle and edge cases

- The absolute server deadline is stored in player NBT. **Offline time and server downtime count.** Reconnecting never grants a fresh countdown. If it expired offline, normal death runs upon login.
- On logout and clean server stop, the physics body is released and the player is saved at the body's real world position. Login rebuilds the locked body if time remains. The saved world-position checkpoint also avoids restoring a player into an orphaned physics plotyard after a restart. Abrupt process termination can still lose changes since the last Minecraft player save, as with normal world data.
- Downed players cannot voluntarily change dimensions. Rescuer dimension changes cancel interactions. External administrative teleportation does not clear the deadline; the server reasserts the ragdoll state.
- **Totems take precedence on triggering damage:** vanilla totem handling runs before the death interception. Countdown expiry and choosing to give up are final; they do not consume a totem or start another downed state.
- **Void and `/kill` are terminal.** Initial void damage or administrative generic-kill deaths bypass downing. If a downed body falls below the void boundary, it dies normally. This avoids indefinite inaccessible bodies. Other damage is blocked while downed.
- Countdown and give-up death release physics, clear the downed marker, and invoke normal player death once. `keepInventory`, XP loss, death messages, loot, and hardcore handling follow Minecraft's normal death path. No second downed trigger occurs. Original damage attribution is retained in memory; after a restart the saved damage type is restored without an absent original attacker.
- Ordinary Reactions ragdolls receive no revive timer, immunity, or G action. If one suffers fatal damage, its existing pose/body is converted in place and its ordinary expiry rules are replaced.
- Carry On pickup is blocked for downed targets and busy/downed rescuers. Existing carrying is released before downing. Occupied rescuers cannot begin feeding/dragging.
- If the native ragdoll cannot be established for 100 server ticks, the mod logs the failure and completes normal death rather than leaving an invulnerable standing player.

## Camera targeting

Selection clips the existing rendered camera ray against the actual rotated ragdoll limbs. It does not modify Unlocked Camera's position, freelook, shoulder selection, or crosshair. The server independently checks line of sight and interaction reach from the rescuer's actual world-space eyes to the real physics body. A camera offset cannot extend reach or permit feeding through walls. Sable plotyard coordinates are projected into world space; dragging uses Sable's native physics constraint and player seat, with no independent teleport loop.

See [dependency API inspection](docs/dependency-api.md) and [test evidence](docs/test-results.md) for the exact scope of verification.

## Build and local test setup

With Java 21 and PowerShell 7:

```powershell
./scripts/fetch-dependencies.ps1
./gradlew.bat build
```

The fetch script verifies SHA-256 hashes from the checked-in lock file. Set `JAVA_HOME` to Java 21 if another Java version is the system default. Gradle can otherwise resolve a Java 21 toolchain. Build outputs are under `build/libs`; only the versioned release artifacts are committed.

```powershell
./scripts/setup-test-runtime.ps1
./scripts/sync-test-mods.ps1 -ModJar ./build/libs/ragrevival-1.21.1-1.1.1.jar
./scripts/start-test-runtime.ps1
```

These scripts use isolated `.local/server`, `.local/client-one`, and `.local/client-two` directories, distinct `ReviveOne`/`ReviveTwo` offline identities, and a server bound to **127.0.0.1:25575**. Worlds, credentials, runtime downloads, and caches stay ignored. Unlocked Camera must be supplied locally because its source is private. Details and the test-harness build are in [runtime instructions](docs/runtime.md).

## Two-player checklist

1. Give both players apples; down one with lethal ordinary damage (e.g. `/damage ReviveOne 100 minecraft:generic`). Verify no death drops, the two-minute HUD, and one chat announcement. Put a wall between players and verify the rescuer can see the body's gold outline.
2. Move while downed; try native stand-up/dismount. Check that the body moves but stays downed. Spawn a hostile mob and verify it ignores the downed player.
3. Empty both rescuer hands, crouch-right-click the body, move, zoom in/out with Unlocked Camera, release right-click while still crouched and zoom again, then release crouch. Check body/player alignment, continued dragging, and release.
4. Hold right-click with an apple, cancel midway, move out of reach, then finish a feed. Check no early consumption, exactly one apple consumed on success, half maximum health restored, and the rescue outline removed.
5. Repeat aiming at limbs with Unlocked Camera's left/right shoulder offsets and freelook; step beyond normal interaction reach and behind a wall. Check visible-body selection and server rejection outside reach.
6. Down again; hold G briefly and release, then hold for five continuous seconds. Check progress reset and one normal death.
7. Down again and let the countdown expire. Repeat with `keepInventory` true/false, and reconnect or restart partway through to check that the timer does not reset.

The integration harness is test-only and is never inside the distributable JAR. It intentionally manipulates the isolated test players/world; do not run it on a valued world.
