# Strip Miner

A client-side Fabric mod for **Minecraft 26.3** that strip-mines on its own at a height you
choose, mines the ores it reveals, picks up the drops and avoids lava.

## What it does

```
   side view of a branch           top view
                                   LLLLL@RRRRR   @ = where the player stands (2 high)
   LLLLL@RRRRR  <- head height          |        L/R = 1 block high holes at head height
        @                               |        3 blocks forward
                                   LLLLL@RRRRR
```

1. *(Optional)* Digs a staircase down (or up) to the Y level you pick.
2. **Standing still** in the main tunnel, it mines a line of **5 blocks to the left at head
   height** (only the top block, 1 high).
3. It checks the blocks around that line for the ores you're looking for. It digs to each ore,
   mines the **whole vein** (diagonal neighbours too), picks up the drops, then walks back to its
   spot in the main tunnel.
4. It **turns right**, mines 5 blocks at head height to the right, and checks for ore the same way.
5. It mines and walks **3 blocks forward** (2 high), checks that stretch for ore, and repeats from
   step 2.

It always finishes the 5 blocks before going after ores. For example: mines 3 blocks → sees a
diamond → mines the last 2 blocks → mines the diamond(s) → goes back to the tunnel → mines the
other side.

Torches are **off by default**. Turn them on with `/config torchinterval 28` to place one on the
tunnel floor every 28 blocks.

### Lava safety

- It never mines a block that has lava next to it, and never steps into a space that touches lava.
  That includes lava behind a column of gravel or sand that would fall.
- It only walks on solid blocks (never magma), so it won't fall into caves or lava pools. Small gaps
  in a branch or the main tunnel are filled with cobblestone or dirt from your inventory.
- If lava is next to a **branch** block, the branch is cut short and mining continues.
- If an **ore** touches lava, it **fills the lava in with gravel or sand** before mining the ore
  (setting `isolatelava`). It digs to a safe spot within reach of the lava and drops gravel/sand
  into it. The gravel sinks to the bottom of the pool and builds pillars upward until no lava
  touches the ore any more. It waits to make sure the lava doesn't flow back, then mines the ore.
  It uses cobblestone/dirt if it has no gravel or sand. If lava still gets into the hole, it plugs
  the hole straight away so the drops don't burn.
- Afterwards it **takes the gravel back** (setting `collectgravel`). It only takes pillars where
  no block of the pillar touches lava, so removing them can't let lava flow. It mines the bottom
  block and puts a torch in its place right away, so the rest of the pillar breaks on the torch
  and drops as items. Then it picks up the gravel and the torch. Pillars still touching lava are
  left where they are.
- If lava blocks the **main tunnel**, the miner **stops** and tells you where.
- If you ever touch lava, or your health drops to 8 or below, it stops right away.

### Other features

- Picks the best tool in your hotbar for each block, and never uses a tool with 5 or fewer
  durability points left.
- Eats food from your inventory when hungry (skips rotten flesh, spider eyes, chorus fruit, etc.).
- **Crafts full stacks into blocks:** when a stack of diamonds, raw iron, redstone, coal, lapis,
  etc. is full (64), it places a crafting table next to itself, crafts those stacks into blocks,
  then mines the table and picks it back up. Bring a crafting table. Turn off with
  `/config compressfullstacks false`.
- **Keeps at most one stack of gravel** (or sand) for filling lava. Extra gravel is merged into
  that stack and the rest is thrown away. All other stone-type blocks (cobblestone, deepslate,
  dirt, tuff, ...) are treated as throwaway blocks: when the inventory is almost full it throws
  them behind it, keeping one stack to bridge gaps and block off lava.
- Moves torches, food and filler blocks from your inventory into the hotbar when needed.
- Re-mines gravel and sand that falls into the tunnel.

## Usage

Stand where you want the tunnel to start and **face the direction to mine**. Then:

| Command | What it does |
|---|---|
| `/stripmine start` | Start mining at your current height |
| `/stripmine start -58` | Dig stairs down to Y = -58 first, then strip-mine there (a good height for diamonds) |
| `/stripmine stop` | Stop |
| `/stripmine status` | Show progress |

### Settings: `/config` or `/stripmine config`

Type `/config` (or `/stripmine config`) to see every setting and its value,
`/config <setting>` to see one, and `/config <setting> <value>` to change one
(tab-completion works). For example `/stripmine config torchinterval 28`.

| Setting | Default | What it does |
|---|---|---|
| `branchlength` | 5 | Blocks mined to each side (at most 5, the furthest a player can reach) |
| `branchspacing` | 3 | Blocks forward between branches |
| `torchinterval` | 0 | Blocks between floor torches (0 = no torches) |
| `maxlength` | 0 | Stop after this many tunnel blocks (0 = never) |
| `veinradius` | 8 | How far (in blocks, sideways from where it stands in the tunnel) it may go to mine ores. Ores further away are left alone, so one big vein can't drag it off forever |
| `compressfullstacks` | true | Craft full stacks into blocks at a crafting table |
| `minearoundores` | true | After mining a vein, dig the fewest extra blocks needed so every block touching the vein (diagonals too) has been uncovered, in case more ore hides there. Mostly useful on servers with anti-xray; on normal worlds the mod can already see hidden ore |
| `isolatelava` | true | Fill lava next to an ore with gravel/sand instead of skipping the ore |
| `collectgravel` | true | Take back gravel pillars that are safe to remove (torch trick) |
| `dropjunk` | true | Throw away cobblestone/stone/dirt when the inventory is nearly full |
| `eatbelowfood` | 14 | Eat when hunger is at or below this (out of 20) |
| `stopbelowhealth` | 8 | Stop when health is at or below this (out of 20) |
| `keeptooldurability` | 5 | Never use a tool with this much durability left or less |
| `ores` | diamond ores | `/config ores add minecraft:ancient_debris`, `remove`, `list`, `clear`, `reset` |

You can also bind a key for start/stop under *Options → Controls → Key Binds → Strip Miner*.

Settings are saved in `config/stripminer.json`.

**Bring:** a pickaxe in your **hotbar** (iron or better for diamonds), a shovel helps with gravel,
torches, some food, a crafting table, some cobblestone, and gravel or sand for lava.

**Tips**
- Press **F3 + P** to turn off pause-on-lost-focus so it keeps going while you're in another window.
- The miner doesn't fight mobs. Check on it now and then (torches are off unless you turn them on). It stops if your
  health gets low.
- If a server has its own `/config` command, this mod's `/config` hides it while the mod is installed.
- Some multiplayer servers don't allow automation mods. Check the server rules first.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 and put
   [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
2. Download the jar: open the **Actions** tab of this repository, click the latest successful
   **build** run, and download the **stripminer-mod-jar** artifact. Unzip it and put
   `stripminer-2.0.0.jar` (not the `-sources` one) in your `.minecraft/mods` folder.

## Building it yourself

```bash
./gradlew build
```

The jar ends up in `build/libs/`. You need JDK 25 or newer.
