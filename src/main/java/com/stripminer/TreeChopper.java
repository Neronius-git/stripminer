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
 * <li>Digs a spiral staircase up inside the trunk of the tree nearest to the player (the edge of
 * the group). If another tree is taller, it walks over and spirals up that one too.</li>
 * <li>Then it always mines the highest layer of logs that is left: the tallest tree comes down
 * to the height of the next tallest, then those come down together, and so on until they are all
 * level, after which the whole group is taken down layer by layer.</li>
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

		climb(squares);
		chopTopDown();
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
	// Climbing up
	// ------------------------------------------------------------------------------------------

	private void climb(List<Square> squares) {
		Vec3 me = job.player().position();
		Square edge = squares.stream()
				.min(Comparator.comparingDouble(s -> me.distanceToSqr(s.x() + 1, me.y, s.z() + 1)))
				.orElseThrow();
		Square tallest = squares.stream().max(Comparator.comparingInt(this::top)).orElseThrow();
		spiralUp(edge);
		if (tallest != edge && top(tallest) + 1 > job.feet().getY()) {
			spiralUp(tallest);
		}
	}

	/** Gets into the trunk of {@code sq} and digs a spiral staircase up inside it to its top. */
	private void spiralUp(Square sq) {
		job.phase = "climbing the tree";
		BlockPos me = job.feet();
		if (!sq.contains(me)) {
			int y = me.getY();
			List<BlockPos> path = PathFinder.find(me, c -> sq.contains(c) && Math.abs(c.getY() - y) <= 1,
					new BlockPos(sq.x(), y, sq.z()), me, 16, 6);
			if (path == null || !job.executePath(path)) {
				throw new StopException("Can't get into the trunk of the tree at " + sq.x() + " " + sq.z() + ".");
			}
		}
		int top = top(sq);
		List<int[]> cells = sq.cells();
		while (job.feet().getY() <= top) {
			BlockPos cur = job.feet();
			int i = 0;
			while (cells.get(i)[0] != cur.getX() || cells.get(i)[1] != cur.getZ()) {
				i++;
			}
			int[] n = cells.get((i + 1) % 4);
			BlockPos next = new BlockPos(n[0], cur.getY() + 1, n[1]);
			if (!WorldChecks.isSolidFloor(next.below())) {
				break; // reached the top of this trunk
			}
			countLogs(WorldChecks.required(cur, next));
			if (!job.step(cur, next, false, true)) {
				throw new StopException("Couldn't climb the tree at " + fmt(next) + " (" + job.lastFailure + ").");
			}
			treeCheckpoint();
		}
	}

	// ------------------------------------------------------------------------------------------
	// Chopping down
	// ------------------------------------------------------------------------------------------

	/** Always mines the highest remaining layer of logs, so taller trees come down to the others first. */
	private void chopTopDown() {
		Set<BlockPos> failed = new HashSet<>();
		while (true) {
			logs.removeIf(p -> !isLog(p));
			List<BlockPos> left = new ArrayList<>(logs);
			left.removeAll(failed);
			if (left.isEmpty()) {
				break;
			}
			int layer = left.stream().mapToInt(BlockPos::getY).max().orElseThrow();
			job.phase = "chopping (Y=" + layer + ", " + logs.size() + " logs left)";
			BlockPos me = job.feet();
			BlockPos log = left.stream().filter(p -> p.getY() == layer)
					.min(Comparator.comparingDouble(p -> p.distSqr(me))).orElseThrow();
			treeCheckpoint();
			if (!chop(log)) {
				failed.add(log);
			}
		}
		if (!failed.isEmpty()) {
			job.msg("§eLeft " + failed.size() + " log(s) it couldn't safely reach.");
		}
	}

	/** Walks to a spot next to the log (digging through leaves if needed) and mines it. */
	private boolean chop(BlockPos log) {
		for (int attempt = 0; attempt < 3; attempt++) {
			BlockPos me = job.feet();
			if (StripMineJob.canMineFrom(me, log)) {
				if (job.mineBlock(log)) {
					logsMined++;
					return true;
				}
				return false;
			}
			List<BlockPos> path = PathFinder.find(me, c -> StripMineJob.canMineFrom(c, log), log, me, 16, 12);
			if (path == null) {
				return false;
			}
			countLogs(pathBlocks(me, path));
			job.executePath(path);
		}
		return false;
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
