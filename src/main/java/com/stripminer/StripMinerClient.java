package com.stripminer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Key binding and chat commands. Every command accepts {@code help} in place of a value, e.g.
 * {@code /stripmine start help} or {@code /stripmine config settings veinradius help}.
 */
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
					startMining(MinerConfig.get().targetY, null);
				}
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath("stripminer", "status"), StripMinerClient::drawHud);
	}

	/** Top-left overlay while mining: time, tunnel length and one line per ore type. */
	private static void drawHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		StripMineJob job = MinerRunner.current();
		Minecraft mc = Minecraft.getInstance();
		if (job == null || mc.player == null) {
			return;
		}
		List<StripMineJob.HudLine> lines = new ArrayList<>(job.hudLines());
		if (MinerRunner.isPaused()) {
			lines.add(0, new StripMineJob.HudLine("PAUSED - /stripmine resume", 0xFFFF5555));
		}
		if (lines.isEmpty()) {
			return;
		}
		int width = 0;
		for (StripMineJob.HudLine line : lines) {
			width = Math.max(width, mc.font.width(line.text()));
		}
		int x = 4;
		int y = 4;
		int lineHeight = 10;
		graphics.fill(x - 2, y - 2, x + width + 2, y + lines.size() * lineHeight, 0x80000000);
		for (StripMineJob.HudLine line : lines) {
			graphics.text(mc.font, line.text(), x, y, line.color());
			y += lineHeight;
		}
	}

	static String version() {
		return FabricLoader.getInstance().getModContainer("stripminer")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	private static void startMining(Integer y, Goal goal) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (MinerRunner.start(new StripMineJob(MinerConfig.get(), y, goal))) {
			MinerRunner.sendChat("Started" + (y != null ? " (target Y=" + y + ")" : "")
					+ (goal != null ? " - goal: " + goal.describe() : "") + ".");
		} else {
			MinerRunner.sendChat("Already running. Use /stripmine stop first.");
		}
	}

	// ------------------------------------------------------------------------------------------
	// Settings
	// ------------------------------------------------------------------------------------------

	/** A setting. {@code tunnel} settings live under "config settings", the rest directly under "config". */
	private record Option<T>(String name, boolean tunnel, String range, String help, Function<MinerConfig, T> get,
			BiConsumer<MinerConfig, T> set, ArgumentType<T> type, Class<T> cls) {
		String usage() {
			return "/stripmine config " + (tunnel ? "settings " : "") + name + " <" + range + ">";
		}
	}

	private static final MinerConfig DEFAULTS = new MinerConfig();

	private static final List<Option<?>> OPTIONS = List.of(
			intOpt("branchlength", true, 1, 5, "How many blocks each side branch goes to the left and to the right"
					+ " (a one block high line at head height). At most 5: the furthest a player can reach while"
					+ " standing still.", c -> c.branchLength, (c, v) -> c.branchLength = v),
			intOpt("branchspacing", true, 2, 64, "How many blocks the main tunnel moves forward between two pairs"
					+ " of branches.", c -> c.branchSpacing, (c, v) -> c.branchSpacing = v),
			intOpt("torchinterval", true, 0, 256, "Place a torch on the tunnel floor every this many blocks."
					+ " 0 = no torches.", c -> c.torchInterval, (c, v) -> c.torchInterval = v),
			intOpt("maxlength", true, 0, 100000, "Always stop once the main tunnel is this long. 0 = no limit."
					+ " For a one-off limit use /stripmine start length <blocks> instead.",
					c -> c.maxTunnelLength, (c, v) -> c.maxTunnelLength = v),
			intOpt("veinradius", true, 1, 16, "How far (in blocks, sideways from its spot in the main tunnel) it"
					+ " may go to mine ore. Ore further away is left alone, so one big vein can't drag it off forever.",
					c -> c.veinRadius, (c, v) -> c.veinRadius = v),
			intOpt("targety", true, -63, 319, "The height /stripmine start digs stairs to before mining (feet level)."
					+ " -58 is good for diamonds. Use /stripmine start here to mine at your current height instead.",
					c -> c.targetY, (c, v) -> c.targetY = v),
			boolOpt("spiralstaircase", true, "Dig a spiral staircase (2x2, turning right every step) instead of a"
					+ " straight one when going to the target height. Takes no room ahead and is easy to climb back up.",
					c -> c.spiralStaircase, (c, v) -> c.spiralStaircase = v),
			intOpt("clustersize", false, 1, 5, "How big an ore cluster can be: it is assumed to fit in a box this"
					+ " many blocks wide, high and deep (2 = 2x2x2). Only blocks that could still be in such a box"
					+ " with all the ores found are checked for hidden ore. If a cluster turns out bigger, it adapts.",
					c -> c.clusterSize, (c, v) -> c.clusterSize = v),
			boolOpt("minearoundores", "After mining all the ore it can see, work out from the mined ores where more"
					+ " of the same cluster could be hiding (every block touching them, diagonals too, that hasn't been"
					+ " seen yet), then dig the fewest blocks needed to check those spots. Any ore found is mined"
					+ " and the check repeats. Off = only mine ore it can see.",
					c -> c.mineAroundOres, (c, v) -> c.mineAroundOres = v),
			boolOpt("isolatelava", "When an ore touches lava, fill the lava in with gravel/sand (or cobblestone)"
					+ " and then mine the ore, instead of skipping it.", c -> c.isolateLava, (c, v) -> c.isolateLava = v),
			boolOpt("collectgravel", "After filling lava, take back the gravel pillars that don't touch lava"
					+ " (mine the bottom block, put a torch there, collect the falling gravel).",
					c -> c.collectGravel, (c, v) -> c.collectGravel = v),
			boolOpt("compressfullstacks", "When a stack of diamonds, raw iron, redstone, ... is full, place a"
					+ " crafting table, craft the stacks into blocks and pick the table back up.",
					c -> c.compressFullStacks, (c, v) -> c.compressFullStacks = v),
			boolOpt("dropjunk", "When the inventory is nearly full, throw away cobblestone, deepslate, dirt and"
					+ " other stone (one stack is kept for bridging gaps and blocking lava).",
					c -> c.dropJunk, (c, v) -> c.dropJunk = v),
			intOpt("eatbelowfood", false, 0, 19, "Eat when the hunger bar is at or below this (out of 20).",
					c -> c.eatBelowFood, (c, v) -> c.eatBelowFood = v),
			intOpt("stopbelowhealth", false, 0, 19, "Stop when health is at or below this (out of 20).",
					c -> c.stopBelowHealth, (c, v) -> c.stopBelowHealth = v),
			intOpt("keeptooldurability", false, 0, 100, "Never use a tool with this much durability left or"
					+ " less, so your pickaxe never breaks.", c -> c.keepToolDurability, (c, v) -> c.keepToolDurability = v));

	private static Option<Integer> intOpt(String name, boolean tunnel, int min, int max, String help,
			Function<MinerConfig, Integer> get, BiConsumer<MinerConfig, Integer> set) {
		return new Option<>(name, tunnel, min + "-" + max, help, get, set, IntegerArgumentType.integer(min, max), Integer.class);
	}

	private static Option<Boolean> boolOpt(String name, String help,
			Function<MinerConfig, Boolean> get, BiConsumer<MinerConfig, Boolean> set) {
		return boolOpt(name, false, help, get, set);
	}

	private static Option<Boolean> boolOpt(String name, boolean tunnel, String help,
			Function<MinerConfig, Boolean> get, BiConsumer<MinerConfig, Boolean> set) {
		return new Option<>(name, tunnel, "true|false", help, get, set, BoolArgumentType.bool(), Boolean.class);
	}

	private static <T> List<String> optionHelp(Option<T> opt) {
		return List.of("§6" + opt.name() + "§r - " + opt.help(),
				"Usage: §b" + opt.usage(),
				"Current value: §b" + opt.get().apply(MinerConfig.get()) + "§r, default: " + opt.get().apply(DEFAULTS));
	}

	private static <T> LiteralArgumentBuilder<FabricClientCommandSource> optionNode(Option<T> opt) {
		return literal(opt.name())
				.executes(ctx -> {
					feedback(ctx, opt.name() + " = §b" + opt.get().apply(MinerConfig.get()) + "§r (type §b"
							+ opt.usage().replace("<" + opt.range() + ">", "help") + "§r for details)");
					return 1;
				})
				.then(helpNode(() -> optionHelp(opt)))
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

	// ------------------------------------------------------------------------------------------
	// Help texts
	// ------------------------------------------------------------------------------------------

	private static final Map<String, List<String>> HELP = new LinkedHashMap<>();

	static {
		HELP.put("start", List.of(
				"§6start§r - start strip mining in the direction you are facing.",
				"§b/stripmine start§r - dig stairs to the targety setting (default -58), then mine until stopped",
				"§b/stripmine start <y>§r - dig stairs to height Y first",
				"§b/stripmine start here§r - mine at your current height (no stairs)",
				"Add a goal to stop by itself (also after a Y or §bhere§r): §btime§r, §bores§r, §bitems§r or §blength§r,",
				"e.g. §b/stripmine start time 30m§r, §b/stripmine start -58 ores 20§r.",
				"Type §b/stripmine start time help§r etc. for each goal."));
		HELP.put("time", List.of(
				"§6start time <duration>§r - stop after mining this long.",
				"Duration like §b30m§r, §b1h30m§r, §b90s§r, or just a number of minutes (§b45§r).",
				"Example: §b/stripmine start time 1h"));
		HELP.put("ores", List.of(
				"§6start ores <count>§r - stop after mining this many ore blocks (of the ores in /stripmine config ores).",
				"Example: §b/stripmine start ores 20"));
		HELP.put("items", List.of(
				"§6start items <count>§r - stop after picking up this many items from mined ores",
				"(e.g. diamonds, raw iron - counts Fortune drops too).",
				"Example: §b/stripmine start items 64"));
		HELP.put("length", List.of(
				"§6start length <blocks>§r - stop once the main tunnel is this long.",
				"Example: §b/stripmine start length 200"));
		HELP.put("stop", List.of("§6stop§r - stop mining right away. Your settings stay as they are."));
		HELP.put("pause", List.of(
				"§6pause§r - freeze the miner where it is and give you back control.",
				"§b/stripmine resume§r carries on from where it paused. Stand where it left you before resuming,",
				"otherwise it continues from wherever you are standing."));
		HELP.put("resume", List.of("§6resume§r - carry on after §b/stripmine pause§r."));
		HELP.put("status", List.of("§6status§r - show run time, tunnel length, ores and items mined, and what it is doing now."));
		HELP.put("config", List.of(
				"§6config§r - change how the miner works. §b/stripmine config§r lists everything.",
				"§b/stripmine config settings <name> <value>§r - tunnel shape: branchlength, branchspacing,"
						+ " torchinterval, maxlength, veinradius, targety, spiralstaircase",
				"§b/stripmine config <name> <value>§r - behaviour: clustersize, minearoundores, isolatelava, collectgravel,"
						+ " compressfullstacks, dropjunk, eatbelowfood, stopbelowhealth, keeptooldurability",
				"§b/stripmine config ores ...§r - which ores to look for",
				"Put §bhelp§r after any setting to see what it does, e.g. §b/stripmine config settings veinradius help"));
		HELP.put("settings", List.of(
				"§6config settings§r - the shape of the tunnel:",
				"§bbranchlength§r, §bbranchspacing§r, §btorchinterval§r, §bmaxlength§r, §bveinradius§r, §btargety§r,"
						+ " §bspiralstaircase",
				"Usage: §b/stripmine config settings <name> <value>§r, or §b... <name> help§r to learn more."));
		HELP.put("orelist", List.of(
				"§6config ores§r - which blocks the miner looks for (default: diamond ore and deepslate diamond ore).",
				"§b/stripmine config ores list§r - show the list",
				"§b/stripmine config ores add <block>§r - add one, e.g. minecraft:ancient_debris (tab-completes)",
				"§b/stripmine config ores remove <block>§r - remove one",
				"§b/stripmine config ores clear§r / §breset§r - empty the list / back to diamonds"));
		HELP.put("help", List.of(
				"§6help§r - type §bhelp§r after any command instead of a value to see how it works,",
				"e.g. §b/stripmine start help§r, §b/stripmine config settings veinradius help§r.",
				"§b/stripmine help <topic>§r works too."));
	}

	private static List<String> overview() {
		List<String> lines = new ArrayList<>();
		lines.add("§6Strip Miner v" + version() + "§r - commands (put §bhelp§r after any of them for details):");
		lines.add("§b/stripmine start [y|here] [time|ores|items|length <value>]§r - start mining");
		lines.add("§b/stripmine stop§r - stop");
		lines.add("§b/stripmine pause§r / §bresume§r - pause and carry on");
		lines.add("§b/stripmine status§r - progress");
		lines.add("§b/stripmine config§r - all settings (§bsettings§r for tunnel shape, §bores§r for the ore list)");
		lines.add("§b/stripmine help <topic>§r - topics: start, time, ores, items, length, stop, pause, resume, status, config,"
				+ " settings, orelist, or any setting name");
		return lines;
	}

	private static void sendLines(CommandContext<FabricClientCommandSource> ctx, List<String> lines) {
		for (int i = 0; i < lines.size(); i++) {
			ctx.getSource().sendFeedback(Component.literal(i == 0 ? "§6[StripMiner]§r " + lines.get(i) : "  " + lines.get(i)));
		}
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> helpNode(java.util.function.Supplier<List<String>> text) {
		return literal("help").executes(ctx -> {
			sendLines(ctx, text.get());
			return 1;
		});
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> helpNode(String topic) {
		return helpNode(() -> HELP.get(topic));
	}

	private static List<String> helpFor(String topic) {
		topic = topic.trim().toLowerCase(Locale.ROOT);
		if (HELP.containsKey(topic)) {
			return HELP.get(topic);
		}
		for (Option<?> opt : OPTIONS) {
			if (opt.name().equals(topic)) {
				return optionHelp(opt);
			}
		}
		return List.of("No help for '" + topic + "'. Try §b/stripmine help§r for the list of topics.");
	}

	// ------------------------------------------------------------------------------------------
	// Command tree
	// ------------------------------------------------------------------------------------------

	private static final SuggestionProvider<FabricClientCommandSource> ORE_SUGGESTIONS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(BuiltInRegistries.BLOCK.keySet().stream()
					.filter(id -> id.getPath().endsWith("_ore") || id.getPath().equals("ancient_debris"))
					.map(Identifier::toString), builder);

	private static final SuggestionProvider<FabricClientCommandSource> CONFIGURED_ORES = (ctx, builder) ->
			SharedSuggestionProvider.suggest(MinerConfig.get().ores, builder);

	private static final SuggestionProvider<FabricClientCommandSource> HELP_TOPICS = (ctx, builder) -> {
		List<String> topics = new ArrayList<>(HELP.keySet());
		OPTIONS.forEach(o -> topics.add(o.name()));
		return SharedSuggestionProvider.suggest(topics, builder);
	};

	private static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(mainNode("stripmine"));
		d.register(configNode());
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> mainNode(String name) {
		return literal(name)
				.executes(ctx -> {
					sendLines(ctx, overview());
					return 1;
				})
				.then(literal("help")
						.executes(ctx -> {
							sendLines(ctx, overview());
							return 1;
						})
						.then(argument("topic", StringArgumentType.greedyString()).suggests(HELP_TOPICS).executes(ctx -> {
							sendLines(ctx, helpFor(StringArgumentType.getString(ctx, "topic")));
							return 1;
						})))
				.then(withGoals(literal("start"), false)
						.executes(ctx -> {
							startMining(MinerConfig.get().targetY, null);
							return 1;
						})
						.then(helpNode("start"))
						.then(withGoals(literal("here"), false, true).executes(ctx -> {
							startMining(null, null);
							return 1;
						}))
						.then(withGoals(argument("y", IntegerArgumentType.integer(-63, 319)), true).executes(ctx -> {
							startMining(IntegerArgumentType.getInteger(ctx, "y"), null);
							return 1;
						})))
				.then(literal("stop")
						.executes(ctx -> {
							if (MinerRunner.isRunning()) {
								MinerRunner.stop("Stopped.");
							} else {
								feedback(ctx, "Not running.");
							}
							return 1;
						})
						.then(helpNode("stop")))
				.then(literal("pause")
						.executes(ctx -> {
							if (MinerRunner.pause()) {
								feedback(ctx, "Paused. §b/stripmine resume§r to carry on, §b/stripmine stop§r to stop.");
							} else {
								feedback(ctx, MinerRunner.isRunning() ? "Already paused." : "Not running.");
							}
							return 1;
						})
						.then(helpNode("pause")))
				.then(literal("resume")
						.executes(ctx -> {
							if (MinerRunner.resume()) {
								feedback(ctx, "Resumed.");
							} else {
								feedback(ctx, MinerRunner.isRunning() ? "Not paused." : "Not running.");
							}
							return 1;
						})
						.then(helpNode("resume")))
				.then(literal("status")
						.executes(ctx -> {
							StripMineJob job = MinerRunner.current();
							feedback(ctx, job == null ? "Not running." : job.status());
							return 1;
						})
						.then(helpNode("status")))
				.then(configNode());
	}

	/** Adds the goal sub-commands (time/ores/items/length) under a start node. */
	private static <T extends ArgumentBuilder<FabricClientCommandSource, T>> T withGoals(T node, boolean hasY) {
		return withGoals(node, hasY, false);
	}

	/**
	 * @param hasY  a Y level was typed before the goal
	 * @param here  "start here": mine at the current height (otherwise, without a Y, the targety setting is used)
	 */
	private static <T extends ArgumentBuilder<FabricClientCommandSource, T>> T withGoals(T node, boolean hasY, boolean here) {
		return node
				.then(literal("time")
						.then(helpNode("time"))
						.then(argument("duration", StringArgumentType.word()).executes(ctx -> {
							String text = StringArgumentType.getString(ctx, "duration");
							long ticks = Goal.parseDuration(text);
							if (ticks <= 0) {
								ctx.getSource().sendError(Component.literal("Can't read '" + text
										+ "' as a time. Use e.g. 30m, 1h30m, 90s or a number of minutes."));
								return 0;
							}
							startMining(y(ctx, hasY, here), new Goal(Goal.Type.TIME, ticks));
							return 1;
						})))
				.then(goalNode("ores", Goal.Type.ORES, "count", hasY, here))
				.then(goalNode("items", Goal.Type.ITEMS, "count", hasY, here))
				.then(goalNode("length", Goal.Type.LENGTH, "blocks", hasY, here));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> goalNode(String name, Goal.Type type, String arg,
			boolean hasY, boolean here) {
		return literal(name)
				.then(helpNode(name))
				.then(argument(arg, IntegerArgumentType.integer(1)).executes(ctx -> {
					startMining(y(ctx, hasY, here), new Goal(type, IntegerArgumentType.getInteger(ctx, arg)));
					return 1;
				}));
	}

	private static Integer y(CommandContext<FabricClientCommandSource> ctx, boolean hasY, boolean here) {
		if (hasY) {
			return IntegerArgumentType.getInteger(ctx, "y");
		}
		return here ? null : MinerConfig.get().targetY;
	}

	/** The settings command tree, used for /config and /stripmine config. */
	private static LiteralArgumentBuilder<FabricClientCommandSource> configNode() {
		LiteralArgumentBuilder<FabricClientCommandSource> config = literal("config").executes(ctx -> {
			MinerConfig c = MinerConfig.get();
			List<String> lines = new ArrayList<>();
			lines.add("Settings (put §bhelp§r after a name to learn what it does):");
			lines.add("§7Tunnel - /stripmine config settings <name> <value>:");
			for (Option<?> opt : OPTIONS) {
				if (opt.tunnel()) {
					lines.add(" §b" + opt.name() + "§r = " + opt.get().apply(c));
				}
			}
			lines.add("§7Behaviour - /stripmine config <name> <value>:");
			for (Option<?> opt : OPTIONS) {
				if (!opt.tunnel()) {
					lines.add(" §b" + opt.name() + "§r = " + opt.get().apply(c));
				}
			}
			lines.add("§7Ores - /stripmine config ores add|remove|list|clear|reset:");
			lines.add(" " + String.join(", ", c.ores));
			sendLines(ctx, lines);
			return 1;
		}).then(helpNode("config"));

		LiteralArgumentBuilder<FabricClientCommandSource> settings = literal("settings").executes(ctx -> {
			MinerConfig c = MinerConfig.get();
			List<String> lines = new ArrayList<>();
			lines.add("Tunnel settings (change with §b/stripmine config settings <name> <value>§r):");
			for (Option<?> opt : OPTIONS) {
				if (opt.tunnel()) {
					lines.add(" §b" + opt.name() + "§r = " + opt.get().apply(c));
				}
			}
			sendLines(ctx, lines);
			return 1;
		}).then(helpNode("settings"));
		for (Option<?> opt : OPTIONS) {
			if (opt.tunnel()) {
				settings.then(optionNode(opt));
			} else {
				config.then(optionNode(opt));
			}
		}
		config.then(settings);

		config.then(literal("ores")
				.executes(ctx -> listOres(ctx))
				.then(helpNode("orelist"))
				.then(literal("list").executes(ctx -> listOres(ctx)).then(helpNode("orelist")))
				.then(literal("add")
						.then(helpNode("orelist"))
						.then(argument("block", StringArgumentType.greedyString()).suggests(ORE_SUGGESTIONS).executes(ctx -> {
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
				.then(literal("remove")
						.then(helpNode("orelist"))
						.then(argument("block", StringArgumentType.greedyString()).suggests(CONFIGURED_ORES).executes(ctx -> {
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
				}).then(helpNode("orelist")))
				.then(literal("reset").executes(ctx -> {
					MinerConfig c = MinerConfig.get();
					c.ores = new ArrayList<>(MinerConfig.DEFAULT_ORES);
					c.save();
					return listOres(ctx);
				}).then(helpNode("orelist"))));
		return config;
	}

	private static String normalize(String id) {
		id = id.trim().toLowerCase(Locale.ROOT);
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static int listOres(CommandContext<FabricClientCommandSource> ctx) {
		feedback(ctx, "Target ores: " + (MinerConfig.get().ores.isEmpty() ? "(none)" : String.join(", ", MinerConfig.get().ores)));
		return 1;
	}

	private static void feedback(CommandContext<FabricClientCommandSource> ctx, String text) {
		ctx.getSource().sendFeedback(Component.literal("§6[StripMiner]§r " + text));
	}
}
