# Strip Miner

A client-side Fabric mod for **Minecraft 26.3** that strip-mines on its own at a height you
choose, mines the ores it reveals, picks up the drops and avoids lava.

This is entirely vibe-coded.

I'm planning on adding a functionality to mine 2x2 spruce trees (and bigger clusters of spruce trees) in the future.

## What it does

```
   side view of a branch          top view
                                   LLLLL@RRRRR   @ = where the player stands (2 high)
   LLLLL@RRRRR  <- head height         |         L/R = 1 block high holes at head height
        @                              |  3 blocks forward
                                   LLLLL@RRRRR
```

1. Digs a staircase down (or up) to **Y = -58** (setting `targety`), straight ahead or as a
   2x2 spiral (setting `spiralstaircase`). When the next step would run into **lava, water or a
   cave**, it digs a short straight tunnel around it and then goes back to the staircase. `/stripmine start here` skips this and mines at your
   current height.
2. **Standing still** in the main tunnel, it mines a line of **5 blocks to the left at head
   height** (only the top block, 1 high).
3. It checks the blocks around that line for the ores you're looking for:
   - First it mines **every ore it can see** (touching open space), digging to each one and
     picking up the drops.
   - Then it works out, from where those ores were, **where more of the same cluster could be
     hiding**. A cluster fits in a 2x2x2 box (setting `clustersize`), so only blocks inside a
     2x2x2 box that also holds every ore found so far are possible. For example, four ores lying
     flat in a 2x2 square leave only the 4 blocks above and the 4 below; blocks it has already
     seen are crossed off. It digs the **fewest blocks needed** to check the rest. For each block it could dig, it counts
     how many hidden spots that block uncovers (including any blocks dug on the way there) and digs
     the one that uncovers the most per block. Any ore it finds is mined and the check repeats.
   - Then it walks back to its spot in the main tunnel.
4. It **turns right**, mines 5 blocks at head height to the right, and checks for ore the same way.
5. It mines and walks **3 blocks forward** (2 high), checks that stretch for ore, and repeats from
   step 2.

It always finishes the 5 blocks before going after ores. For example: mines 3 blocks → sees a
diamond → mines the last 2 blocks → mines the diamond(s) → goes back to the tunnel → mines the
other side.

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
- Moves food, filler blocks and torches (for collecting gravel) into the hotbar when needed.
- Re-mines gravel and sand that falls into the tunnel.

## Usage

Stand where you want the tunnel to start and **face the direction to mine**. Then:

| Command | What it does |
|---|---|
| `/stripmine start` | Dig stairs to Y = -58 (the `targety` setting), then mine until stopped |
| `/stripmine start -40` | Dig stairs to another height first |
| `/stripmine start here` | Mine at your current height (no stairs) |
| `/stripmine start time 30m` | Stop by itself after 30 minutes (`1h30m`, `90s`, or a plain number of minutes) |
| `/stripmine start ores 20` | Stop after mining 20 ore blocks |
| `/stripmine start items 64` | Stop after picking up 64 items from ores (diamonds, raw iron, ... Fortune drops count) |
| `/stripmine start length 200` | Stop once the main tunnel is 200 blocks long |
| `/stripmine start -58 ores 20` | Goals work after a Y level too |
| `/stripmine tree` | Chop down the 2x2 tree (or group of 2x2 trees) you are looking at, see below |
| `/stripmine stop` | Stop |
| `/stripmine pause` / `resume` | Freeze it where it is (you get control back) and carry on later |
| `/stripmine status` | Show progress |
| `/stripmine help [topic]` | List commands, or explain one |

**Help everywhere:** put `help` after any command instead of a value to see how it works, e.g.
`/stripmine start help`, `/stripmine start time help` or `/stripmine config settings veinradius help`.

While it runs, the top-left corner shows the **run time** (yellow), the **tunnel length** (blue)
and one green line per ore type it is looking for, with the ore blocks mined and the items they
dropped in brackets, e.g. `Diamond: 5 (12)`.

### Tree mode: `/stripmine tree`

Look at a log of a **2x2 tree** (big spruce, jungle or dark oak) and type `/stripmine tree`
(or bind a key under *Controls → Strip Miner*). If several 2x2 trees stand right next to each
other, it chops the whole group:

1. It picks the **corner** of the group nearest to you and digs a **staircase up along the outside
   edge** of the group (moving inwards only if it has to) until it stands on top of the corner
   tree. That height is the **working level**.
2. Every tree that sticks up above the working level is chopped down to it, one at a time: the
   **tallest tree next to the current one** first, or the **closest** one if no neighbour sticks
   up. If the top is in reach it mines straight up from where it stands; otherwise it digs a
   staircase up until it can reach the top.
3. When nothing sticks up any more, it **mines the working level away layer by layer**. That
   lowers the level until shorter trees (for example in the middle) come into reach, and finally
   takes the whole group down to the ground.
4. It picks up the wood (and saplings) that dropped.

Up in the trees it stops exactly on every block and never steps where there is no floor, so it
can't fall. Bring an axe.

### Settings: `/stripmine config`

`/stripmine config` (or just `/config`) lists every setting. Put `help` after a setting's name to
see what it does, its current value and its default.

**Tunnel shape:** `/stripmine config settings <name> <value>`

| Setting | Default | What it does |
|---|---|---|
| `branchlength` | 5 | Blocks mined to each side (at most 5, the furthest a player can reach) |
| `branchspacing` | 3 | Blocks forward between branches |
| `maxlength` | 0 | Always stop after this many tunnel blocks (0 = never) |
| `targety` | -58 | Height `/stripmine start` digs stairs to |
| `spiralstaircase` | false | Dig a 2x2 spiral staircase (turning right every step) instead of a straight one |
| `veinradius` | 10 | How far (in blocks, sideways from its spot in the tunnel) it may go to mine ore. Ore further away is left alone, so one big vein can't drag it off forever |

**Behaviour:** `/stripmine config <name> <value>`

| Setting | Default | What it does |
|---|---|---|
| `clustersize` | 2 | Ore clusters fit in a box this big (2 = 2x2x2); only blocks that could still be in such a box are checked for hidden ore. Adapts if a cluster turns out bigger |
| `minearoundores` | true | After mining the ore it can see, check where more of the same cluster could be hiding, digging as few blocks as possible. Off = only mine ore it can see |
| `isolatelava` | true | Fill lava next to an ore with gravel/sand instead of skipping the ore |
| `collectgravel` | true | Take back gravel pillars that are safe to remove (torch trick) |
| `compressfullstacks` | true | Craft full stacks into blocks at a crafting table |
| `dropjunk` | true | Throw away cobblestone/stone/dirt when the inventory is nearly full |
| `eatbelowfood` | 14 | Eat when hunger is at or below this (out of 20) |
| `stopbelowhealth` | 8 | Stop when health is at or below this (out of 20) |
| `keeptooldurability` | 5 | Never use a tool with this much durability left or less |

**Ores:** `/stripmine config ores add minecraft:ancient_debris`, `remove`, `list`, `clear`, `reset`
(default: diamond ore and deepslate diamond ore).

You can also bind a key for start/stop under *Options → Controls → Key Binds → Strip Miner*.

Settings are saved in `config/stripminer.json`.

**Bring:** a pickaxe in your **hotbar** (iron or better for diamonds), a shovel helps with gravel,
a few torches (to collect gravel), some food, a crafting table, some cobblestone, and gravel or sand for lava.

**Tips**
- Press **F3 + P** to turn off pause-on-lost-focus so it keeps going while you're in another window.
- The miner doesn't fight mobs. Check on it now and then. It stops if your
  health gets low.
- If a server has its own `/config` command, this mod's `/config` hides it while the mod is installed.
- Some multiplayer servers don't allow automation mods. Check the server rules first.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 and put
   [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
2. Download the jar from the [Releases page](https://github.com/Neronius-git/stripminer/releases)
   (the newest one is at the top) and put `stripminer-26.3.X.x.jar` in your `.minecraft/mods`
   folder.

Version numbers are **26.3.X.x** (see [CHANGELOG.md](CHANGELOG.md)): `26.3` is the Minecraft
version, **X** goes up for new features, **x** for small changes and fixes.

## Building it yourself

```bash
./gradlew build
```

The jar ends up in `build/libs/`. You need JDK 25 or newer.
