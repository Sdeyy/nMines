# nMines

A mine management plugin for Bukkit/Spigot and Paper, targeting **Minecraft Java 1.16.5–26.3**, with configurable regions, block compositions, custom rewards and inventory menus.

## Installation

1. Copy `nMines-1.0.0.jar` into `plugins/` and start your server.
2. Use the Java version required by your server. The plugin uses Java 8 bytecode; Paper 26.3 itself requires Java 25.
3. Operators can manage mines with `/nm`. The administrative permission is `nmines.admin`.
4. Optional: install a WorldEdit/FAWE 7 build compatible with your server for selections and schematic imports. Install PlaceholderAPI if you want scoreboard/hologram placeholders.

No resource pack or mandatory plugin dependency is required. Menus use 54-slot inventories, dark borders, cyan accents, navigation, live statistics and inventory item pickers.

Folia is not supported.

## Language and configuration

**All bundled text is English.** Edit `plugins/nMines/language.yml` to translate messages, menu titles, button names, lore, command help, selection tools, errors, status labels and notifications. Run `/nm reload` to apply changes. Valid reloads cancel pending work and recount incomplete regions; invalid files leave the current jobs and definitions intact.

- `config.yml`: engine budgets, volume limits, default mine settings, decorative materials and sounds.
- `language.yml`: text, prefix, color codes and the configurable chat cancellation word (`cancel` by default).
- Missing translations fall back to English. Invalid YAML leaves the active language unchanged. Existing language files are not overwritten.
- Menu lore supports additional lines using `\n` in double-quoted YAML strings. Positional placeholders such as `{0}` and `{1}` can be reordered.
- Old `config.yml` entries `prefix`, `messages` and `menus.title` are no longer used. Move custom text to the language file.

See [the translation guide](docs/LANGUAGE.md) for keys, examples and upgrade instructions. Stored custom mine names, item metadata and reward messages remain unchanged.

## Your first mine

```text
/nm wand
# Left-click: first corner. Right-click: second corner.
/nm create mine1
/nm gui mine1
```

You can also mark corners with `/nm pos1` and `/nm pos2`. Selections are inclusive. New mines contain a stone mixture in a `default` region and composition.

Stand on a safe platform outside the mine and configure its destinations:

```text
/nm settp mine1
/nm setresettp mine1
/nm reset mine1
```

Open the mine's **Regions and blocks** menu to edit its regions, compositions and block mixtures. Use **Add block** or **Change block** to select a placeable block from your inventory. Weight, amount and probability fields are configured separately. The reset interval uses a button editor with +1, +5, +10, +30 and matching subtraction buttons. Click **Save interval** to apply the draft or **Cancel** to discard it. The minimum is 1 second; invalid adjustments are rejected. When necessary, the warning is reduced to fit the new interval.

## Model and regeneration

`Mine → Regions → Compositions → Blocks`

- Compositions and blocks use relative weights: weights 90 and 10 represent probabilities of 90% and 10%; they do not have to total 100.
- With region rotation disabled, every region regenerates. With rotation enabled, one region is selected by weight per reset.
- `TIME`: timed resets. `PERCENTAGE`: reset at a remaining-block threshold. `BOTH`: either condition. `MANUAL`: requested resets only.
- The reset interval starts when regeneration finishes. Timed resets support intervals as short as 1 second; percentage-triggered resets retain a five-second grace period.
- Each countdown second is announced at most once per mine and reset cycle.
- Jobs alternate small batches within the shared block/time budget and traverse each chunk before moving to the next. Missing worlds and chunk-load limits do not stop work in other loaded regions. Progress and remaining-block queries use incremental counters.
- Block reward matching uses precompiled block data indexed by material. Inventory menus reuse decoration and unchanged buttons, updating only slots whose contents changed.
- After regeneration, the percentage denominator is the number of non-air blocks generated. Intentional air is not counted as mined blocks.
- Startup recounts actual world blocks gradually. Region changes are blocked until counting finishes, but players can leave. Active compositions and capacities are kept in `runtime.yml`.
- `stop` pauses automatic resets while protection and mining remain available.
- Before regeneration, players intersecting the region are evacuated to a safe exit. If a teleport is cancelled by another plugin, the reset is cancelled before block changes start.
- Disabling evacuation makes resets wait until players leave. Mines are not filled over players.
- Region bounding boxes must not overlap, including regions with different internal masks.

The engine checks cell, time and chunk-load budgets before each cell, including skipped mask cells. A single Bukkit operation or chunk load can exceed the time budget by itself, so this is not a hard server latency guarantee.

## Inventory item selection

Block mixtures, drops, rewards and mine icons use inventory pickers. Click an item in the bottom inventory, or hold it on your cursor and click the selector's central slot. The original is not consumed. Custom names, lore, enchantments and data supported by Bukkit item serialization are preserved.

Only placeable blocks can be used for regeneration. Their Minecraft material and block state are placed in the world. The selected item is retained as the menu icon and, when it has custom metadata, as its custom drop. This does not implement a separate custom-block provider in the world.

In **Edit block → Item** and **Edit reward → Reward item**, you can also click directly in the bottom inventory. Stack size does not change the configured delivery amount. Use the amount editor. The reward picker has a **Remove item** button. Templates survive saving, reloading, restarting and undo/redo.

## Required mining tools

Each mine can optionally require a registered pickaxe or other item. Open **Manage → Required mining tool** to select a template from your inventory. Selecting it saves a copy without changing or consuming the original.

The rule uses the item's material and a persistent `nmines:tool-id` tag. A mining attempt checks those two values; it does not compare names, lore, enchantments or serialize the item. A visually identical but unregistered item is rejected. Mining is cancelled before drops, rewards and counters are processed. This restriction also applies to operators and creative players; the building bypass permission does not bypass it.

To configure and register the item in your main hand:

```text
/nm tool set mine1
/nm tool register mine1
```

Registering an existing item verifies its full properties once, ignoring wear, stack size and any previous nMines tool tag. It then attaches the required ID and keeps the held stack's size and wear. You can also use **Get a registered copy** in the menu or give one to a player:

```text
/nm tool give mine1 PlayerName
```

Renaming or repairing a registered tool does not invalidate its ID. Changing its material does. External plugins must preserve the tag when rebuilding items. This is an identity-based rule, not a continuous appearance comparison.

Registered copies of the same template share an ID. Select an already registered copy when configuring another mine if both should accept the same tool. A registered copy can also be selected as a reward item. Rule definitions and item tags persist through reloads and restarts.

Only administrators can configure, register or issue tools. Existing mines remain unrestricted until a rule is set. Use **Remove tool requirement** or `/nm tool clear mine1` to allow any item again.

## Commands

Aliases: `/nmines`, `/nm`, `/mines`. IDs use lowercase letters, digits, `_` or `-`, up to 32 characters. Block indices start at **0**, as shown in the editor and `/nm info`.

| Command after `/nm` | Purpose |
|---|---|
| `help`, `list`, `gui [mine]`, `tp mine` | Help, navigation and teleportation |
| `wand`, `pos1`, `pos2` | Native selections |
| `create id [we]` | Create from a native or WorldEdit selection |
| `region add mine:region [we]` | Add a region |
| `region redefine mine:region [we]` | Replace geometry while keeping compositions |
| `region remove mine:region` | Remove a region; at least one must remain |
| `region weight mine:region weight` | Set its rotation weight |
| `region schematic mine:region file.schem` | Import a schematic at the native selector's first position |
| `composition add\|remove mine:region:composition` | Manage compositions |
| `composition weight mine:region:composition weight` | Set composition weight |
| `block set mine:region:composition block weight` | Add a block or update its weight |
| `block remove mine:region:composition index` | Remove a block |
| `tool set mine`, `tool register mine`, `tool give mine [player]`, `tool clear mine` | Configure, register, issue or remove the required mining tool |
| `drop mine:region:composition:index item min max [true\|false]` | Set a material drop; final argument controls Fortune |
| `drop mine:region:composition:index vanilla` | Restore normal drops |
| `mode mine TIME\|PERCENTAGE\|BOTH\|MANUAL` | Select reset mode |
| `timer mine [seconds]`, `percent mine threshold`, `warning mine seconds` | Open the time editor or set seconds; threshold and warning (0 disables it) |
| `name mine text`, `icon mine material`, `rename mine new_id` | Name, icon and identifier |
| `public\|protect\|pickup\|rotate\|evacuate mine true\|false` | Access, protection, collection, rotation and evacuation |
| `settp mine`, `setresettp mine` | Save your location as destination/exit |
| `start\|stop mine` | Enable/pause automatic resets |
| `start\|stop *`, `reset *`, `confirm` | Confirm a global action |
| `tasks stop`, `tasks start` | Pause/resume global work without losing progress |
| `tasks cancel mine`, `tasks retry mine` | Cancel pending work or recover by recounting the current blocks |
| `tasks save` | Wait for pending saves and retry failed writes |
| `reset mine[:region]`, `recount mine` | Regenerate, or recount after external changes |
| `delete mine`, `confirm` | Confirm removal of a definition |
| `undo`, `redo` | Restore definitions; world blocks are not changed |
| `info mine`, `reload`, `cancel` | Diagnostics, reload and cancel chat editing |

Only mines affected by an edit are blocked while their jobs are pending. Other mines remain editable, including while a world is unloaded or the queue is paused. Use `tasks cancel mine` or **Cancel queued jobs** in the management menu to release a stuck mine. Cancellation keeps existing world blocks and locks incomplete regions. Use `tasks retry mine`, `recount mine`, **Recover pending regions**, or a new reset to make them available again. Recovery by recount does not continue an interrupted random mixture; use a reset to generate a complete mixture. Mixed regions whose composition cannot be identified do not use composition-specific rewards until reset.

Protection covers building, multi-placement, explosions, pistons, growth, spreading, burning and entity block changes, including endermen picking up or placing blocks. With Protection disabled, those general restrictions are relaxed. Fluid placement and incoming flow remain blocked independently: buckets (including fish buckets), dispensers aimed into a region, waterlogged block placement and cauldron level changes cannot introduce fluids, even with building bypass or operator permissions. Fluid actions outside mine regions remain available. Other plugins can bypass Bukkit events by writing blocks directly; use `recount` after those operations. nMines is not a general plot-protection or anticheat system.

## Rewards and drops

Reward target: `mine[:region[:composition[:block_index]]]`. Rewards are evaluated from block → composition → region → mine. Each can deliver an inventory-selected item, run console commands, send a chat message or show an action bar. They can require a permission and have their own probability.

```text
/nm reward add mine1 bonus
/nm reward chance mine1 bonus 5
/nm reward item mine1 bonus hand 1
/nm reward message mine1 bonus &bYou found a reward, {player}!
/nm reward command mine1 bonus say {player} found treasure in {mine}
/nm reward appendcommand mine1 bonus give {player} emerald 1
```

Operations: `add`, `remove`, `chance`, `item`, `message`, `actionbar`, `permission`, `command`, `appendcommand`, `radius`, `group`, `overrides`. `-` clears text, a permission, an item or a command. `command` replaces the command list; `appendcommand` adds another command. Only administrators can configure console commands.

Supported reward placeholders: `{player}`, `{uuid}`, `{trigger}`, `{mine}`, `{region}`. Radius 0 targets the miner; a positive radius targets nearby players in the same world. The permission condition is checked against the triggering miner.

A triggered reward can suppress groups at broader scopes using comma-separated `overrides`. All four scopes are persisted. Invalid conditions, weights or amounts reject the entire edit. Creative mining and breaks cancelled later by another plugin do not grant rewards. Delivery is checked on the following tick. Inventory overflow drops on the ground.

Normal drops use Minecraft's logic. Custom drops support a quantity range and optional Fortune; Silk Touch keeps the vanilla drop. Normal block experience is not modified. Drops and rewards are independent: a block can have both a regular drop and an additional probabilistic reward.

## WorldEdit and schematics

WorldEdit/FAWE 7 is optional. `create … we` and `region … we` can import non-cuboid selections, keeping a positional mask and full vertical bounds. Regeneration still uses nMines' server-thread engine, not FAWE's asynchronous queue.

Place `.schem`/`.schematic` files in `plugins/nMines/schematics/`. The schematic's minimum point is placed at `/nm pos1`. Region detection uses those translated coordinates.

Imports preserve **block states and air**, not entities, biomes, inventory NBT or block-entity data. A template determines the regenerated blocks; its compositions supply reward configuration. File sizes and volumes are limited. Imports are read on the server thread and large imports should be done during maintenance.

## Permissions and placeholders

| Permission | Default |
|---|---|
| `nmines.admin` | Operator; full administration |
| `nmines.use` | Everyone; public navigation |
| `nmines.teleport` | Everyone; permitted teleport destinations |
| `nmines.bypass` | Operator; build outside active jobs |
| `nmines.mine.<id>` | Explicit grant for access to a private mine |

Optional PlaceholderAPI expansion:

```text
%nmines_count%
%nmines_name_mine1%
%nmines_remaining_mine1%
%nmines_total_mine1%
%nmines_mined_mine1%
%nmines_percent_mine1%
%nmines_seconds_mine1%
%nmines_time_mine1%
%nmines_status_mine1%
%nmines_progress_mine1%
```

IDs may contain `_`. Placeholders read immutable snapshots updated each second, without world access from scoreboard threads. Status labels can be translated in `language.yml`.

## Storage and recovery

- `mines.yml`: definitions, geometry, mixtures, item templates, required tools, drops and rewards.
- `runtime.yml`: active compositions and reset capacities; remaining blocks are always recounted.
- `config.yml`: engine settings and decoration.
- `language.yml`: all configurable text.

A single background writer receives immutable text snapshots prepared on the server thread. Pending snapshots for the same file are coalesced, keeping the newest version. Each write uses a temporary file forced to disk before replacing the target; multi-mine changes use one document. No backup files are created. Renaming cannot overwrite an existing mine. Invalid YAML prevents startup/reload instead of silently replacing data with empty definitions.

Ordinary edits apply immediately and queue persistence. Reload, shutdown and `tasks save` wait for writes to finish. Write failures log the complete exception, retain failed snapshots in memory and block further definition edits until recovery. Fix the disk problem and run `tasks save` to retry. An abrupt process termination can lose changes still waiting to be written.

Single-mine editors copy and serialize that mine, reusing unchanged definitions. Undo/redo stores only affected definitions, with up to 10 changes per administrator and an approximate 4 MiB text limit. It refuses to overwrite later edits to those same mines while allowing independent changes to other mines. History is cleared on logout/reload. World blocks are never restored by undo/redo.

The YAML format is specific to nMines. CataMines YAML is not imported automatically.

Official references: [Paper 26.3](https://papermc.io/news/26-3/), [Java requirements](https://docs.papermc.io/paper/getting-started/).

MIT license; see [LICENSE](LICENSE).
