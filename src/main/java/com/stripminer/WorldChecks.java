package com.stripminer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Read-only questions about blocks around the player. All lava-safety rules live here. */
public final class WorldChecks {
	private WorldChecks() {
	}

	static Level level() {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			throw new StopException("Stopped: left the world.");
		}
		return level;
	}

	public static boolean isLava(BlockPos pos) {
		return level().getFluidState(pos).is(FluidTags.LAVA);
	}

	/** True if the player can stand/walk in this block without mining it. */
	public static boolean passable(BlockPos pos) {
		Level level = level();
		BlockState state = level.getBlockState(pos);
		if (state.getFluidState().is(FluidTags.LAVA)) {
			return false;
		}
		if (state.getBlock() instanceof BaseFireBlock || state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW)
				|| state.is(Blocks.SWEET_BERRY_BUSH)) {
			return false;
		}
		return state.getCollisionShape(level, pos).isEmpty();
	}

	public static boolean canMine(BlockPos pos) {
		Level level = level();
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.getFluidState().is(FluidTags.LAVA)) {
			return false;
		}
		return state.getDestroySpeed(level, pos) >= 0;
	}

	/** A block that is safe to stand on: full top face, not lava, not magma. */
	public static boolean isSolidFloor(BlockPos pos) {
		Level level = level();
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (state.is(Blocks.MAGMA_BLOCK) || state.getFluidState().is(FluidTags.LAVA)) {
			return false;
		}
		return state.isFaceSturdy(level, pos, Direction.UP);
	}

	/**
	 * Returns why it would be dangerous to open up / stand in this block, or null if it is safe.
	 * A block is unsafe if it is lava, touches lava on any side, or has a column of gravel/sand
	 * above it that touches lava (because that column falls down when this block is removed).
	 */
	public static String hazard(BlockPos pos) {
		Level level = level();
		if (!level.isLoaded(pos)) {
			return "chunk not loaded";
		}
		if (isLava(pos)) {
			return "lava";
		}
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof BaseFireBlock) {
			return "fire";
		}
		if (!passable(pos) && !canMine(pos)) {
			return "unbreakable block";
		}
		if (!lavaNeighbors(pos).isEmpty()) {
			return "lava next to " + fmt(pos);
		}
		if (lavaBehindFallingBlocks(pos)) {
			return "lava behind falling blocks above " + fmt(pos);
		}
		return null;
	}

	/** The sides of {@code pos} that touch lava. */
	public static List<Direction> lavaNeighbors(BlockPos pos) {
		List<Direction> sides = new ArrayList<>();
		for (Direction d : Direction.values()) {
			if (isLava(pos.relative(d))) {
				sides.add(d);
			}
		}
		return sides;
	}

	/** True if a column of gravel/sand above {@code pos} touches lava (it would fall when pos is mined). */
	public static boolean lavaBehindFallingBlocks(BlockPos pos) {
		Level level = level();
		BlockPos up = pos.above();
		for (int i = 0; i < 16 && level.getBlockState(up).getBlock() instanceof FallingBlock; i++) {
			if (!lavaNeighbors(up).isEmpty()) {
				return true;
			}
			up = up.above();
		}
		return false;
	}

	/**
	 * The blocks that must be empty to walk from {@code cur} to the neighbouring cell {@code next}
	 * (which may be one block higher or lower), listed top to bottom.
	 */
	public static List<BlockPos> required(BlockPos cur, BlockPos next) {
		int dy = next.getY() - cur.getY();
		if (dy > 0) {
			return List.of(cur.above(2), next.above(), next);
		} else if (dy < 0) {
			return List.of(next.above(2), next.above(), next);
		}
		return List.of(next.above(), next);
	}

	/** Cost of digging/walking from cur to next, or -1 if not possible or not safe. */
	public static double moveCost(BlockPos cur, BlockPos next) {
		if (!isSolidFloor(next.below())) {
			return -1;
		}
		int dy = next.getY() - cur.getY();
		double cost = dy == 0 ? 1 : 2;
		for (BlockPos b : required(cur, next)) {
			if (hazard(b) != null) {
				return -1;
			}
			if (!passable(b)) {
				cost += 4;
			}
		}
		return cost;
	}

	public static String fmt(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}
}
