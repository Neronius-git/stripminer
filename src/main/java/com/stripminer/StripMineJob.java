package com.stripminer;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static com.stripminer.WorldChecks.fmt;
import static com.stripminer.WorldChecks.hazard;
import static com.stripminer.WorldChecks.isLava;
import static com.stripminer.WorldChecks.isSolidFloor;
import static com.stripminer.WorldChecks.passable;

/**
 * The strip-mining routine. Runs on the miner thread; every call to {@link #tick()} waits for the
 * next game tick (see {@link MinerRunner}).
 *
 * <pre>
 * Pattern (top view, tunnel going up):
 *
 *     LLLLL@RRRRR    branch: 5 left, 5 right (1 wide, 2 high)
 *          |
 *          |         3 blocks forward
 *     LLLLL@RRRRR
 * </pre>
 *
 * Each branch is mined to its full length first; then every target ore that is now visible from
 * the branch is mined (digging a path to it if needed, following the whole vein), the drops are
 * picked up, and the miner walks back to the main tunnel before doing the other side.
 */
public final class StripMineJob {
	private static final Set<Item> JUNK = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE,
			Items.DEEPSLATE, Items.DIRT, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF,
			Items.NETHERRACK, Items.CALCITE, Items.FLINT, Items.SMOOTH_BASALT, Items.BASALT, Items.BLACKSTONE);
	private static final Set<Item> FILLER = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE,
			Items.DEEPSLATE, Items.DIRT, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.NETHERRACK,
			Items.BLACKSTONE);
	/** Blocks that fall: dropped into lava they sink and fill the pool from the bottom. */
	private static final Set<Item> FALLING_FILLER = Set.of(Items.GRAVEL, Items.SAND, Items.RED_SAND);
	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
			Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.CHICKEN);

	private final Minecraft mc = Minecraft.getInstance();
	private final MinerConfig cfg;
	private final Integer targetY;

	private Set<Block> targets;
	private Direction forward;
	private int tunnelLength;
	private int lastTorchAt;
	private int oresMined;
	/** Items (diamonds, raw iron, ...) picked up from the ores that were mined. */
	private int resources;
	/** Game ticks since the start (20 per second). */
	private long ticks;
	private final Goal goal;
	/** Per ore type ("Diamond", "Iron", ...): {ore blocks mined, items picked up}. */
	private final Map<String, int[]> perType = new LinkedHashMap<>();
	/** What the on-screen overlay shows; rebuilt every half second, read by the render thread. */
	private volatile List<HudLine> hud = List.of();

	/** One line of the on-screen overlay. */
	public record HudLine(String text, int color) {
	}
	private boolean warnedNoTorches;
	private boolean warnedNoTable;
	private volatile String phase = "starting";
	private String lastFailure = "";
	/** Blocks dug since the last call to {@link #takeCleared()}, used to spot newly revealed ores. */
	private final List<BlockPos> cleared = new ArrayList<>();
	/** Where gravel/sand was dropped into lava, so the pillars can be collected again. */
	private final List<BlockPos> placedGravel = new ArrayList<>();

	public StripMineJob(MinerConfig cfg, Integer targetY, Goal goal) {
		this.cfg = cfg;
		this.targetY = targetY;
		this.goal = goal;
	}

	public String status() {
		return "§e" + Goal.formatTicks(ticks) + "§r running, §b" + tunnelLength + "§r blocks of tunnel, §a"
				+ oresMined + "§r ores mined (§a" + resources + "§r items" + perTypeText() + "), now " + phase
				+ (goal != null ? ". Goal: " + goal.describe() : "");
	}

	private String perTypeText() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, int[]> e : perType.entrySet()) {
			sb.append("; ").append(e.getKey()).append(' ').append(e.getValue()[0]).append(" (").append(e.getValue()[1]).append(')');
		}
		return sb.toString();
	}

	public List<HudLine> hudLines() {
		return hud;
	}

	/** Rebuilds the overlay: run time, tunnel length, then one line per ore type: "Diamond: 5 (12)". */
	private void updateHud() {
		List<HudLine> lines = new ArrayList<>();
		lines.add(new HudLine("Time " + Goal.formatTicks(ticks), 0xFFFFFF55));
		lines.add(new HudLine("Tunnel " + tunnelLength, 0xFF55FFFF));
		for (Map.Entry<String, int[]> e : perType.entrySet()) {
			lines.add(new HudLine(e.getKey() + ": " + e.getValue()[0] + " (" + e.getValue()[1] + ")", 0xFF55FF55));
		}
		hud = List.copyOf(lines);
	}

	/** Stops the run once its goal is reached. */
	private void checkGoal() {
		if (goal == null) {
			return;
		}
		boolean done = switch (goal.type()) {
			case TIME -> ticks >= goal.amount();
			case ORES -> oresMined >= goal.amount();
			case ITEMS -> resources >= goal.amount();
			case LENGTH -> tunnelLength >= goal.amount();
		};
		if (done) {
			throw new StopException("§aGoal reached (" + goal.describe() + "): " + Goal.formatTicks(ticks)
					+ ", tunnel " + tunnelLength + ", " + oresMined + " ores (" + resources + " items).");
		}
	}

	// ------------------------------------------------------------------------------------------
	// Main routine
	// ------------------------------------------------------------------------------------------

	void run() {
		targets = cfg.resolveOres();
		if (targets.isEmpty()) {
			throw new StopException("No valid ores configured. Use /config ores add <block>.");
		}
		for (Block b : targets) {
			perType.putIfAbsent(typeName(b), new int[2]);
		}
		updateHud();
		forward = player().getDirection();
		checkTools();
		msg("Strip mining towards §b" + forward.getName() + "§r, looking for " + cfg.ores
				+ (goal != null ? ". Goal: " + goal.describe() : "") + ". Use §b/stripmine stop§r to stop.");

		phase = "centering";
		moveTo(Vec3.atBottomCenterOf(feet()));

		if (targetY != null && targetY != feet().getY()) {
			goToHeight(targetY);
		}

		tunnelLength = 0;
		lastTorchAt = 0;
		if (cfg.torchInterval > 0) {
			placeTorchHere();
		}

		while (true) {
			checkGoal();
			if (cfg.maxTunnelLength > 0 && tunnelLength >= cfg.maxTunnelLength) {
				msg("Reached the tunnel length limit of " + cfg.maxTunnelLength + " blocks. Mined " + oresMined + " ores.");
				return;
			}
			checkpoint(true);
			BlockPos home = feet();

			branch(home, forward.getCounterClockWise(), "left");
			branch(home, forward.getClockWise(), "right");

			phase = "advancing";
			List<BlockPos> segment = new ArrayList<>();
			for (int i = 0; i < cfg.branchSpacing; i++) {
				checkpoint(false);
				BlockPos cur = feet();
				BlockPos next = cur.relative(forward);
				if (!step(cur, next, true)) {
					throw new StopException("Main tunnel blocked at " + fmt(next) + " (" + lastFailure
							+ "). Stopped to stay safe. Turn around or pick a new spot and start again.");
				}
				segment.add(next);
				segment.add(next.above());
				tunnelLength++;
				if (cfg.torchInterval > 0 && tunnelLength - lastTorchAt >= cfg.torchInterval) {
					placeTorchHere();
					lastTorchAt = tunnelLength;
				}
			}
			BlockPos newHome = feet();
			phase = "checking tunnel for ores";
			mineRevealedOres(segment, newHome);
			returnTo(newHome);
		}
	}

	/** Furthest block that can be mined while standing in the tunnel (survival reach). */
	static final int MAX_BRANCH_LENGTH = 5;

	/**
	 * Mines a one block high line at head height to one side while standing still in the main
	 * tunnel, then mines the ores it revealed and comes back to {@code home}.
	 */
	private void branch(BlockPos home, Direction side, String name) {
		phase = "mining " + name + " branch";
		List<BlockPos> dug = new ArrayList<>();
		int length = Math.min(cfg.branchLength, MAX_BRANCH_LENGTH);
		for (int i = 1; i <= length; i++) {
			checkpoint(false);
			BlockPos block = home.above().relative(side, i);
			if (isTarget(block)) {
				continue; // leave ore in the line for the ore logic, so its drops get picked up
			}
			if (!clearBlocks(List.of(block))) {
				msg("The " + name + " branch was cut short after " + (i - 1) + " blocks: " + lastFailure + ".");
				break;
			}
			dug.add(block);
		}
		phase = "checking " + name + " branch for ores";
		dug.add(home.above());
		mineRevealedOres(dug, home);
		phase = "returning to tunnel";
		returnTo(home);
	}

	/**
	 * Digs a staircase until the feet are at height {@code y}: straight ahead in the facing
	 * direction, or (with the spiralstaircase setting) a spiral that turns right every step and
	 * stays inside a 2x2 column. It keeps walking from step to step without stopping.
	 */
	private void goToHeight(int y) {
		boolean spiral = cfg.spiralStaircase;
		phase = "digging " + (spiral ? "a spiral" : "a") + " staircase to Y=" + y;
		msg("Digging " + (spiral ? "a spiral" : "a") + " staircase to Y=" + y + "...");
		Direction dir = forward;
		try {
			while (feet().getY() != y) {
				checkpoint(false);
				BlockPos cur = feet();
				int dy = y < cur.getY() ? -1 : 1;
				BlockPos next = cur.relative(dir).offset(0, dy, 0);
				boolean last = next.getY() == y;
				if (!step(cur, next, false, last)) {
					throw new StopException("Can't continue the staircase at " + fmt(next) + " (" + lastFailure
							+ "). Stopped to stay safe.");
				}
				if (spiral) {
					dir = dir.getClockWise();
				}
			}
		} finally {
			releaseMovement();
		}
		moveTo(Vec3.atBottomCenterOf(feet()));
	}

	// ------------------------------------------------------------------------------------------
	// Ores
	// ------------------------------------------------------------------------------------------

	private boolean isTarget(BlockPos pos) {
		return targets.contains(WorldChecks.level().getBlockState(pos).getBlock());
	}

	/**
	 * Mines the ore found around the given open blocks, in two repeating steps:
	 * <ol>
	 * <li>Mine every target ore that is visible right now (touching open space), nearest first.</li>
	 * <li>From the positions of the mined ores, work out where more of the same cluster could hide
	 * (see {@link #clusterCandidates}) among the blocks that haven't been seen yet. Then dig
	 * the block that uncovers the most of those hidden spots for the fewest blocks dug (counting
	 * the blocks dug to get there too). If that reveals ore, go back to step 1.</li>
	 * </ol>
	 * It stops when nothing is left that could hide more of the cluster.
	 */
	private void mineRevealedOres(Collection<BlockPos> openBlocks, BlockPos origin) {
		takeCleared();
		placedGravel.clear();
		Set<BlockPos> open = new HashSet<>(openBlocks); // dug-out blocks whose sides we can see
		Set<BlockPos> cluster = new HashSet<>();        // ores mined in this search
		Set<BlockPos> skipped = new HashSet<>();        // ores that can't be mined safely
		Set<BlockPos> failed = new HashSet<>();         // blocks we couldn't dig to look behind
		int work = 0;
		while (work++ < 600) {
			checkpoint(false);
			open.addAll(takeCleared());

			// Step 1: mine what is visible.
			List<BlockPos> visible = visibleOres(open, cluster, skipped, origin);
			if (!visible.isEmpty()) {
				BlockPos me = feet();
				visible.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
				BlockPos ore = visible.get(0);
				if (mineOre(ore, origin)) {
					cluster.add(ore);
					open.add(ore);
				} else {
					skipped.add(ore);
				}
				continue;
			}

			// Step 2: look where more of the cluster could be hiding.
			if (!cfg.mineAroundOres || cluster.isEmpty()) {
				break;
			}
			BlockPos dig = bestRevealBlock(cluster, origin, failed);
			if (dig == null) {
				break; // every spot that could hide more of this cluster has been checked
			}
			if (isTarget(dig)) {
				// The block we were going to dig to look is itself ore.
				if (mineOre(dig, origin)) {
					cluster.add(dig);
					open.add(dig);
				} else {
					skipped.add(dig);
					failed.add(dig);
				}
				continue;
			}
			phase = "checking for hidden ore";
			if (reach(dig, origin) && mineBlock(dig)) {
				open.add(dig);
			} else {
				failed.add(dig);
			}
		}
		recoverGravel(origin);
	}

	/** Target ores that can be seen now: touching our dug-out space, or part of the cluster and touching any open space. */
	private List<BlockPos> visibleOres(Set<BlockPos> open, Set<BlockPos> cluster, Set<BlockPos> skipped, BlockPos origin) {
		Set<BlockPos> found = new LinkedHashSet<>();
		for (BlockPos o : open) {
			for (Direction d : Direction.values()) {
				BlockPos n = o.relative(d);
				if (!skipped.contains(n) && !cluster.contains(n) && inRange(n, origin) && isTarget(n)) {
					found.add(n);
				}
			}
		}
		for (BlockPos ore : cluster) {
			for (BlockPos n : around(ore)) {
				if (!skipped.contains(n) && !cluster.contains(n) && inRange(n, origin) && isTarget(n) && isSeen(n)) {
					found.add(n);
				}
			}
		}
		return new ArrayList<>(found);
	}

	/** Goes to an ore, makes it safe, mines it and picks up the drops. Returns false if it was skipped. */
	private boolean mineOre(BlockPos ore, BlockPos origin) {
		String type = typeName(WorldChecks.level().getBlockState(ore).getBlock());
		if (!prepareOre(ore, origin)) {
			return false;
		}
		phase = "mining " + type.toLowerCase(java.util.Locale.ROOT) + " ore";
		Map<Item, Integer> before = countResources();
		if (!mineBlock(ore)) {
			return false;
		}
		if (!WorldChecks.lavaNeighbors(ore).isEmpty() && !feet().equals(ore)) {
			// Lava found its way in after all: plug the hole before the drops can burn.
			sealHole(ore);
		}
		collectDrops(ore, origin);
		int items = gained(before, countResources());
		oresMined++;
		resources += items;
		int[] counts = perType.computeIfAbsent(type, k -> new int[2]);
		counts[0]++;
		counts[1] += items;
		return true;
	}

	/** "minecraft:deepslate_diamond_ore" -> "Diamond", "minecraft:ancient_debris" -> "Ancient Debris". */
	static String typeName(Block block) {
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (path.startsWith("deepslate_")) {
			path = path.substring("deepslate_".length());
		}
		if (path.endsWith("_ore")) {
			path = path.substring(0, path.length() - "_ore".length());
		}
		StringBuilder sb = new StringBuilder();
		for (String word : path.split("_")) {
			if (!word.isEmpty()) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return sb.toString();
	}

	/** The 26 blocks around {@code pos} (sides, edges and corners). */
	private static List<BlockPos> around(BlockPos pos) {
		List<BlockPos> list = new ArrayList<>(26);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dy != 0 || dz != 0) {
						list.add(pos.offset(dx, dy, dz));
					}
				}
			}
		}
		return list;
	}

	/** A block counts as seen if it is open or touches an open block on one of its sides. */
	private static boolean isSeen(BlockPos pos) {
		if (passable(pos)) {
			return true;
		}
		for (Direction d : Direction.values()) {
			if (passable(pos.relative(d))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Picks the next block to dig to look for hidden ore of the cluster. A hidden spot is a block
	 * touching a mined ore (sides, edges or corners) that hasn't been seen yet; digging a block
	 * reveals itself and its 6 neighbours. For the most promising blocks it also counts the blocks
	 * that must be dug to reach them (those reveal things too) and picks the one that reveals the
	 * most hidden spots per block dug.
	 */
	private BlockPos bestRevealBlock(Set<BlockPos> cluster, BlockPos origin, Set<BlockPos> failed) {
		Set<BlockPos> unseen = new HashSet<>();
		for (BlockPos n : clusterCandidates(cluster)) {
			if (inRange(n, origin) && !isSeen(n)) {
				unseen.add(n);
			}
		}
		if (unseen.isEmpty()) {
			return null;
		}
		// How many hidden spots each block would reveal on its own.
		Map<BlockPos, Integer> score = new HashMap<>();
		for (BlockPos c : unseen) {
			score.merge(c, 1, Integer::sum);
			for (Direction d : Direction.values()) {
				score.merge(c.relative(d), 1, Integer::sum);
			}
		}
		BlockPos me = feet();
		List<BlockPos> options = new ArrayList<>();
		for (BlockPos p : score.keySet()) {
			if (!failed.contains(p) && !passable(p) && WorldChecks.canMine(p) && hazard(p) == null
					&& !isTunnelFloor(p, origin) && inRange(p, origin)) {
				options.add(p);
			}
		}
		options.sort(Comparator.<BlockPos>comparingInt(p -> -score.get(p)).thenComparingDouble(p -> p.distSqr(me)));

		BlockPos best = null;
		double bestValue = 0;
		int bestDug = Integer.MAX_VALUE;
		for (BlockPos option : options.subList(0, Math.min(8, options.size()))) {
			List<BlockPos> path = PathFinder.find(me, c -> canMineFrom(c, option), option, origin, cfg.veinRadius + 2, 8);
			if (path == null) {
				failed.add(option);
				continue;
			}
			Set<BlockPos> dug = new HashSet<>();
			BlockPos cur = me;
			for (BlockPos next : path) {
				for (BlockPos b : WorldChecks.required(cur, next)) {
					if (!passable(b)) {
						dug.add(b);
					}
				}
				cur = next;
			}
			dug.add(option);
			int revealed = 0;
			for (BlockPos u : unseen) {
				boolean hit = dug.contains(u);
				for (Direction d : Direction.values()) {
					hit |= dug.contains(u.relative(d));
				}
				if (hit) {
					revealed++;
				}
			}
			double value = revealed / (double) dug.size();
			if (revealed > 0 && (value > bestValue + 1e-9 || (Math.abs(value - bestValue) < 1e-9 && dug.size() < bestDug))) {
				best = option;
				bestValue = value;
				bestDug = dug.size();
			}
		}
		return best;
	}

	/**
	 * Every block that could still belong to the cluster. A cluster fits in a box of
	 * clustersize x clustersize x clustersize blocks (2x2x2 by default), so only the blocks inside
	 * some such box that also holds every ore found so far are possible. Example: four ores in a
	 * flat 2x2 square leave only the 4 blocks above and the 4 below. If the ores found already
	 * spread wider than that along an axis, the cluster is bigger than expected and one more block
	 * in that direction is allowed.
	 */
	private Set<BlockPos> clusterCandidates(Set<BlockPos> cluster) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : cluster) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		int ex = boxEdge(maxX - minX + 1), ey = boxEdge(maxY - minY + 1), ez = boxEdge(maxZ - minZ + 1);
		// The union of all boxes of that size containing the ores found so far.
		Set<BlockPos> candidates = new HashSet<>();
		for (int x = maxX - ex + 1; x <= minX + ex - 1; x++) {
			for (int y = maxY - ey + 1; y <= minY + ey - 1; y++) {
				for (int z = maxZ - ez + 1; z <= minZ + ez - 1; z++) {
					BlockPos p = new BlockPos(x, y, z);
					if (!cluster.contains(p)) {
						candidates.add(p);
					}
				}
			}
		}
		return candidates;
	}

	private int boxEdge(int span) {
		int size = Math.max(1, cfg.clusterSize);
		return span > size ? span + 1 : size;
	}

	private boolean inRange(BlockPos pos, BlockPos origin) {
		return Math.abs(pos.getX() - origin.getX()) <= cfg.veinRadius
				&& Math.abs(pos.getZ() - origin.getZ()) <= cfg.veinRadius
				&& Math.abs(pos.getY() - origin.getY()) <= 6;
	}

	/** Don't dig holes into the floor of the tunnel we walk on. */
	private static boolean isTunnelFloor(BlockPos pos, BlockPos origin) {
		return pos.getY() == origin.getY() - 1 && passable(pos.above());
	}

	/**
	 * Gets the player next to the ore and makes it safe to mine. If the ore touches lava, the lava
	 * is filled in first (if enabled and possible); otherwise the ore is skipped.
	 */
	private boolean prepareOre(BlockPos ore, BlockPos origin) {
		boolean touchesLava = !WorldChecks.lavaNeighbors(ore).isEmpty();
		// Gravel above the ore that touches lava is fine: when the ore is mined the gravel just
		// slides down into the hole and keeps blocking the lava.
		String danger = touchesLava ? "lava next to " + fmt(ore) : WorldChecks.hazard(ore);
		if (danger != null && danger.startsWith("lava behind falling")) {
			danger = null;
		}
		if (danger != null && !(touchesLava && cfg.isolateLava && WorldChecks.canMine(ore))) {
			msg("§cSkipping ore at " + fmt(ore) + " (" + danger + ").");
			return false;
		}
		phase = "going to ore at " + fmt(ore);
		if (!reach(ore, origin)) {
			msg("Couldn't safely reach the ore at " + fmt(ore) + ", skipping it.");
			return false;
		}
		if (!WorldChecks.lavaNeighbors(ore).isEmpty()) {
			if (!fillLavaAround(ore, origin)) {
				msg("§cSkipping ore at " + fmt(ore) + ": it touches lava that couldn't be filled in"
						+ " (need gravel, sand, cobblestone or dirt).");
				return false;
			}
			msg("Filled in the lava next to the ore at " + fmt(ore) + ".");
		}
		return true;
	}

	/**
	 * Fills every lava block touching {@code ore}. Gravel/sand is used first: dropped into a lava
	 * pool it sinks to the bottom and builds a pillar up, so the pool fills from below until the
	 * lava next to the ore is gone. Cobblestone/dirt is used when there is no gravel or sand.
	 */
	private boolean fillLavaAround(BlockPos ore, BlockPos origin) {
		phase = "filling lava with gravel";
		for (int round = 0; round < 64; round++) {
			List<Direction> sides = WorldChecks.lavaNeighbors(ore);
			if (sides.isEmpty()) {
				// Give flowing lava a moment to show whether it comes back.
				for (int i = 0; i < 10; i++) {
					tick();
				}
				if (WorldChecks.lavaNeighbors(ore).isEmpty()) {
					return true;
				}
				continue;
			}
			Direction side = sides.get(0);
			BlockPos lava = ore.relative(side);
			if (!placeBlockInto(lava, ore, side, origin)) {
				return false;
			}
			waitForFallingBlocks(lava);
		}
		return WorldChecks.lavaNeighbors(ore).isEmpty();
	}

	/** Places gravel/sand (or filler) into {@code target} by clicking the {@code face} of {@code against}. */
	private boolean placeBlockInto(BlockPos target, BlockPos against, Direction face, BlockPos origin) {
		Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		if (player().getEyePosition().distanceTo(hit) > 4.0) {
			// Dig up to the lava pool until it is within reach.
			List<BlockPos> path = PathFinder.find(feet(),
					c -> Vec3.atBottomCenterOf(c).add(0, 1.62, 0).distanceTo(hit) <= 4.0,
					target, origin, cfg.veinRadius + 2, 8);
			if (path == null || !executePath(path)) {
				return false;
			}
		}
		boolean gravel = placeFiller(against, face, true);
		if (!gravel && !placeFiller(against, face, false)) {
			return false;
		}
		if (gravel) {
			placedGravel.add(target.immutable());
		}
		return true;
	}

	private void waitForFallingBlocks(BlockPos near) {
		AABB box = new AABB(near).inflate(1, 24, 1);
		for (int i = 0; i < 40; i++) {
			tick();
			if (WorldChecks.level().getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty()) {
				return;
			}
		}
	}

	/** Places a block into an open hole, attached to any solid block next to it. */
	private void sealHole(BlockPos hole) {
		for (Direction d : Direction.values()) {
			BlockPos against = hole.relative(d);
			if (!passable(against) && !isLava(against)) {
				if (!placeFiller(against, d.getOpposite(), false)) {
					placeFiller(against, d.getOpposite(), true);
				}
				if (!passable(hole)) {
					return;
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Getting gravel back (torch trick)
	// ------------------------------------------------------------------------------------------

	private static boolean isGravelLike(BlockPos pos) {
		BlockState s = WorldChecks.level().getBlockState(pos);
		return s.is(Blocks.GRAVEL) || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND);
	}

	/**
	 * Takes back the gravel/sand pillars built in lava. A pillar is only taken if no block of it
	 * touches lava, so removing it can't let lava flow. The bottom block is mined and a torch is
	 * placed in its spot straight away: the rest of the pillar falls onto the torch and pops off
	 * as items. Then the torch is picked up again.
	 */
	private void recoverGravel(BlockPos origin) {
		if (placedGravel.isEmpty()) {
			return;
		}
		List<BlockPos> placed = new ArrayList<>(placedGravel);
		placedGravel.clear();
		if (!cfg.collectGravel) {
			return;
		}
		Set<BlockPos> bottoms = new LinkedHashSet<>();
		for (BlockPos p : placed) {
			// The gravel sank through the lava: look down for where the pillar ended up.
			BlockPos b = p;
			for (int k = 0; k < 32 && !isGravelLike(b) && (isLava(b) || passable(b)); k++) {
				b = b.below();
			}
			if (!isGravelLike(b)) {
				continue;
			}
			while (isGravelLike(b.below())) {
				b = b.below();
			}
			bottoms.add(b);
		}
		int left = 0;
		for (BlockPos bottom : bottoms) {
			checkpoint(false);
			if (!isGravelLike(bottom)) {
				continue;
			}
			if (hazard(bottom) != null || !isSolidFloor(bottom.below())) {
				left++; // taking it would let lava flow (or there's nothing to put a torch on)
				continue;
			}
			phase = "collecting gravel";
			List<BlockPos> path = PathFinder.find(feet(), c -> {
				int dy = bottom.getY() - c.getY();
				int flat = Math.abs(bottom.getX() - c.getX()) + Math.abs(bottom.getZ() - c.getZ());
				return (dy == 0 || dy == 1) && flat == 1;
			}, bottom, origin, cfg.veinRadius + 2, 8);
			if (path == null || !executePath(path) || !collectPillar(bottom)) {
				left++;
			}
		}
		if (left > 0) {
			msg("Left " + left + " gravel pillar(s) in the lava pool; taking them would let the lava flow.");
		}
	}

	private boolean collectPillar(BlockPos bottom) {
		int torch = ensureInHotbar(s -> s.is(Items.TORCH));
		if (torch < 0) {
			msg("§eNo torches to collect the gravel with.");
			return false;
		}
		for (int i = 0; i < 300; i++) {
			if (isGravelLike(bottom)) {
				if (hazard(bottom) != null || !mineBlock(bottom)) {
					return false;
				}
				placeTorchOn(bottom, torch); // no tick in between: the torch must be there first
			} else if (WorldChecks.level().getBlockState(bottom).isAir() && isGravelLike(bottom.above())) {
				placeTorchOn(bottom, torch);
			}
			tick();
			if (!isGravelLike(bottom) && !isGravelLike(bottom.above())
					&& WorldChecks.level().getEntitiesOfClass(FallingBlockEntity.class, new AABB(bottom).inflate(1, 24, 1)).isEmpty()) {
				break;
			}
		}
		AABB area = new AABB(bottom).inflate(1.5);
		for (int i = 0; i < 40; i++) {
			tick();
			if (WorldChecks.level().getEntitiesOfClass(ItemEntity.class, area).isEmpty()) {
				break;
			}
		}
		if (WorldChecks.level().getBlockState(bottom).is(Blocks.TORCH)) {
			Direction face = faceTowardsEyes(bottom);
			lookAt(Vec3.atCenterOf(bottom));
			MinerRunner.onMain(() -> gameMode().startDestroyBlock(bottom, face));
			for (int i = 0; i < 20; i++) {
				tick();
				if (WorldChecks.level().getEntitiesOfClass(ItemEntity.class, area).isEmpty()) {
					break;
				}
			}
		}
		return true;
	}

	private void placeTorchOn(BlockPos cell, int torchSlot) {
		BlockPos floor = cell.below();
		player().getInventory().setSelectedSlot(torchSlot);
		Vec3 hit = Vec3.atCenterOf(floor).add(0, 0.5, 0);
		lookAt(hit);
		MinerRunner.onMain(() -> gameMode().useItemOn(player(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, floor, false)));
		swingHand();
	}

	/** True if standing with feet in {@code stand} lets the player mine {@code ore} and catch its drop. */
	private static boolean canMineFrom(BlockPos stand, BlockPos ore) {
		if (ore.equals(stand.below())) {
			// Mining the block we stand on: we drop into its space, so it must have a floor.
			return isSolidFloor(ore.below()) && hazard(ore) == null;
		}
		if (ore.equals(stand.above(2))) {
			return true;
		}
		int dy = ore.getY() - stand.getY();
		int flat = Math.abs(ore.getX() - stand.getX()) + Math.abs(ore.getZ() - stand.getZ());
		return (dy == 0 || dy == 1) && flat == 1;
	}

	/** Moves (digging if needed) to a spot where {@code ore} can be mined and its drop caught. */
	private boolean reach(BlockPos ore, BlockPos origin) {
		for (int attempt = 0; attempt < 3; attempt++) {
			BlockPos me = feet();
			if (canMineFrom(me, ore)) {
				return true;
			}
			List<BlockPos> path = PathFinder.find(me, c -> canMineFrom(c, ore), ore, origin,
					cfg.veinRadius + 2, 8);
			if (path == null) {
				return false;
			}
			executePath(path);
		}
		return canMineFrom(feet(), ore);
	}

	/** Waits for the drops of a mined ore to be picked up, walking to any that land out of reach. */
	private void collectDrops(BlockPos ore, BlockPos origin) {
		phase = "picking up drops";
		AABB area = new AABB(ore).inflate(3);
		for (int i = 0; i < 30; i++) {
			tick();
			if (drops(area).isEmpty()) {
				return;
			}
		}
		Set<Integer> tried = new HashSet<>();
		for (int round = 0; round < 8; round++) {
			List<ItemEntity> left = drops(area);
			left.removeIf(e -> tried.contains(e.getId()));
			if (left.isEmpty()) {
				return;
			}
			ItemEntity item = left.get(0);
			tried.add(item.getId());
			BlockPos at = item.blockPosition();
			List<BlockPos> path = PathFinder.find(feet(), c -> {
				int dy = at.getY() - c.getY();
				int flat = Math.abs(at.getX() - c.getX()) + Math.abs(at.getZ() - c.getZ());
				return (dy == 0 || dy == 1) && flat <= 1;
			}, at, origin, cfg.veinRadius + 3, 8);
			if (path == null) {
				msg("Couldn't reach a dropped item at " + fmt(at) + ".");
				continue;
			}
			executePath(path);
			for (int i = 0; i < 15 && item.isAlive(); i++) {
				tick();
			}
		}
	}

	private List<ItemEntity> drops(AABB area) {
		List<ItemEntity> list = new ArrayList<>(WorldChecks.level().getEntitiesOfClass(ItemEntity.class, area,
				e -> e.isAlive() && !JUNK.contains(e.getItem().getItem())
						&& !(FALLING_FILLER.contains(e.getItem().getItem()) && gravelCount() >= 64)));
		list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player())));
		return list;
	}

	private List<BlockPos> takeCleared() {
		List<BlockPos> copy = new ArrayList<>(new LinkedHashSet<>(cleared));
		cleared.clear();
		return copy;
	}

	// ------------------------------------------------------------------------------------------
	// Moving and digging
	// ------------------------------------------------------------------------------------------

	private void returnTo(BlockPos home) {
		for (int attempt = 0; attempt < 4; attempt++) {
			if (feet().equals(home)) {
				moveTo(Vec3.atBottomCenterOf(home));
				return;
			}
			List<BlockPos> path = PathFinder.find(feet(), home::equals, home, home, cfg.veinRadius + 6, 10);
			if (path == null) {
				break;
			}
			executePath(path);
		}
		if (!feet().equals(home)) {
			throw new StopException("Couldn't find the way back to the tunnel at " + fmt(home) + ". Stopped.");
		}
	}

	private boolean executePath(List<BlockPos> path) {
		for (int k = 0; k < path.size(); k++) {
			BlockPos next = path.get(k);
			BlockPos cur = feet();
			int flat = Math.abs(next.getX() - cur.getX()) + Math.abs(next.getZ() - cur.getZ());
			if (flat != 1 || Math.abs(next.getY() - cur.getY()) > 1) {
				return false;
			}
			// Only stop exactly (and sneak) at the end of the path; walk smoothly through the rest.
			if (!step(cur, next, false, k == path.size() - 1)) {
				releaseMovement();
				return false;
			}
		}
		return true;
	}

	/**
	 * Digs out the space needed to move from {@code cur} into the neighbouring cell {@code next} and
	 * walks there. Refuses (returning false, reason in {@link #lastFailure}) if that would open a
	 * block next to lava. If {@code allowBridge} is set, a missing floor is filled in with
	 * cobblestone or similar blocks from the inventory.
	 */
	private boolean step(BlockPos cur, BlockPos next, boolean allowBridge) {
		return step(cur, next, allowBridge, true);
	}

	/** @param exact stop exactly in the middle of {@code next}; otherwise keep walking through it */
	private boolean step(BlockPos cur, BlockPos next, boolean allowBridge, boolean exact) {
		List<BlockPos> req = WorldChecks.required(cur, next);
		for (BlockPos b : req) {
			String danger = hazard(b);
			if (danger != null) {
				lastFailure = danger;
				return false;
			}
		}
		BlockPos floor = next.below();
		boolean needBridge = false;
		if (!isSolidFloor(floor)) {
			if (isLava(floor) || hazardAround(floor)) {
				lastFailure = "lava below " + fmt(next);
				return false;
			}
			if (!allowBridge || next.getY() != cur.getY() || !(passable(floor) || WorldChecks.level().getBlockState(floor).canBeReplaced())) {
				lastFailure = "no floor to stand on at " + fmt(next);
				return false;
			}
			needBridge = true;
		}
		if (!clearBlocks(req)) {
			return false;
		}
		if (needBridge && !isSolidFloor(floor)) {
			Direction dir = next.getX() > cur.getX() ? Direction.EAST : next.getX() < cur.getX() ? Direction.WEST
					: next.getZ() > cur.getZ() ? Direction.SOUTH : Direction.NORTH;
			if (!placeFiller(cur.below(), dir, false) || !isSolidFloor(floor)) {
				lastFailure = "gap in the floor at " + fmt(floor) + " and no cobblestone/dirt to fill it";
				return false;
			}
		}
		if (!moveTo(Vec3.atBottomCenterOf(next), exact) || !feet().equals(next)) {
			lastFailure = "couldn't walk to " + fmt(next);
			return false;
		}
		return true;
	}

	private static boolean hazardAround(BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (isLava(pos.relative(d))) {
				return true;
			}
		}
		return false;
	}

	/** Mines the given blocks until all are open, re-mining gravel/sand that falls in. */
	private boolean clearBlocks(List<BlockPos> blocks) {
		for (int attempt = 0; attempt < 48; attempt++) {
			BlockPos todo = null;
			for (BlockPos b : blocks) {
				if (!passable(b)) {
					todo = b;
					break;
				}
			}
			if (todo == null) {
				if (!mightFall(blocks)) {
					return true; // nothing above can fall in: no need to wait
				}
				// Give falling blocks a moment to land, then check again.
				boolean settled = true;
				for (int i = 0; i < 6; i++) {
					tick();
					if (!fallingNear(blocks)) {
						break;
					}
					settled = false;
				}
				boolean allOpen = blocks.stream().allMatch(WorldChecks::passable);
				if (allOpen && (settled || !fallingNear(blocks))) {
					return true;
				}
				continue;
			}
			String danger = hazard(todo);
			if (danger != null) {
				lastFailure = danger;
				return false;
			}
			if (!mineBlock(todo)) {
				return false;
			}
		}
		lastFailure = "blocks kept falling in";
		return false;
	}

	/** True if gravel/sand sits right above one of the blocks, or something is already falling nearby. */
	private boolean mightFall(List<BlockPos> blocks) {
		for (BlockPos b : blocks) {
			if (WorldChecks.level().getBlockState(b.above()).getBlock() instanceof FallingBlock) {
				return true;
			}
		}
		return fallingNear(blocks);
	}

	private boolean fallingNear(List<BlockPos> blocks) {
		AABB box = new AABB(blocks.get(0));
		for (BlockPos b : blocks) {
			box = box.minmax(new AABB(b));
		}
		return !WorldChecks.level().getEntitiesOfClass(FallingBlockEntity.class, box.inflate(1, 4, 1)).isEmpty();
	}

	/** Breaks a single block within reach. */
	private boolean mineBlock(BlockPos pos) {
		MultiPlayerGameMode gm = gameMode();
		int waited = 0;
		while (!passable(pos)) {
			if (isLava(pos)) {
				lastFailure = "lava";
				return false;
			}
			if (waited++ > 600) {
				MinerRunner.onMain(() -> gm.stopDestroyBlock());
				lastFailure = "block at " + fmt(pos) + " took too long to mine";
				return false;
			}
			BlockState state = WorldChecks.level().getBlockState(pos);
			Direction face = faceTowardsEyes(pos);
			lookAt(Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5));
			selectBestTool(state);
			MinerRunner.onMain(() -> gm.continueDestroyBlock(pos, face));
			swingHand();
			tick();
		}
		cleared.add(pos.immutable());
		return true;
	}

	private Direction faceTowardsEyes(BlockPos pos) {
		Vec3 d = player().getEyePosition().subtract(Vec3.atCenterOf(pos));
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		if (ay >= ax && ay >= az) {
			return d.y > 0 ? Direction.UP : Direction.DOWN;
		}
		if (ax >= az) {
			return d.x > 0 ? Direction.EAST : Direction.WEST;
		}
		return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** Walks to an exact position (bottom-center of a block), jumping up single steps. */
	private boolean moveTo(Vec3 target) {
		return moveTo(target, true);
	}

	/**
	 * Walks towards a position (bottom-center of a block), jumping up single steps. With
	 * {@code exact} it slows down (sneaking) and stops right on the spot; without it, it returns as
	 * soon as the player is inside the block and keeps walking, for smooth movement along a path.
	 */
	private boolean moveTo(Vec3 target, boolean exact) {
		double best = Double.MAX_VALUE;
		int stalled = 0;
		boolean keepWalking = false;
		try {
			while (true) {
				LocalPlayer p = player();
				double dx = target.x - p.getX();
				double dz = target.z - p.getZ();
				double dy = target.y - p.getY();
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (exact ? dist < 0.15 && Math.abs(dy) < 0.4 && p.onGround()
						: dist < 0.3 && Math.abs(dy) < 0.4 && (p.onGround() || dy > -0.1)) {
					keepWalking = !exact;
					return true;
				}
				double progress = dist + Math.abs(dy);
				if (progress < best - 0.01) {
					best = progress;
					stalled = 0;
				} else if (++stalled > 80) {
					return false;
				}
				if (dist > 0.02) {
					p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
				}
				p.setXRot(20f);
				p.setSprinting(false);
				setKey(mc.options.keySprint, false);
				setKey(mc.options.keyShift, false);
				boolean braking = exact && dist < 0.6 && Math.abs(dy) < 0.4 && p.onGround();
				if (braking) {
					// Close to the final spot: let go of the key and ease in, so it stops right on it.
					setKey(mc.options.keyUp, false);
					double speed = Math.min(0.12, dist * 0.5);
					Vec3 v = p.getDeltaMovement();
					p.setDeltaMovement(dx / Math.max(dist, 1e-4) * speed, v.y, dz / Math.max(dist, 1e-4) * speed);
				} else {
					setKey(mc.options.keyUp, dist >= 0.12);
				}
				setKey(mc.options.keyJump, dy > 0.5 && p.onGround());
				tick();
			}
		} finally {
			if (!keepWalking) {
				releaseMovement();
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Items: tools, torches, filler blocks, food, junk
	// ------------------------------------------------------------------------------------------

	private boolean usable(ItemStack s) {
		return !s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > cfg.keepToolDurability;
	}

	private void selectBestTool(BlockState state) {
		Inventory inv = player().getInventory();
		int best = -1;
		float bestScore = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (!usable(s)) {
				continue;
			}
			float score = s.getDestroySpeed(state);
			if (state.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(state)) {
				score += 1000;
			}
			if (s.isDamageableItem() && score <= 1) {
				score = 0.5f; // don't wear down tools that don't help
			}
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		if (best >= 0) {
			inv.setSelectedSlot(best);
		}
	}

	private void checkTools() {
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
		Inventory inv = player().getInventory();
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (usable(s) && s.isCorrectToolForDrops(stone) && s.isCorrectToolForDrops(deepslate)) {
				for (Block b : targets) {
					if (b.defaultBlockState().requiresCorrectToolForDrops() && !hasToolFor(b.defaultBlockState())) {
						throw new StopException("No pickaxe in your hotbar can mine " + b.getName().getString()
								+ ". Put a better pickaxe in your hotbar.");
					}
				}
				return;
			}
		}
		throw new StopException("Put a pickaxe (with more than " + cfg.keepToolDurability
				+ " durability left) in your hotbar.");
	}

	private boolean hasToolFor(BlockState state) {
		Inventory inv = player().getInventory();
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (usable(s) && s.isCorrectToolForDrops(state)) {
				return true;
			}
		}
		return false;
	}

	/** Makes sure an item matching {@code what} is in the hotbar; returns its slot or -1. */
	private int ensureInHotbar(Predicate<ItemStack> what) {
		Inventory inv = player().getInventory();
		for (int i = 0; i < 9; i++) {
			if (what.test(inv.getItem(i))) {
				return i;
			}
		}
		for (int i = 9; i < 36; i++) {
			if (!what.test(inv.getItem(i))) {
				continue;
			}
			int target = -1;
			for (int j = 0; j < 9 && target < 0; j++) {
				if (inv.getItem(j).isEmpty()) {
					target = j;
				}
			}
			for (int j = 8; j >= 0 && target < 0; j--) {
				ItemStack s = inv.getItem(j);
				if (!s.isDamageableItem() && !s.has(DataComponents.FOOD) && !s.is(Items.TORCH)) {
					target = j;
				}
			}
			if (target < 0) {
				return -1;
			}
			// Inventory slots 9-35 have the same index in the player's inventory menu.
			final int from = i, to = target;
			MinerRunner.onMain(() -> gameMode().handleContainerInput(player().inventoryMenu.containerId, from, to, ContainerInput.SWAP, player()));
			tick();
			return what.test(inv.getItem(target)) ? target : -1;
		}
		return -1;
	}

	/** Places a torch on the floor of the block the player is standing in. */
	private void placeTorchHere() {
		BlockPos cell = feet();
		BlockPos floor = cell.below();
		if (!isSolidFloor(floor) || !WorldChecks.level().getBlockState(cell).isAir()) {
			return;
		}
		int slot = ensureInHotbar(s -> s.is(Items.TORCH));
		if (slot < 0) {
			if (!warnedNoTorches) {
				msg("§eNo torches in your inventory - continuing without placing torches.");
				warnedNoTorches = true;
			}
			return;
		}
		warnedNoTorches = false;
		player().getInventory().setSelectedSlot(slot);
		Vec3 hit = Vec3.atCenterOf(floor).add(0, 0.5, 0);
		lookAt(hit);
		MinerRunner.onMain(() -> gameMode().useItemOn(player(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, floor, false)));
		swingHand();
		tick();
	}

	/** Places a filler block (cobblestone, dirt, ...) against the side of {@code against} facing {@code dir}. */
	private boolean placeFiller(BlockPos against, Direction dir, boolean gravelOrSand) {
		int slot = ensureInHotbar(s -> (gravelOrSand ? FALLING_FILLER : FILLER).contains(s.getItem()));
		if (slot < 0) {
			return false;
		}
		player().getInventory().setSelectedSlot(slot);
		Vec3 hit = Vec3.atCenterOf(against).add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
		lookAt(hit);
		tick();
		MinerRunner.onMain(() -> gameMode().useItemOn(player(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, dir, against, false)));
		swingHand();
		tick();
		tick();
		setKey(mc.options.keyShift, false);
		return true;
	}

	private boolean isGoodFood(ItemStack s) {
		return s.has(DataComponents.FOOD) && !BAD_FOOD.contains(s.getItem());
	}

	private void eatIfHungry() {
		LocalPlayer p = player();
		if (p.getFoodData().getFoodLevel() > cfg.eatBelowFood) {
			return;
		}
		int slot = ensureInHotbar(this::isGoodFood);
		if (slot < 0) {
			if (p.getFoodData().getFoodLevel() <= 6) {
				throw new StopException("Too hungry and no food in the inventory. Stopped.");
			}
			return;
		}
		phase = "eating";
		p.getInventory().setSelectedSlot(slot);
		p.setXRot(-90f);
		MinerRunner.onMain(() -> gameMode().useItem(p, InteractionHand.MAIN_HAND));
		setKey(mc.options.keyUse, true);
		try {
			for (int i = 0; i < 60; i++) {
				tick();
				if (!player().isUsingItem()) {
					break;
				}
			}
		} finally {
			setKey(mc.options.keyUse, false);
		}
	}

	/** How many of each valuable item is in the inventory (ignores stone, gravel, torches, tables). */
	private Map<Item, Integer> countResources() {
		Map<Item, Integer> counts = new HashMap<>();
		Inventory inv = player().getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			Item item = s.getItem();
			if (s.isEmpty() || JUNK.contains(item) || FALLING_FILLER.contains(item) || item == Items.TORCH
					|| item == Items.CRAFTING_TABLE) {
				continue;
			}
			counts.merge(item, s.getCount(), Integer::sum);
		}
		return counts;
	}

	private static int gained(Map<Item, Integer> before, Map<Item, Integer> after) {
		int n = 0;
		for (Map.Entry<Item, Integer> e : after.entrySet()) {
			n += Math.max(0, e.getValue() - before.getOrDefault(e.getKey(), 0));
		}
		return n;
	}

	private int freeSlots() {
		Inventory inv = player().getInventory();
		int free = 0;
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	private static int menuSlot(int invSlot) {
		// Player inventory menu: hotbar 0-8 is at 36-44, the main inventory 9-35 keeps its index.
		return invSlot < 9 ? 36 + invSlot : invSlot;
	}

	private void faceBackwards() {
		LocalPlayer p = player();
		p.setYRot(forward.getOpposite().toYRot());
		p.setXRot(0f);
		tick();
	}

	private void throwStack(int invSlot) {
		LocalPlayer p = player();
		MinerRunner.onMain(() -> gameMode().handleContainerInput(p.inventoryMenu.containerId, menuSlot(invSlot), 1, ContainerInput.THROW, p));
	}

	/** Total gravel/sand carried. */
	private int gravelCount() {
		Inventory inv = player().getInventory();
		int n = 0;
		for (int i = 0; i < 36; i++) {
			if (FALLING_FILLER.contains(inv.getItem(i).getItem())) {
				n += inv.getItem(i).getCount();
			}
		}
		return n;
	}

	/**
	 * Keeps at most one stack of gravel (or sand). Smaller stacks are merged into the biggest one
	 * and whatever doesn't fit is thrown behind the player.
	 */
	private void limitGravel() {
		LocalPlayer p = player();
		Inventory inv = p.getInventory();
		int keep = -1;
		for (int i = 0; i < 36; i++) {
			if (FALLING_FILLER.contains(inv.getItem(i).getItem())
					&& (keep < 0 || inv.getItem(i).getCount() > inv.getItem(keep).getCount())) {
				keep = i;
			}
		}
		if (keep < 0) {
			return;
		}
		boolean turned = false;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (i == keep || !FALLING_FILLER.contains(s.getItem())) {
				continue;
			}
			if (!turned) {
				faceBackwards();
				turned = true;
			}
			int id = p.inventoryMenu.containerId;
			ItemStack kept = inv.getItem(keep);
			if (s.is(kept.getItem()) && kept.getCount() < kept.getMaxStackSize()) {
				final int from = i, into = keep;
				MinerRunner.onMain(() -> gameMode().handleContainerInput(id, menuSlot(from), 0, ContainerInput.PICKUP, p));
				MinerRunner.onMain(() -> gameMode().handleContainerInput(id, menuSlot(into), 0, ContainerInput.PICKUP, p));
				if (!p.inventoryMenu.getCarried().isEmpty()) {
					MinerRunner.onMain(() -> gameMode().handleContainerInput(id, menuSlot(from), 0, ContainerInput.PICKUP, p));
				}
			}
			if (FALLING_FILLER.contains(inv.getItem(i).getItem())) {
				throwStack(i);
			}
		}
		if (turned) {
			tick();
		}
	}

	/**
	 * When the inventory is nearly full, throws the throwaway blocks (cobblestone, deepslate, dirt,
	 * ...) behind the player, keeping one stack to bridge gaps and block off lava with.
	 */
	private void dropJunk() {
		if (!cfg.dropJunk || freeSlots() > 3) {
			return;
		}
		faceBackwards();
		Inventory inv = player().getInventory();
		boolean keptFiller = false;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!JUNK.contains(s.getItem())) {
				continue;
			}
			if (!keptFiller && FILLER.contains(s.getItem())) {
				keptFiller = true;
				continue;
			}
			throwStack(i);
		}
		tick();
	}

	// ------------------------------------------------------------------------------------------
	// Crafting full stacks into blocks
	// ------------------------------------------------------------------------------------------

	/** Items where nine of them craft into one block (the client can't look up recipes itself). */
	private static final Set<Item> COMPRESSIBLE = Set.of(Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT,
			Items.RAW_IRON, Items.GOLD_INGOT, Items.RAW_GOLD, Items.COPPER_INGOT, Items.RAW_COPPER, Items.REDSTONE,
			Items.LAPIS_LAZULI, Items.COAL, Items.IRON_NUGGET, Items.GOLD_NUGGET, Items.NETHERITE_INGOT);

	private boolean canCompress(Item item) {
		return COMPRESSIBLE.contains(item);
	}

	/** Items that currently have a completely full stack and can be crafted into blocks. */
	private Set<Item> fullCompressibleStacks() {
		Set<Item> full = new LinkedHashSet<>();
		Inventory inv = player().getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && s.getMaxStackSize() >= 9 && s.getCount() >= s.getMaxStackSize()
					&& canCompress(s.getItem())) {
				full.add(s.getItem());
			}
		}
		return full;
	}

	private boolean hasItem(Item item) {
		Inventory inv = player().getInventory();
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).is(item)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * When a stack of e.g. diamonds is full: place a crafting table next to the player, craft every
	 * stack of that item into blocks, then mine the table and pick it back up.
	 */
	private void compressFullStacks() {
		if (!cfg.compressFullStacks) {
			return;
		}
		Set<Item> items = fullCompressibleStacks();
		if (items.isEmpty()) {
			return;
		}
		if (!hasItem(Items.CRAFTING_TABLE)) {
			if (!warnedNoTable) {
				msg("§eA stack is full but there's no crafting table in your inventory, so it can't be crafted into blocks.");
				warnedNoTable = true;
			}
			return;
		}
		warnedNoTable = false;
		phase = "crafting full stacks into blocks";
		BlockPos table = placeCraftingTable();
		if (table == null) {
			return;
		}
		try {
			if (!openCraftingTable(table)) {
				msg("Couldn't open the crafting table.");
				return;
			}
			for (Item item : items) {
				for (int tries = 0; tries < 36; tries++) {
					int slot = findStack(item);
					if (slot < 0 || !(player().containerMenu instanceof CraftingMenu)) {
						break;
					}
					craftNine(slot);
				}
			}
		} finally {
			LocalPlayer p = player();
			if (p.containerMenu != p.inventoryMenu) {
				MinerRunner.onMain(() -> p.closeContainer());
			}
			tick();
			phase = "picking the crafting table back up";
			if (WorldChecks.level().getBlockState(table).is(Blocks.CRAFTING_TABLE) && mineBlock(table)) {
				AABB area = new AABB(table).inflate(2);
				for (int i = 0; i < 40; i++) {
					tick();
					if (WorldChecks.level().getEntitiesOfClass(ItemEntity.class, area,
							e -> e.getItem().is(Items.CRAFTING_TABLE)).isEmpty()) {
						break;
					}
				}
			}
		}
	}

	/** Inventory index (0-35) of a stack of {@code item} with at least 9 items, or -1. */
	private int findStack(Item item) {
		Inventory inv = player().getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item) && s.getCount() >= 9) {
				return i;
			}
		}
		return -1;
	}

	/** Spreads one stack evenly over the 3x3 grid and shift-clicks the result. */
	private void craftNine(int invSlot) {
		LocalPlayer p = player();
		MultiPlayerGameMode gm = gameMode();
		AbstractContainerMenu menu = p.containerMenu;
		int id = menu.containerId;
		// In the crafting table menu: 0 = result, 1-9 = grid, 10-36 = inventory, 37-45 = hotbar.
		int slot = invSlot < 9 ? 37 + invSlot : invSlot + 1;
		MinerRunner.onMain(() -> gm.handleContainerInput(id, slot, 0, ContainerInput.PICKUP, p));
		MinerRunner.onMain(() -> gm.handleContainerInput(id, -999, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, p));
		for (int grid = 1; grid <= 9; grid++) {
			final int g = grid;
			MinerRunner.onMain(() -> gm.handleContainerInput(id, g, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, p));
		}
		MinerRunner.onMain(() -> gm.handleContainerInput(id, -999, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, p));
		if (!menu.getCarried().isEmpty()) {
			MinerRunner.onMain(() -> gm.handleContainerInput(id, slot, 0, ContainerInput.PICKUP, p)); // put the leftovers back
		}
		tick();
		tick();
		MinerRunner.onMain(() -> gm.handleContainerInput(id, 0, 0, ContainerInput.QUICK_MOVE, p));
		tick();
		// Anything left in the grid goes back to the inventory.
		for (int grid = 1; grid <= 9; grid++) {
			if (!menu.getSlot(grid).getItem().isEmpty()) {
				final int g = grid;
				MinerRunner.onMain(() -> gm.handleContainerInput(id, g, 0, ContainerInput.QUICK_MOVE, p));
			}
		}
		tick();
	}

	/** Places a crafting table in an open spot next to the player; returns where, or null. */
	private BlockPos placeCraftingTable() {
		BlockPos cur = feet();
		Direction[] sides = {forward.getOpposite(), forward.getCounterClockWise(), forward.getClockWise(), forward};
		for (Direction d : sides) {
			BlockPos cell = cur.relative(d);
			BlockPos floor = cell.below();
			if (!WorldChecks.level().getBlockState(cell).isAir() || !isSolidFloor(floor) || hazard(cell) != null) {
				continue;
			}
			int slot = ensureInHotbar(st -> st.is(Items.CRAFTING_TABLE));
			if (slot < 0) {
				return null;
			}
			player().getInventory().setSelectedSlot(slot);
			Vec3 hit = Vec3.atCenterOf(floor).add(0, 0.5, 0);
			lookAt(hit);
			tick();
			MinerRunner.onMain(() -> gameMode().useItemOn(player(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, floor, false)));
			swingHand();
			tick();
			setKey(mc.options.keyShift, false);
			tick();
			if (WorldChecks.level().getBlockState(cell).is(Blocks.CRAFTING_TABLE)) {
				return cell;
			}
		}
		msg("No room to place a crafting table here.");
		return null;
	}

	private boolean openCraftingTable(BlockPos table) {
		setKey(mc.options.keyShift, false);
		tick();
		Vec3 hit = Vec3.atCenterOf(table).add(0, 0.5, 0);
		lookAt(hit);
		MinerRunner.onMain(() -> gameMode().useItemOn(player(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, table, false)));
		for (int i = 0; i < 20; i++) {
			tick();
			if (player().containerMenu instanceof CraftingMenu) {
				return true;
			}
		}
		return false;
	}

	/** Housekeeping between steps: tools, food and inventory space. */
	private void checkpoint(boolean inMainTunnel) {
		checkGoal();
		checkTools();
		eatIfHungry();
		if (inMainTunnel) {
			compressFullStacks();
			limitGravel();
			dropJunk();
			if (freeSlots() == 0) {
				throw new StopException("Inventory is full. Stopped.");
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Low level
	// ------------------------------------------------------------------------------------------

	private LocalPlayer player() {
		LocalPlayer p = mc.player;
		if (p == null) {
			throw new StopException("Stopped: left the world.");
		}
		return p;
	}

	private MultiPlayerGameMode gameMode() {
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm == null) {
			throw new StopException("Stopped: left the world.");
		}
		return gm;
	}

	private BlockPos feet() {
		LocalPlayer p = player();
		return BlockPos.containing(p.getX(), p.getY() + 0.01, p.getZ());
	}

	private void lookAt(Vec3 target) {
		LocalPlayer p = player();
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		double flat = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90f, 90f));
	}

	private void swingHand() {
		MinerRunner.onMain(() -> player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false));
	}

	private static void setKey(KeyMapping key, boolean down) {
		key.setDown(down);
	}

	private void releaseMovement() {
		setKey(mc.options.keyUp, false);
		setKey(mc.options.keyShift, false);
		setKey(mc.options.keyJump, false);
		setKey(mc.options.keySprint, false);
	}

	/** Waits one game tick, then checks the player is still safe. */
	private void tick() {
		MinerRunner.yieldTick();
		LocalPlayer p = player();
		if (p.isInLava()) {
			throw new StopException("§cTouched lava! Stopped.");
		}
		if (p.getHealth() <= cfg.stopBelowHealth) {
			throw new StopException("§cHealth is low (" + Math.round(p.getHealth()) + "). Stopped.");
		}
		if (++ticks % 10 == 0) {
			updateHud();
		}
		if (goal != null && goal.type() == Goal.Type.TIME) {
			checkGoal();
		}
	}

	private void msg(String text) {
		MinerRunner.sendChat(text);
	}

	/** Runs on the miner thread after the job ends for any reason. */
	void cleanup() {
		releaseMovement();
		setKey(mc.options.keyUse, false);
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm != null && mc.player != null) {
			MinerRunner.onMain(() -> gm.stopDestroyBlock());
		}
	}
}
