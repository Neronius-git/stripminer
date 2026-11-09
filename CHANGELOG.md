# Changelog

Version numbers are **26.3.X.x**: `26.3` is the Minecraft version, **X** goes up for bigger changes
and new features, **x** for small changes and fixes (e.g. 26.3.5.0 → 26.3.5.1 → 26.3.6.0).
Older versions were called 2.0.0-2.5.0; they are listed here under their new numbers.

## 26.3.5.1
- Fixed tree mode stopping with "no floor to stand on": paths no longer plan to stand on a block
  that an earlier step of the same path digs away, and the climb re-plans instead of giving up.
- New version numbers matching Minecraft (26.3.X.x).
- Releases with a ready-to-use jar are now published on GitHub.

## 26.3.5.0
- Reworked tree mode: starts at the corner nearest to you and climbs a staircase up the outside
  edge of the group to the top of the corner tree (the working level); chops every tree that
  sticks up down to that level (tallest neighbour first, else the closest; mines straight up if
  the top is in reach, otherwise digs a staircase up); then lowers the level layer by layer until
  shorter trees are in reach and everything is down.
- Removed placing torches in the strip mine (the `torchinterval` setting is gone).

## 26.3.4.0
- New tree mode: `/stripmine tree` chops the 2x2 tree (or group of connected 2x2 trees) you are
  looking at: spiral staircase up inside a trunk at the edge, tallest trees cut down to the
  others first, then the whole group layer by layer, then the wood is picked up.
- Staircase adapts: if the next step would hit lava, water or a cave, it digs a short straight
  tunnel around it, then switches back to the chosen staircase mode.

## 26.3.3.0
- Smarter hidden-ore check: clusters are assumed to fit in a 2x2x2 box (`clustersize`), so only
  blocks that could still share such a box with every ore found are checked (e.g. a flat 2x2 of
  ores leaves just the 4 blocks above and 4 below). Adapts if a cluster is bigger.
- Faster digging everywhere: no more waiting after each block unless gravel/sand could fall.
- Faster staircase: keeps walking from step to step.
- New `spiralstaircase` setting: 2x2 spiral staircase instead of a straight one.
- `/stripmine start` now digs to Y = -58 by default (`targety` setting);
  `/stripmine start here` mines at the current height.

## 26.3.2.0
- Reworked ore mining: first mines every ore it can see, then works out from the mined ores
  where more of the cluster could be hiding and digs the fewest blocks needed to check (counting
  the blocks dug on the way there). Repeats when it finds more.
- New on-screen overlay (top left): run time, tunnel length and one line per ore type with
  ore blocks mined and items in brackets, e.g. `Diamond: 5 (12)`.
- New `/stripmine pause` and `/stripmine resume`.

## 26.3.1.0
- New goals: `/stripmine start time <duration>`, `ores <count>`, `items <count>`,
  `length <blocks>` (also after a Y level) stop the miner by themselves.
- New action bar: run time (yellow), tunnel length (blue), ores mined with the items they
  dropped in brackets (green).
- Tunnel settings moved to `/stripmine config settings <name> <value>` (branchlength,
  branchspacing, torchinterval, maxlength, veinradius).
- `help` works after any command, e.g. `/stripmine start help`; `/stripmine help <topic>`.
- No more sneaking when walking; it eases into its final spot instead.
- `veinradius` default is now 10.
- Ore inside a branch line is left for the ore logic, so its drops get picked up.

## 26.3.0.0
- Ported to Minecraft 26.3.
- Side branches are a one block high line at head height, mined while standing still.
- Keeps at most one stack of gravel; other stone is throwaway.
- Fills lava next to ores with gravel and collects the gravel back; minimal digging around veins.
- Crafts full stacks into blocks at a crafting table.
- `/config` and `/stripmine config` settings; torches off by default.
- Fixed a crash with Sodium.
