# Strip Miner

A client-side Fabric mod for **Minecraft 26.3** that does two jobs for you:

- **Strip mining:** digs down to diamond level, strip-mines on its own, mines every ore cluster
  it finds (including the hidden parts), picks up the drops and stays away from lava.
- **Tree chopping:** cuts down big 2x2 trees (spruce, jungle, dark oak), even whole groups of
  them, and collects the wood.

This is entirely vibe-coded.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 and put
   [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
2. Download the newest `stripminer-26.3.X.x.jar` from the
   [Releases page](https://github.com/Neronius-git/stripminer/releases) and put it in your
   `.minecraft/mods` folder.

## Quick start

- **Strip mining:** face the direction you want to mine and type `/stripmine start`.
- **Tree chopping:** look at a log of a 2x2 tree and type `/stripmine tree`.
- **Stop:** `/stripmine stop`. **Pause:** `/stripmine pause`, then `/stripmine resume`.
- **Help:** `/stripmine help`, or put `help` after any command, e.g. `/stripmine start help`.

You can also bind keys for both under *Options → Controls → Key Binds → Strip Miner*.

## Strip mining

```
   side view of a branch           top view
                                   LLLLL@RRRRR   @ = where the player stands (2 high)
   LLLLL@RRRRR  <- head height          |        L/R = 1 block high holes at head height
        @                               |        3 blocks forward
                                   LLLLL@RRRRR
```

1. **Digs down** to **Y = -58** (setting `targety`), with a straight staircase or a 2x2 spiral
   (setting `spiralstaircase`). Before every step it checks for **lava, water and caves**. If the
   next step would run into one, it switches to digging a short straight tunnel around it, then
   goes back to the staircase. `/stripmine start here` skips this and mines at your current height.
2. **Standing still** in the main tunnel, it mines a line of **5 blocks to the left at head
   height** (one block high).
3. It **checks for ore** around that line (see *Finding every ore* below), mines it, picks up the
   drops and walks back to its spot.
4. It **turns right**, mines 5 blocks to the right and checks for ore the same way.
5. It mines and walks **3 blocks forward** (2 high), checks that stretch for ore, and repeats from
   step 2.

It always finishes the 5 blocks before going after ore. For example: mines 3 blocks → sees a
diamond → mines the last 2 blocks → mines the diamonds → goes back → mines the other side.

### Finding every ore of a cluster

1. It mines **every ore it can see** (touching open space), nearest first, and picks up the drops.
2. Then it works out **where more of the same cluster could be hiding**. A cluster fits in a 2x2x2
   box (setting `clustersize`), so only blocks inside a 2x2x2 box that also holds every ore found
   so far are possible. Four ores lying flat in a 2x2 square, for example, leave only the 4 blocks
   above and the 4 below. Blocks it has already seen are crossed off.
3. It digs the **fewest blocks needed** to look at the rest: each time it picks the block that
   uncovers the most unchecked spots per block dug (counting the blocks it has to dig to get
   there). Any ore it finds is mined and the check repeats.

### Lava and other dangers

- It never mines a block that touches lava, never steps next to lava, and only walks on solid
  ground (never magma). That includes lava behind gravel or sand that would fall.
- If an **ore** touches lava, it **fills the lava with gravel or sand** first (setting
  `isolatelava`): the gravel sinks to the bottom of the pool and builds pillars up until no lava
  touches the ore. If lava still gets into the hole, it plugs it right away so the drops don't burn.
- Afterwards it **takes the gravel back** (setting `collectgravel`), but only pillars that don't
  touch lava: it mines the bottom block and puts a torch there, so the rest of the pillar breaks
  on the torch. Then it picks up the gravel and the torch.
- If lava is next to a **side branch**, the branch is cut short. If lava blocks the **main
  tunnel**, it stops and tells you where.
- Small gaps in the floor are filled with cobblestone or dirt.
- It stops at once if you touch lava or your health drops to 8 or below.

### Inventory and tools

- Picks the best tool in your hotbar for each block and never uses a tool with 5 or fewer
  durability left.
- Eats when hungry (skips rotten flesh, spider eyes, chorus fruit, ...).
- **Crafts full stacks into blocks:** when a stack of diamonds, raw iron, redstone, coal, lapis,
  ... is full, it places a crafting table, crafts the stacks into blocks and picks the table back up.
- **Keeps at most one stack of gravel** (for lava). Cobblestone, deepslate, dirt, tuff and other
  stone are throwaway: when the inventory is nearly full they are thrown away, except one stack
  kept for bridging gaps and blocking lava.

**Bring:** a pickaxe in your **hotbar** (iron or better for diamonds), a shovel for gravel, some
food, a crafting table, a few torches (for collecting gravel), cobblestone and gravel or sand.

## Tree mode

Look at a log of a **2x2 tree** (big spruce, jungle or dark oak) and type `/stripmine tree`.
If several 2x2 trees stand right next to each other, it chops the whole group:

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
4. It picks up the wood and saplings that dropped.

Up in the trees it stops exactly on every block, never steps where there is no floor and never
drops more than one block, so it can't fall. **Bring an axe.**

## Commands

| Command | What it does |
|---|---|
| `/stripmine start` | Dig stairs to Y = -58 (the `targety` setting), then strip-mine until stopped |
| `/stripmine start -40` | Dig stairs to another height first |
| `/stripmine start here` | Strip-mine at your current height |
| `/stripmine start time 30m` | Stop by itself after 30 minutes (`1h30m`, `90s`, or a number of minutes) |
| `/stripmine start ores 20` | Stop after mining 20 ore blocks |
| `/stripmine start items 64` | Stop after picking up 64 items from ores (Fortune drops count) |
| `/stripmine start length 200` | Stop once the main tunnel is 200 blocks long |
| `/stripmine start -58 ores 20` | Goals work after a height or `here` too |
| `/stripmine tree` | Chop the 2x2 tree (or group of trees) you are looking at |
| `/stripmine pause` / `resume` | Freeze it where it is (you get control back) and carry on later |
| `/stripmine stop` | Stop |
| `/stripmine status` | Show progress |
| `/stripmine config` | Show and change settings (see below) |
| `/stripmine help [topic]` | List commands, or explain one |

Put `help` after any command instead of a value to see how it works, e.g.
`/stripmine start time help` or `/stripmine config settings veinradius help`.

While it runs, the top-left corner shows the **run time**, the **tunnel length** and one line per
ore type with the ore blocks mined and the items they dropped, e.g. `Diamond: 5 (12)`. In tree
mode it shows the logs chopped.

## Settings

`/stripmine config` (or just `/config`) lists every setting. Put `help` after a setting's name to
see what it does, its current value and its default. Settings are saved in
`config/stripminer.json`.

**Tunnel:** `/stripmine config settings <name> <value>`

| Setting | Default | What it does |
|---|---|---|
| `targety` | -58 | Height `/stripmine start` digs down (or up) to |
| `spiralstaircase` | false | Dig a 2x2 spiral staircase instead of a straight one |
| `branchlength` | 5 | Blocks mined to each side (at most 5, the furthest a player can reach) |
| `branchspacing` | 3 | Blocks forward between branches |
| `maxlength` | 0 | Always stop after this many tunnel blocks (0 = never) |
| `veinradius` | 10 | How far (sideways from its spot in the tunnel) it may go to mine ore |

**Behaviour:** `/stripmine config <name> <value>`

| Setting | Default | What it does |
|---|---|---|
| `clustersize` | 2 | Ore clusters fit in a box this big (2 = 2x2x2); adapts if a cluster is bigger |
| `minearoundores` | true | Look for hidden ore of a cluster. Off = only mine ore it can see |
| `isolatelava` | true | Fill lava next to an ore with gravel/sand instead of skipping the ore |
| `collectgravel` | true | Take back gravel pillars that are safe to remove |
| `compressfullstacks` | true | Craft full stacks into blocks at a crafting table |
| `dropjunk` | true | Throw away stone when the inventory is nearly full |
| `eatbelowfood` | 14 | Eat when hunger is at or below this (out of 20) |
| `stopbelowhealth` | 8 | Stop when health is at or below this (out of 20) |
| `keeptooldurability` | 5 | Never use a tool with this much durability left or less |

**Ores:** `/stripmine config ores add minecraft:ancient_debris`, `remove`, `list`, `clear`,
`reset` (default: diamond ore and deepslate diamond ore).

## Tips

- Press **F3 + P** to turn off pause-on-lost-focus, so it keeps going while you're in another window.
- It doesn't fight mobs. Check on it now and then; it stops if your health gets low.
- If a server has its own `/config` command, this mod's `/config` hides it. `/stripmine config`
  always works.
- Many multiplayer servers don't allow automation mods. Check the server rules first.

## Versions

Version numbers are **26.3.X.x**: `26.3` is the Minecraft version, **X** goes up for new features,
**x** for fixes. See [CHANGELOG.md](CHANGELOG.md). Every new version is published on the
[Releases page](https://github.com/Neronius-git/stripminer/releases) automatically.

## Building it yourself

You need JDK 25 or newer.

```bash
./gradlew build
```

The jar ends up in `build/libs/`. To test changes, `./gradlew runClient` starts Minecraft with the
mod loaded (or use the *Minecraft Client* run configuration in IntelliJ IDEA).

## Bugs and ideas

Found a bug or have an idea? [Open an issue](https://github.com/Neronius-git/stripminer/issues/new/choose).
For bugs, the chat message the mod showed (or the crash log) helps a lot.

## License

[MIT](LICENSE)
