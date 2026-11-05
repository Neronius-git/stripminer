package com.stripminer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Settings, saved to config/stripminer.json. */
public final class MinerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("stripminer.json");
	private static MinerConfig instance;

	public static final List<String> DEFAULT_ORES = List.of(
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore");

	/** Blocks to search for and mine when they are revealed. */
	public List<String> ores = new ArrayList<>(DEFAULT_ORES);
	/** How many blocks each side branch goes to the left and to the right (at most 5: the player's reach). */
	public int branchLength = 5;
	/** How many blocks the main tunnel moves forward between branches. */
	public int branchSpacing = 3;
	/** A torch is placed on the floor of the main tunnel every this many blocks (0 = never). */
	public int torchInterval = 0;
	/** Stop after the main tunnel is this long (0 = keep going until stopped). */
	public int maxTunnelLength = 0;
	/** How far (horizontally) the miner may dig away from the tunnel to follow an ore vein. */
	public int veinRadius = 8;
	/** Throw away cobblestone/deepslate/dirt etc. when the inventory is almost full. */
	public boolean dropJunk = true;
	/** Eat when the hunger bar drops to this value or below (out of 20). */
	public int eatBelowFood = 14;
	/** Stop if health drops to this value or below (out of 20). */
	public int stopBelowHealth = 8;
	/** Never use a tool that has this much durability left or less, so your pickaxe doesn't break. */
	public int keepToolDurability = 5;
	/**
	 * When a stack of something that can be crafted into a block (diamonds, raw iron, redstone,
	 * coal, ...) is full, place a crafting table, craft the stacks into blocks and mine the table again.
	 */
	public boolean compressFullStacks = true;
	/**
	 * After mining a vein, dig the fewest blocks needed so that every block touching the vein
	 * (diagonals included) has been seen, so no ore of the vein stays hidden.
	 */
	public boolean mineAroundOres = true;
	/** Instead of skipping an ore that touches lava, fill the lava with gravel/sand first and then mine it. */
	public boolean isolateLava = true;
	/** Afterwards, take back the gravel pillars that can be removed without letting lava flow (torch trick). */
	public boolean collectGravel = true;
	/** Used to update old config files. */
	public int configVersion = 0;

	public static MinerConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static MinerConfig load() {
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				MinerConfig c = GSON.fromJson(r, MinerConfig.class);
				if (c != null) {
					if (c.ores == null) {
						c.ores = new ArrayList<>(DEFAULT_ORES);
					}
					if (c.configVersion < 2) {
						c.branchSpacing = 3; // the old default of 6 was a misunderstanding
					}
					if (c.configVersion < 3) {
						if (c.torchInterval == 28) {
							c.torchInterval = 0; // torches are now off by default
						}
						c.branchLength = Math.min(c.branchLength, 5);
					}
					if (c.configVersion < 3) {
						c.configVersion = 3;
						c.save();
					}
					return c;
				}
			} catch (Exception e) {
				MinerRunner.LOG.warn("Could not read {}, using defaults", FILE, e);
			}
		}
		MinerConfig c = new MinerConfig();
		c.configVersion = 3;
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(this, w);
			}
		} catch (Exception e) {
			MinerRunner.LOG.warn("Could not save {}", FILE, e);
		}
	}

	public Set<Block> resolveOres() {
		Set<Block> blocks = new HashSet<>();
		for (String id : ores) {
			Identifier rl = Identifier.tryParse(id);
			if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
				blocks.add(BuiltInRegistries.BLOCK.getValue(rl));
			}
		}
		return blocks;
	}
}
