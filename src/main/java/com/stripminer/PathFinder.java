package com.stripminer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A* search over standing positions (feet blocks). Moves are one block sideways, optionally one
 * block up or down. Digging is allowed but costs more than walking through open space, and any
 * move that would open up a block next to lava is never taken.
 */
public final class PathFinder {
	private static final int MAX_EXPANSIONS = 20000;

	private record Node(BlockPos pos, double g, double f, Node parent) {
	}

	private PathFinder() {
	}

	/**
	 * @return the cells to step through (not including {@code start}), an empty list if
	 * {@code start} already satisfies {@code goal}, or null if there is no safe path.
	 */
	public static List<BlockPos> find(BlockPos start, Predicate<BlockPos> goal, BlockPos target,
			BlockPos center, int radiusXZ, int radiusY) {
		return find(start, goal, target, center, radiusXZ, radiusY, WorldChecks::moveCost);
	}

	/** Cost of moving from one cell to a neighbouring one, or a negative number if not allowed. */
	public interface MoveCost {
		double cost(BlockPos cur, BlockPos next);
	}

	public static List<BlockPos> find(BlockPos start, Predicate<BlockPos> goal, BlockPos target,
			BlockPos center, int radiusXZ, int radiusY, MoveCost moveCost) {
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
		Map<BlockPos, Double> best = new HashMap<>();
		open.add(new Node(start, 0, heuristic(start, target), null));
		best.put(start, 0.0);
		int expansions = 0;
		while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
			Node n = open.poll();
			if (n.g > best.getOrDefault(n.pos, Double.MAX_VALUE)) {
				continue;
			}
			if (goal.test(n.pos)) {
				List<BlockPos> path = new ArrayList<>();
				for (Node k = n; k.parent != null; k = k.parent) {
					path.add(k.pos);
				}
				Collections.reverse(path);
				return path;
			}
			Set<BlockPos> dugOnTheWay = null;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos next = n.pos.relative(d).offset(0, dy, 0);
					if (Math.abs(next.getX() - center.getX()) > radiusXZ
							|| Math.abs(next.getZ() - center.getZ()) > radiusXZ
							|| Math.abs(next.getY() - center.getY()) > radiusY) {
						continue;
					}
					double cost = moveCost.cost(n.pos, next);
					if (cost < 0) {
						continue;
					}
					// Never plan to stand on a block that an earlier step of this same path digs away
					// (e.g. a staircase that turns back on itself).
					if (dugOnTheWay == null) {
						dugOnTheWay = dugOnTheWay(n);
					}
					if (dugOnTheWay.contains(next.below())) {
						continue;
					}
					double g = n.g + cost;
					if (g < best.getOrDefault(next, Double.MAX_VALUE)) {
						best.put(next, g);
						open.add(new Node(next, g, g + heuristic(next, target), n));
					}
				}
			}
		}
		return null;
	}

	/** The blocks the path up to {@code n} digs (looking back a limited number of steps). */
	private static Set<BlockPos> dugOnTheWay(Node n) {
		Set<BlockPos> dug = new HashSet<>();
		int depth = 0;
		for (Node k = n; k.parent != null && depth < 24; k = k.parent, depth++) {
			dug.addAll(WorldChecks.required(k.parent.pos, k.pos));
		}
		return dug;
	}

	private static double heuristic(BlockPos a, BlockPos b) {
		return Math.max(0, a.distManhattan(b) - 2);
	}
}
