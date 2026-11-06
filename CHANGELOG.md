# Changelog

Version numbers: the **last** number goes up for small changes and fixes, the **middle** number
for bigger changes and new features (e.g. 2.0.0 → 2.0.1 → 2.1.0).

## 2.1.0
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

## 2.0.0
- Ported to Minecraft 26.3.
- Side branches are a one block high line at head height, mined while standing still.
- Keeps at most one stack of gravel; other stone is throwaway.
- Fills lava next to ores with gravel and collects the gravel back; minimal digging around veins.
- Crafts full stacks into blocks at a crafting table.
- `/config` and `/stripmine config` settings; torches off by default.
- Fixed a crash with Sodium.
