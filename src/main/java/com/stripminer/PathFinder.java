package com.stripminer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos next = n.pos.relative(d).offset(0, dy, 0);
					if (Math.abs(next.getX() - center.getX()) > radiusXZ
							|| Math.abs(next.getZ() - center.getZ()) > radiusXZ
							|| Math.abs(next.getY() - center.getY()) > radiusY) {
						continue;
					}
					double cost = WorldChecks.moveCost(n.pos, next);
					if (cost < 0) {
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

	private static double heuristic(BlockPos a, BlockPos b) {
		return Math.max(0, a.distManhattan(b) - 2);
	}
}
