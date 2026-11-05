package com.stripminer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class StripMinerClient implements ClientModInitializer {
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		MinerConfig.get();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("stripminer", "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.stripminer.toggle",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.START_CLIENT_TICK.register(MinerRunner::onClientTick);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (toggleKey.consumeClick()) {
				if (MinerRunner.isRunning()) {
					MinerRunner.stop("Stopped.");
				} else {
					startMining(null);
				}
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
	}

	private static void startMining(Integer y) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (MinerRunner.start(new StripMineJob(MinerConfig.get(), y))) {
			MinerRunner.sendChat("Started" + (y != null ? " (target Y=" + y + ")" : "") + ".");
		} else {
			MinerRunner.sendChat("Already running. Use /stripmine stop first.");
		}
	}

	private static final SuggestionProvider<FabricClientCommandSource> ORE_SUGGESTIONS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(BuiltInRegistries.BLOCK.keySet().stream()
					.filter(id -> id.getPath().endsWith("_ore") || id.getPath().equals("ancient_debris"))
					.map(Identifier::toString), builder);

	private static final SuggestionProvider<FabricClientCommandSource> CONFIGURED_ORES = (ctx, builder) ->
			SharedSuggestionProvider.suggest(MinerConfig.get().ores, builder);

	/** A setting that can be changed with /config. */
	private record Option<T>(String name, String help, Function<MinerConfig, T> get, BiConsumer<MinerConfig, T> set,
			ArgumentType<T> type, Class<T> cls) {
	}

	private static final List<Option<?>> OPTIONS = List.of(
			intOpt("branchlength", "blocks mined to each side (max 5, the reach limit)", 1, 5, c -> c.branchLength, (c, v) -> c.branchLength = v),
			intOpt("branchspacing", "blocks forward between branches", 2, 64, c -> c.branchSpacing, (c, v) -> c.branchSpacing = v),
			intOpt("torchinterval", "blocks between floor torches (0 = none)", 0, 256, c -> c.torchInterval, (c, v) -> c.torchInterval = v),
			intOpt("maxlength", "stop after this many tunnel blocks (0 = never)", 0, 100000, c -> c.maxTunnelLength, (c, v) -> c.maxTunnelLength = v),
			intOpt("veinradius", "how far from the tunnel it follows a vein", 1, 16, c -> c.veinRadius, (c, v) -> c.veinRadius = v),
			intOpt("eatbelowfood", "eat when hunger is at or below this (of 20)", 0, 19, c -> c.eatBelowFood, (c, v) -> c.eatBelowFood = v),
			intOpt("stopbelowhealth", "stop when health is at or below this (of 20)", 0, 19, c -> c.stopBelowHealth, (c, v) -> c.stopBelowHealth = v),
			intOpt("keeptooldurability", "never use a tool with this much durability left or less", 0, 100, c -> c.keepToolDurability, (c, v) -> c.keepToolDurability = v),
			boolOpt("dropjunk", "throw away cobblestone/stone/dirt when the inventory is nearly full", c -> c.dropJunk, (c, v) -> c.dropJunk = v),
			boolOpt("compressfullstacks", "craft full stacks into blocks at a crafting table", c -> c.compressFullStacks, (c, v) -> c.compressFullStacks = v),
			boolOpt("minearoundores", "dig the fewest blocks needed to make sure no ore of a vein is hidden", c -> c.mineAroundOres, (c, v) -> c.mineAroundOres = v),
			boolOpt("isolatelava", "fill lava next to an ore with gravel/sand instead of skipping the ore", c -> c.isolateLava, (c, v) -> c.isolateLava = v),
			boolOpt("collectgravel", "take back gravel pillars that are safe to remove (torch trick)", c -> c.collectGravel, (c, v) -> c.collectGravel = v));

	private static Option<Integer> intOpt(String name, String help, int min, int max,
			Function<MinerConfig, Integer> get, BiConsumer<MinerConfig, Integer> set) {
		return new Option<>(name, help, get, set, IntegerArgumentType.integer(min, max), Integer.class);
	}

	private static Option<Boolean> boolOpt(String name, String help,
			Function<MinerConfig, Boolean> get, BiConsumer<MinerConfig, Boolean> set) {
		return new Option<>(name, help, get, set, BoolArgumentType.bool(), Boolean.class);
	}

	private static <T> LiteralArgumentBuilder<FabricClientCommandSource> optionNode(Option<T> opt) {
		return literal(opt.name())
				.executes(ctx -> {
					feedback(ctx, opt.name() + " = §b" + opt.get().apply(MinerConfig.get()) + "§r (" + opt.help() + ")");
					return 1;
				})
				.then(argument("value", opt.type()).executes(ctx -> {
					T v = ctx.getArgument("value", opt.cls());
					MinerConfig c = MinerConfig.get();
					opt.set().accept(c, v);
					c.save();
					feedback(ctx, opt.name() + " set to §b" + v + "§r"
							+ (MinerRunner.isRunning() ? " (takes effect on the next start)" : "") + ".");
					return 1;
				}));
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("stripmine")
				.executes(StripMinerClient::help)
				.then(literal("help").executes(StripMinerClient::help))
				.then(literal("start")
						.executes(ctx -> {
							startMining(null);
							return 1;
						})
						.then(argument("y", IntegerArgumentType.integer(-63, 319)).executes(ctx -> {
							startMining(IntegerArgumentType.getInteger(ctx, "y"));
							return 1;
						})))
				.then(literal("stop").executes(ctx -> {
					if (MinerRunner.isRunning()) {
						MinerRunner.stop("Stopped.");
					} else {
						feedback(ctx, "Not running.");
					}
					return 1;
				}))
				.then(literal("status").executes(ctx -> {
					StripMineJob job = MinerRunner.current();
					feedback(ctx, job == null ? "Not running." : job.status());
					return 1;
				}))
				.then(configNode()));
		d.register(configNode());
	}

	/** The settings command tree, used for both /config and /stripmine config. */
	private static LiteralArgumentBuilder<FabricClientCommandSource> configNode() {
		LiteralArgumentBuilder<FabricClientCommandSource> config = literal("config").executes(ctx -> {
			feedback(ctx, "Settings (change with §b/config <name> <value>§r):");
			MinerConfig c = MinerConfig.get();
			for (Option<?> opt : OPTIONS) {
				ctx.getSource().sendFeedback(Component.literal(" §b" + opt.name() + "§r = " + opt.get().apply(c) + " §7- " + opt.help()));
			}
			ctx.getSource().sendFeedback(Component.literal(" §bores§r = " + String.join(", ", c.ores) + " §7- /config ores add|remove|list|clear|reset"));
			return 1;
		});
		for (Option<?> opt : OPTIONS) {
			config.then(optionNode(opt));
		}
		config.then(literal("ores")
				.executes(ctx -> listOres(ctx))
				.then(literal("list").executes(ctx -> listOres(ctx)))
				.then(literal("add").then(argument("block", StringArgumentType.greedyString())
						.suggests(ORE_SUGGESTIONS).executes(ctx -> {
							String id = normalize(StringArgumentType.getString(ctx, "block"));
							Identifier rl = Identifier.tryParse(id);
							if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
								ctx.getSource().sendError(Component.literal("Unknown block: " + id));
								return 0;
							}
							MinerConfig c = MinerConfig.get();
							if (!c.ores.contains(id)) {
								c.ores.add(id);
								c.save();
							}
							return listOres(ctx);
						})))
				.then(literal("remove").then(argument("block", StringArgumentType.greedyString())
						.suggests(CONFIGURED_ORES).executes(ctx -> {
							MinerConfig c = MinerConfig.get();
							c.ores.remove(normalize(StringArgumentType.getString(ctx, "block")));
							c.save();
							return listOres(ctx);
						})))
				.then(literal("clear").executes(ctx -> {
					MinerConfig c = MinerConfig.get();
					c.ores.clear();
					c.save();
					return listOres(ctx);
				}))
				.then(literal("reset").executes(ctx -> {
					MinerConfig c = MinerConfig.get();
					c.ores = new java.util.ArrayList<>(MinerConfig.DEFAULT_ORES);
					c.save();
					return listOres(ctx);
				})));
		return config;
	}

	private static String normalize(String id) {
		id = id.trim().toLowerCase(java.util.Locale.ROOT);
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static int listOres(CommandContext<FabricClientCommandSource> ctx) {
		feedback(ctx, "Target ores: " + (MinerConfig.get().ores.isEmpty() ? "(none)" : String.join(", ", MinerConfig.get().ores)));
		return 1;
	}

	private static void feedback(CommandContext<FabricClientCommandSource> ctx, String text) {
		ctx.getSource().sendFeedback(Component.literal("§6[StripMiner]§r " + text));
	}

	private static int help(CommandContext<FabricClientCommandSource> ctx) {
		feedback(ctx, "Commands:");
		for (String line : new String[] {
				"§b/stripmine start [y]§r - start mining in the direction you face (digs stairs to Y first if given)",
				"§b/stripmine stop§r - stop",
				"§b/stripmine status§r - progress and settings",
				"§b/config§r or §b/stripmine config§r - list all settings",
				"§b/config <setting> <value>§r - change a setting, e.g. /config compressfullstacks false",
				"§b/config ores add|remove|list|clear|reset§r - which ores to mine"}) {
			ctx.getSource().sendFeedback(Component.literal(line));
		}
		return 1;
	}
}
