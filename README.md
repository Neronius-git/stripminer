# Strip Miner

A client-side Fabric mod for **Minecraft 1.21.1** that strip-mines on its own at a height you
choose, mines the ores it reveals, picks up the drops, avoids lava and lights the tunnel with torches.

## What it does

```
   LLLLL@RRRRR      @ = main tunnel, L/R = 5-block branches (1 wide, 2 high)
        |
        |           6 blocks forward
        |
   LLLLL@RRRRR
```

1. *(Optional)* Digs a staircase down (or up) to the Y level you pick.
2. Mines **5 blocks to the left**.
3. When the whole branch is open, it checks the walls, floor and ceiling for the ores you're
   looking for. It digs to each ore, mines the **whole vein** (diagonal neighbours too), waits
   for the drops and walks over to any it didn't pick up, then **walks back to the main tunnel**.
4. Does the same **5 blocks to the right**.
5. Mines **6 blocks forward**, checks the new stretch of tunnel for ores, and repeats from step 2.
6. Places a **torch on the tunnel floor every 28 blocks** (and one at the start).

It always finishes the 5 blocks before going after ores. For example: mines 3 blocks → sees a
diamond → mines the last 2 blocks → mines the diamond(s) → goes back to the tunnel → mines the
other side.

### Lava safety

- It never mines a block that has lava next to it, and never steps into a space that touches lava.
  That includes lava behind a column of gravel or sand that would fall.
- It only walks on solid blocks (never magma), so it won't fall into caves or lava pools. Small gaps
  in a branch or the main tunnel are filled with cobblestone or dirt from your inventory.
- If lava blocks a **branch**, the branch is cut short and mining continues.
- If an **ore** is next to lava, that ore is skipped.
- If lava blocks the **main tunnel**, the miner **stops** and tells you where.
- If you ever touch lava, or your health drops to 8 or below, it stops right away.

### Other features

- Picks the best tool in your hotbar for each block, and never uses a tool with 5 or fewer
  durability points left.
- Eats food from your inventory when hungry (skips rotten flesh, spider eyes, chorus fruit, etc.).
- When the inventory is almost full, it throws cobblestone, deepslate, dirt, gravel and other junk
  behind it. It keeps one stack for filling gaps.
- Moves torches, food and filler blocks from your inventory into the hotbar when needed.
- Re-mines gravel and sand that falls into the tunnel.

## Usage

Stand where you want the tunnel to start and **face the direction to mine**. Then:

| Command | What it does |
|---|---|
| `/stripmine start` | Start mining at your current height |
| `/stripmine start -58` | Dig stairs down to Y = -58 first, then strip-mine there (a good height for diamonds) |
| `/stripmine stop` | Stop |
| `/stripmine status` | Show progress and settings |
| `/stripmine ores list` | Show which ores it looks for (default: diamond ore + deepslate diamond ore) |
| `/stripmine ores add minecraft:ancient_debris` | Also look for another block (tab-completes ores) |
| `/stripmine ores remove <block>` / `clear` / `reset` | Change the ore list |
| `/stripmine set branchLength 5` | Blocks per side branch |
| `/stripmine set branchSpacing 6` | Blocks forward between branches |
| `/stripmine set torchInterval 28` | Torch spacing on the floor (0 = no torches) |
| `/stripmine set maxLength 0` | Stop after this many tunnel blocks (0 = unlimited) |
| `/stripmine set veinRadius 8` | How far from the tunnel it may dig to follow a vein |
| `/stripmine set eatBelowFood 14` / `stopBelowHealth 8` / `dropJunk true` | Survival settings |

You can also bind a key for start/stop under *Options → Controls → Key Binds → Strip Miner*.

Settings are saved in `config/stripminer.json`.

**Bring:** a pickaxe in your **hotbar** (iron or better for diamonds), torches, some food, and a
few cobblestone.

**Tips**
- Press **F3 + P** to turn off pause-on-lost-focus so it keeps going while you're in another window.
- The miner doesn't fight mobs. The torches help, but check on it now and then. It stops if your
  health gets low.
- Some multiplayer servers don't allow automation mods. Check the server rules first.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.1 and put
   [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
2. Download the jar: open the **Actions** tab of this repository, click the latest successful
   **build** run, and download the **stripminer-mod-jar** artifact. Unzip it and put
   `stripminer-1.0.0.jar` (not the `-sources` one) in your `.minecraft/mods` folder.

## Building it yourself

```bash
./gradlew build
```

The jar ends up in `build/libs/`. You need JDK 21 or newer.
