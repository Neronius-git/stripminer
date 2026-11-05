package com.stripminer;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 *          |         6 blocks forward
 *          |
 *     LLLLL@RRRRR
 * </pre>
 *
 * Each branch is mined to its full length first; then every target ore that is now visible from
 * the branch is mined (digging a path to it if needed, following the whole vein), the drops are
 * picked up, and the miner walks back to the main tunnel before doing the other side.
 */
public final class StripMineJob {
	private static final Set<Item> JUNK = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE,
			Items.DEEPSLATE, Items.DIRT, Items.GRAVEL, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF,
			Items.NETHERRACK, Items.CALCITE, Items.FLINT, Items.SMOOTH_BASALT, Items.BASALT, Items.BLACKSTONE);
	private static final Set<Item> FILLER = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE,
			Items.DEEPSLATE, Items.DIRT, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.NETHERRACK,
			Items.BLACKSTONE);
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
	private int ticks;
	private boolean warnedNoTorches;
	private volatile String phase = "starting";
	private String lastFailure = "";
	/** Blocks dug since the last call to {@link #takeCleared()}, used to spot newly revealed ores. */
	private final List<BlockPos> cleared = new ArrayList<>();

	public StripMineJob(MinerConfig cfg, Integer targetY) {
		this.cfg = cfg;
		this.targetY = targetY;
	}

	public String status() {
		return phase + " | tunnel " + tunnelLength + " blocks | " + oresMined + " ores mined";
	}

	// ------------------------------------------------------------------------------------------
	// Main routine
	// ------------------------------------------------------------------------------------------

	void run() {
		targets = cfg.resolveOres();
		if (targets.isEmpty()) {
			throw new StopException("No valid ores configured. Use /stripmine ores add <block>.");
		}
		forward = player().getDirection();
		checkTools();
		msg("Strip mining towards §b" + forward.getName() + "§r, looking for " + cfg.ores
				+ ". Use §b/stripmine stop§r to stop.");

		phase = "centering";
		moveTo(Vec3.atBottomCenterOf(feet()));

		if (targetY != null && targetY != feet().getY()) {
			goToHeight(targetY);
		}

		tunnelLength = 0;
		lastTorchAt = 0;
		placeTorchHere();

		while (true) {
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

	/** Mines one side branch, then the ores it revealed, then walks back to {@code home}. */
	private void branch(BlockPos home, Direction side, String name) {
		phase = "mining " + name + " branch";
		List<BlockPos> dug = new ArrayList<>();
		for (int i = 1; i <= cfg.branchLength; i++) {
			checkpoint(false);
			BlockPos cur = feet();
			BlockPos next = cur.relative(side);
			if (!step(cur, next, true)) {
				msg("The " + name + " branch was cut short after " + (i - 1) + " blocks: " + lastFailure + ".");
				break;
			}
			dug.add(next);
		}
		phase = "checking " + name + " branch for ores";
		mineRevealedOres(dug, home);
		phase = "returning to tunnel";
		returnTo(home);
	}

	/** Digs a staircase (in the facing direction) until the feet are at height {@code y}. */
	private void goToHeight(int y) {
		phase = "digging stairs to Y=" + y;
		msg("Digging a staircase to Y=" + y + "...");
		while (feet().getY() != y) {
			checkpoint(false);
			BlockPos cur = feet();
			int dy = y < cur.getY() ? -1 : 1;
			BlockPos next = cur.relative(forward).offset(0, dy, 0);
			if (!step(cur, next, false)) {
				throw new StopException("Can't continue the staircase at " + fmt(next) + " (" + lastFailure
						+ "). Stopped to stay safe.");
			}
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
	 * Finds every target ore touching the given open cells (feet and head block), mines each one
	 * together with the rest of its vein, and collects the drops.
	 */
	private void mineRevealedOres(Collection<BlockPos> feetCells, BlockPos origin) {
		takeCleared();
		Set<BlockPos> seen = new HashSet<>();
		List<BlockPos> pending = new ArrayList<>();
		for (BlockPos c : feetCells) {
			addTouchingOres(c, seen, pending);
			addTouchingOres(c.above(), seen, pending);
		}
		int mined = 0;
		while (!pending.isEmpty() && mined < 256) {
			checkpoint(false);
			BlockPos me = feet();
			pending.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
			BlockPos ore = pending.remove(0);
			if (!isTarget(ore)) {
				continue;
			}
			if (Math.abs(ore.getX() - origin.getX()) > cfg.veinRadius
					|| Math.abs(ore.getZ() - origin.getZ()) > cfg.veinRadius
					|| Math.abs(ore.getY() - origin.getY()) > 6) {
				continue;
			}
			String danger = hazard(ore);
			if (danger != null) {
				msg("§cSkipping ore at " + fmt(ore) + " (" + danger + ").");
				continue;
			}
			phase = "going to ore at " + fmt(ore);
			if (!reachOre(ore, origin)) {
				msg("Couldn't safely reach the ore at " + fmt(ore) + ", skipping it.");
				continue;
			}
			phase = "mining ore";
			if (!mineBlock(ore)) {
				continue;
			}
			mined++;
			oresMined++;
			collectDrops(ore, origin);

			// Follow the vein (including diagonal neighbours) ...
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = ore.offset(dx, dy, dz);
						if (seen.add(n) && isTarget(n)) {
							pending.add(n);
						}
					}
				}
			}
			// ... and pick up anything the path to it revealed.
			for (BlockPos c : takeCleared()) {
				addTouchingOres(c, seen, pending);
			}
		}
	}

	private void addTouchingOres(BlockPos open, Set<BlockPos> seen, List<BlockPos> pending) {
		for (Direction d : Direction.values()) {
			BlockPos n = open.relative(d);
			if (isTarget(n) && seen.add(n)) {
				pending.add(n);
			}
		}
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

	private boolean reachOre(BlockPos ore, BlockPos origin) {
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
				e -> e.isAlive() && !JUNK.contains(e.getItem().getItem())));
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
		for (BlockPos next : path) {
			BlockPos cur = feet();
			int flat = Math.abs(next.getX() - cur.getX()) + Math.abs(next.getZ() - cur.getZ());
			if (flat != 1 || Math.abs(next.getY() - cur.getY()) > 1) {
				return false;
			}
			if (!step(cur, next, false)) {
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
			if (!placeFiller(cur.below(), dir) || !isSolidFloor(floor)) {
				lastFailure = "gap in the floor at " + fmt(floor) + " and no cobblestone/dirt to fill it";
				return false;
			}
		}
		if (!moveTo(Vec3.atBottomCenterOf(next)) || !feet().equals(next)) {
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
				gm.stopDestroyBlock();
				lastFailure = "block at " + fmt(pos) + " took too long to mine";
				return false;
			}
			BlockState state = WorldChecks.level().getBlockState(pos);
			Direction face = faceTowardsEyes(pos);
			lookAt(Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5));
			selectBestTool(state);
			gm.continueDestroyBlock(pos, face);
			player().swing(InteractionHand.MAIN_HAND);
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
		double best = Double.MAX_VALUE;
		int stalled = 0;
		try {
			while (true) {
				LocalPlayer p = player();
				double dx = target.x - p.getX();
				double dz = target.z - p.getZ();
				double dy = target.y - p.getY();
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (dist < 0.15 && Math.abs(dy) < 0.4 && p.onGround()) {
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
				setKey(mc.options.keyUp, dist >= 0.12);
				// Sneak when close: slows down for precise stopping and never walks off an edge.
				setKey(mc.options.keyShift, dist < 0.6 && Math.abs(dy) < 0.4);
				setKey(mc.options.keyJump, dy > 0.5 && p.onGround());
				tick();
			}
		} finally {
			releaseMovement();
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
			inv.selected = best;
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
			gameMode().handleInventoryMouseClick(player().inventoryMenu.containerId, i, target, ClickType.SWAP, player());
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
		player().getInventory().selected = slot;
		Vec3 hit = Vec3.atCenterOf(floor).add(0, 0.5, 0);
		lookAt(hit);
		gameMode().useItemOn(player(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, floor, false));
		player().swing(InteractionHand.MAIN_HAND);
		tick();
	}

	/** Places a filler block against the side of {@code against} facing {@code dir}. */
	private boolean placeFiller(BlockPos against, Direction dir) {
		int slot = ensureInHotbar(s -> FILLER.contains(s.getItem()));
		if (slot < 0) {
			return false;
		}
		player().getInventory().selected = slot;
		Vec3 hit = Vec3.atCenterOf(against).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5);
		lookAt(hit);
		setKey(mc.options.keyShift, true);
		tick();
		gameMode().useItemOn(player(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, dir, against, false));
		player().swing(InteractionHand.MAIN_HAND);
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
		p.getInventory().selected = slot;
		p.setXRot(-90f);
		gameMode().useItem(p, InteractionHand.MAIN_HAND);
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

	/** Throws stone-type junk behind the player when the inventory is nearly full (keeps one filler stack). */
	private void dropJunk() {
		if (!cfg.dropJunk || freeSlots() > 3) {
			return;
		}
		LocalPlayer p = player();
		p.setYRot(forward.getOpposite().toYRot());
		p.setXRot(0f);
		tick();
		Inventory inv = p.getInventory();
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
			int menuSlot = i < 9 ? 36 + i : i;
			gameMode().handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot, 1, ClickType.THROW, p);
		}
		tick();
	}

	/** Housekeeping between steps: tools, food and inventory space. */
	private void checkpoint(boolean inMainTunnel) {
		checkTools();
		eatIfHungry();
		if (inMainTunnel) {
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
			p.displayClientMessage(Component.literal("§6StripMiner:§r " + status()), true);
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
			gm.stopDestroyBlock();
		}
	}
}
