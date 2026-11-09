package com.stripminer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.stripminer.WorldChecks.fmt;

/**
 * Chops down a 2x2 tree (e.g. a big spruce), or a whole group of 2x2 trees standing right next to
 * each other:
 * <ol>
 * <li>Finds every log of the same kind connected to the one the player looked at.</li>
 * <li>Picks the corner of the group nearest to the player and digs a staircase up along the
 * outside edge of the group until it stands on top of the corner tree. That height is the
 * working level.</li>
 * <li>Every tree sticking up above the working level is chopped down to it: the tallest tree next
 * to the current one first (mined from where it stands if the top is in reach, otherwise it digs a
 * staircase up), or the closest one if no neighbour sticks up.</li>
 * <li>When nothing sticks up any more, the working level is mined away layer by layer. That brings
 * shorter trees (e.g. in the middle) into reach and finally takes the whole group down.</li>
 * <li>Finally it picks up the dropped items.</li>
 * </ol>
 * Up in the trees it stops exactly on every block and never steps where there is no floor, so it
 * can't fall.
 */
final class TreeChopper {
	private static final int MAX_LOGS = 4000;

	private final StripMineJob job;
	private final BlockPos start;
	private Block logType;
	private final Set<BlockPos> logs = new HashSet<>();
	private int baseY;
	/** The working level: the height of the log tops the player walks on. */
	private int floorY;
	int logsMined;

	/** A 2x2 trunk, by its north-west column. */
	private record Square(int x, int z) {
		/** The 4 columns, clockwise seen from above (east, south, west, north steps). */
		List<int[]> cells() {
			return List.of(new int[] {x, z}, new int[] {x + 1, z}, new int[] {x + 1, z + 1}, new int[] {x, z + 1});
		}

		boolean contains(BlockPos p) {
			return (p.getX() == x || p.getX() == x + 1) && (p.getZ() == z || p.getZ() == z + 1);
		}
	}

	TreeChopper(StripMineJob job, BlockPos start) {
		this.job = job;
		this.start = start;
	}

	private static Level level() {
		return WorldChecks.level();
	}

	private boolean isLog(BlockPos p) {
		return level().getBlockState(p).is(logType);
	}

	void run() {
		job.careful = true;
		logType = level().getBlockState(start).getBlock();
		if (!level().getBlockState(start).is(BlockTags.LOGS)) {
			throw new StopException("Look at a log of the tree you want to chop and try again.");
		}
		scan();
		List<Square> squares = squares();
		if (squares.isEmpty()) {
			throw new StopException("That isn't a 2x2 tree. Tree mode only chops trees with a 2x2 trunk"
					+ " (like big spruces).");
		}
		int top = logs.stream().mapToInt(BlockPos::getY).max().orElse(baseY);
		job.msg("Found " + squares.size() + " tree" + (squares.size() == 1 ? "" : "s") + " (" + logs.size()
				+ " logs, " + (top - baseY + 1) + " blocks tall). Climbing up...");

		climbAtCorner(squares);
		floorY = job.feet().getY() - 1;
		workDown(squares);
		collectItems();
		job.msg("§aDone: chopped " + logsMined + " logs.");
	}

	// ------------------------------------------------------------------------------------------
	// Finding the trees
	// ------------------------------------------------------------------------------------------

	/** Every log of the same kind connected to the start log. */
	private void scan() {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		logs.add(start);
		while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
			BlockPos p = queue.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (Math.abs(n.getX() - start.getX()) <= 24 && Math.abs(n.getZ() - start.getZ()) <= 24
						&& !logs.contains(n) && isLog(n)) {
					logs.add(n);
					queue.add(n);
				}
			}
		}
		baseY = logs.stream().mapToInt(BlockPos::getY).min().orElse(start.getY());
	}

	/** The 2x2 trunks among the logs (columns that have logs at the bottom layer). */
	private List<Square> squares() {
		Set<Long> base = new HashSet<>();
		for (BlockPos p : logs) {
			if (p.getY() <= baseY + 1) {
				base.add(key(p.getX(), p.getZ()));
			}
		}
		List<Square> squares = new ArrayList<>();
		Set<Long> used = new HashSet<>();
		for (BlockPos p : logs) {
			int x = p.getX(), z = p.getZ();
			long a = key(x, z), b = key(x + 1, z), c = key(x, z + 1), d = key(x + 1, z + 1);
			if (base.contains(a) && base.contains(b) && base.contains(c) && base.contains(d)
					&& !used.contains(a) && !used.contains(b) && !used.contains(c) && !used.contains(d)) {
				squares.add(new Square(x, z));
				used.add(a);
				used.add(b);
				used.add(c);
				used.add(d);
			}
		}
		return squares;
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	private int top(Square sq) {
		int top = baseY;
		for (BlockPos p : logs) {
			if (sq.contains(p) && isLog(p)) {
				top = Math.max(top, p.getY());
			}
		}
		return top;
	}

	// ------------------------------------------------------------------------------------------
	// Climbing up at a corner
	// ------------------------------------------------------------------------------------------

	private Set<Long> columns() {
		Set<Long> cols = new HashSet<>();
		for (BlockPos p : logs) {
			cols.add(key(p.getX(), p.getZ()));
		}
		return cols;
	}

	private static boolean inColumns(Set<Long> cols, BlockPos p) {
		return cols.contains(key(p.getX(), p.getZ()));
	}

	/** A column on the outside edge of the group (a side touches a column without logs). */
	private static boolean onEdge(Set<Long> cols, BlockPos p) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (!cols.contains(key(p.getX() + d.getStepX(), p.getZ() + d.getStepZ()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Picks the corner of the group nearest to the player and digs a staircase up along the
	 * outside edge of the group (moving inwards only when it has to) until it stands on top of the
	 * corner tree. That height is the working level.
	 */
	private void climbAtCorner(List<Square> squares) {
		Set<Long> cols = columns();
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Square s : squares) {
			minX = Math.min(minX, s.x());
			minZ = Math.min(minZ, s.z());
			maxX = Math.max(maxX, s.x() + 1);
			maxZ = Math.max(maxZ, s.z() + 1);
		}
		Vec3 me = job.player().position();
		int[][] corners = {{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ}};
		int[] corner = corners[0];
		for (int[] c : corners) {
			if (me.distanceToSqr(c[0] + 0.5, me.y, c[1] + 0.5) < me.distanceToSqr(corner[0] + 0.5, me.y, corner[1] + 0.5)) {
				corner = c;
			}
		}
		int[] cornerFinal = corner;
		Square cornerTree = squares.stream()
				.min(Comparator.comparingDouble(s -> Math.pow(s.x() + 0.5 - cornerFinal[0], 2) + Math.pow(s.z() + 0.5 - cornerFinal[1], 2)))
				.orElseThrow();
		int targetFeet = top(cornerTree) + 1;
		BlockPos from = job.feet();
		if (from.getY() >= targetFeet) {
			return;
		}
		job.phase = "digging stairs up the side of the trees";
		BlockPos center = new BlockPos((minX + maxX) / 2, (from.getY() + targetFeet) / 2, (minZ + maxZ) / 2);
		int radiusXZ = Math.max(maxX - minX, maxZ - minZ) / 2 + 10;
		int radiusY = (targetFeet - from.getY()) / 2 + 4;
		// If a step fails (e.g. something changed), plan again from where the player is.
		for (int attempt = 0; attempt < 5 && job.feet().getY() < targetFeet; attempt++) {
			BlockPos here = job.feet();
			List<BlockPos> path = PathFinder.find(here,
					c -> c.getY() == targetFeet && inColumns(cols, c) && isLog(c.below()),
					new BlockPos(cornerTree.x(), targetFeet, cornerTree.z()), center, radiusXZ, radiusY,
					(cur, next) -> {
						double cost = WorldChecks.moveCost(cur, next);
						if (cost < 0) {
							return -1;
						}
						// Stay on the outside edge of the group where possible.
						if (inColumns(cols, next) && !onEdge(cols, next)) {
							cost += 3;
						}
						return cost;
					});
			if (path == null) {
				throw new StopException("Can't dig a staircase up the side of these trees from here. Stand next to"
						+ " a corner of the trees and try again.");
			}
			countLogs(pathBlocks(here, path));
			if (job.executePath(path)) {
				return;
			}
			job.releaseMovement();
		}
		if (job.feet().getY() < targetFeet) {
			throw new StopException("Couldn't climb the trees (" + job.lastFailure + ").");
		}
	}

	// ------------------------------------------------------------------------------------------
	// Chopping down
	// ------------------------------------------------------------------------------------------

	/**
	 * Works the group down from the working level: first every tree that sticks up above it is
	 * chopped down to it (the tallest neighbour of the current tree first, otherwise the closest),
	 * then, when nothing sticks up any more, the level itself is mined away layer by layer, which
	 * brings shorter trees into reach and finally takes everything down to the ground.
	 */
	private void workDown(List<Square> squares) {
		Set<BlockPos> failed = new HashSet<>();
		while (true) {
			treeCheckpoint();
			logs.removeIf(p -> !isLog(p));
			List<BlockPos> left = new ArrayList<>(logs);
			left.removeAll(failed);
			if (left.isEmpty()) {
				break;
			}
			List<BlockPos> above = left.stream().filter(p -> p.getY() > floorY).toList();
			if (!above.isEmpty()) {
				Square tree = nextTree(squares, above);
				Set<BlockPos> targets = new HashSet<>();
				for (BlockPos p : above) {
					if (tree == null || tree.contains(p)) {
						targets.add(p);
					}
				}
				job.phase = "chopping a tree down to Y=" + (floorY + 1);
				chopDown(targets, failed);
				continue;
			}
			// Nothing sticks up: lower the working level by mining the layer we stand on.
			int layer = left.stream().mapToInt(BlockPos::getY).max().orElseThrow();
			job.phase = "lowering the level (Y=" + layer + ", " + logs.size() + " logs left)";
			Set<BlockPos> targets = new HashSet<>();
			for (BlockPos p : left) {
				if (p.getY() == layer) {
					targets.add(p);
				}
			}
			chopDown(targets, failed);
			floorY = layer - 1;
		}
		if (!failed.isEmpty()) {
			job.msg("§eLeft " + failed.size() + " log(s) it couldn't safely reach.");
		}
	}

	/** The tallest tree next to the one the player is on that still sticks up, else the closest such tree. */
	private Square nextTree(List<Square> squares, List<BlockPos> above) {
		BlockPos me = job.feet();
		List<Square> sticking = new ArrayList<>();
		for (Square s : squares) {
			if (above.stream().anyMatch(s::contains)) {
				sticking.add(s);
			}
		}
		if (sticking.isEmpty()) {
			return null; // only odd logs outside the 2x2 trunks are left
		}
		Square current = squares.stream()
				.min(Comparator.comparingDouble(s -> Math.pow(s.x() + 1 - (me.getX() + 0.5), 2) + Math.pow(s.z() + 1 - (me.getZ() + 0.5), 2)))
				.orElseThrow();
		List<Square> neighbours = sticking.stream().filter(s -> s == current || touches(s, current)).toList();
		if (!neighbours.isEmpty()) {
			return neighbours.stream().max(Comparator.comparingInt(this::top)).orElseThrow();
		}
		return sticking.stream()
				.min(Comparator.comparingDouble(s -> Math.pow(s.x() + 1 - (me.getX() + 0.5), 2) + Math.pow(s.z() + 1 - (me.getZ() + 0.5), 2)))
				.orElseThrow();
	}

	/** True if two trunks share a side. */
	private static boolean touches(Square a, Square b) {
		int dx = Math.abs(a.x() - b.x());
		int dz = Math.abs(a.z() - b.z());
		return (dx == 2 && dz <= 1) || (dz == 2 && dx <= 1);
	}

	/** Mines the given logs, always the highest one first (closest among equals). */
	private void chopDown(Set<BlockPos> targets, Set<BlockPos> failed) {
		while (true) {
			targets.removeIf(p -> !isLog(p) || failed.contains(p));
			if (targets.isEmpty()) {
				return;
			}
			treeCheckpoint();
			BlockPos me = job.feet();
			BlockPos log = targets.stream()
					.max(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> -p.distSqr(me)))
					.orElseThrow();
			if (!reachAndMine(log)) {
				failed.add(log);
			}
		}
	}

	/**
	 * Mines a log from where the player stands if it is in reach; otherwise walks (or digs a
	 * staircase up) to a spot from where it can be reached.
	 */
	private boolean reachAndMine(BlockPos log) {
		for (int attempt = 0; attempt < 3; attempt++) {
			BlockPos me = job.feet();
			if (canReach(me, log)) {
				if (job.mineBlock(log)) {
					logsMined++;
					return true;
				}
				return false;
			}
			List<BlockPos> path = PathFinder.find(me, c -> canReach(c, log), log, me, 16, 16);
			if (path == null) {
				return false;
			}
			countLogs(pathBlocks(me, path));
			job.executePath(path);
		}
		return false;
	}

	/** Can a player standing in {@code stand} mine {@code target} without falling? */
	private static boolean canReach(BlockPos stand, BlockPos target) {
		if (target.equals(stand) || target.equals(stand.above())) {
			return false;
		}
		if (target.equals(stand.below())) {
			// Mining the block we stand on: we drop one block, so there must be a floor under it.
			return WorldChecks.isSolidFloor(target.below()) && WorldChecks.hazard(target) == null;
		}
		double ex = stand.getX() + 0.5, ey = stand.getY() + 1.62, ez = stand.getZ() + 0.5;
		double dx = Math.max(Math.max(target.getX() - ex, 0), ex - (target.getX() + 1));
		double dy = Math.max(Math.max(target.getY() - ey, 0), ey - (target.getY() + 1));
		double dz = Math.max(Math.max(target.getZ() - ez, 0), ez - (target.getZ() + 1));
		return dx * dx + dy * dy + dz * dz <= 4.3 * 4.3;
	}

	private static List<BlockPos> pathBlocks(BlockPos from, List<BlockPos> path) {
		List<BlockPos> blocks = new ArrayList<>();
		BlockPos cur = from;
		for (BlockPos next : path) {
			blocks.addAll(WorldChecks.required(cur, next));
			cur = next;
		}
		return blocks;
	}

	/** Counts the logs among blocks that are about to be dug through. */
	private void countLogs(List<BlockPos> blocks) {
		for (BlockPos b : blocks) {
			if (logs.contains(b) && isLog(b)) {
				logsMined++;
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Picking up
	// ------------------------------------------------------------------------------------------

	private void collectItems() {
		job.phase = "picking up the wood";
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : logs.isEmpty() ? List.of(start) : logs) {
			minX = Math.min(minX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxZ = Math.max(maxZ, p.getZ());
		}
		minX = Math.min(minX, start.getX());
		minZ = Math.min(minZ, start.getZ());
		maxX = Math.max(maxX, start.getX());
		maxZ = Math.max(maxZ, start.getZ());
		AABB area = new AABB(minX - 6, baseY - 3, minZ - 6, maxX + 7, baseY + 6, maxZ + 7);
		for (int i = 0; i < 30; i++) {
			job.tick(); // let the last drops land
		}
		Set<Integer> tried = new HashSet<>();
		for (int round = 0; round < 60; round++) {
			BlockPos me = job.feet();
			ItemEntity item = level().getEntitiesOfClass(ItemEntity.class, area, e -> e.isAlive() && !tried.contains(e.getId()))
					.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(Vec3.atCenterOf(me)))).orElse(null);
			if (item == null) {
				return;
			}
			tried.add(item.getId());
			BlockPos at = item.blockPosition();
			List<BlockPos> path = PathFinder.find(me, c -> {
				int dy = at.getY() - c.getY();
				int flat = Math.abs(at.getX() - c.getX()) + Math.abs(at.getZ() - c.getZ());
				return (dy == 0 || dy == 1) && flat <= 1;
			}, at, me, 16, 8);
			if (path == null) {
				continue;
			}
			job.executePath(path);
			for (int i = 0; i < 10 && item.isAlive(); i++) {
				job.tick();
			}
		}
	}

	private void treeCheckpoint() {
		job.eatIfHungry();
		if (job.freeSlots() == 0) {
			throw new StopException("Inventory is full. Stopped.");
		}
	}
}
