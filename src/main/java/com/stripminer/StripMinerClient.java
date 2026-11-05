package com.stripminer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.function.BiConsumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class StripMinerClient implements ClientModInitializer {
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		MinerConfig.get();
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.stripminer.toggle",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.stripminer"));

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
					.map(ResourceLocation::toString), builder);

	private static final SuggestionProvider<FabricClientCommandSource> CONFIGURED_ORES = (ctx, builder) ->
			SharedSuggestionProvider.suggest(MinerConfig.get().ores, builder);

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
					MinerConfig c = MinerConfig.get();
					feedback(ctx, "Ores: " + c.ores + " | branch " + c.branchLength + " | spacing "
							+ c.branchSpacing + " | torch every " + c.torchInterval + " | max length "
							+ (c.maxTunnelLength == 0 ? "unlimited" : c.maxTunnelLength) + " | vein radius "
							+ c.veinRadius + " | dropJunk " + c.dropJunk + " | compressFullStacks " + c.compressFullStacks
							+ " | mineAroundOres " + c.mineAroundOres + " | isolateLava " + c.isolateLava);
					return 1;
				}))
				.then(literal("ores")
						.executes(ctx -> listOres(ctx))
						.then(literal("list").executes(ctx -> listOres(ctx)))
						.then(literal("add").then(argument("block", StringArgumentType.greedyString())
								.suggests(ORE_SUGGESTIONS).executes(ctx -> {
									String id = normalize(StringArgumentType.getString(ctx, "block"));
									ResourceLocation rl = ResourceLocation.tryParse(id);
									if (rl == null || BuiltInRegistries.BLOCK.getOptional(rl).isEmpty()) {
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
						})))
				.then(literal("set")
						.then(intSetting("branchLength", 1, 64, (c, v) -> c.branchLength = v))
						.then(intSetting("branchSpacing", 2, 64, (c, v) -> c.branchSpacing = v))
						.then(intSetting("torchInterval", 0, 256, (c, v) -> c.torchInterval = v))
						.then(intSetting("maxLength", 0, 100000, (c, v) -> c.maxTunnelLength = v))
						.then(intSetting("veinRadius", 1, 16, (c, v) -> c.veinRadius = v))
						.then(intSetting("eatBelowFood", 0, 19, (c, v) -> c.eatBelowFood = v))
						.then(intSetting("stopBelowHealth", 0, 19, (c, v) -> c.stopBelowHealth = v))
						.then(boolSetting("dropJunk", (c, v) -> c.dropJunk = v))
						.then(boolSetting("compressFullStacks", (c, v) -> c.compressFullStacks = v))
						.then(boolSetting("mineAroundOres", (c, v) -> c.mineAroundOres = v))
						.then(boolSetting("isolateLava", (c, v) -> c.isolateLava = v))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> intSetting(
			String name, int min, int max, BiConsumer<MinerConfig, Integer> setter) {
		return literal(name).then(argument("value", IntegerArgumentType.integer(min, max)).executes(ctx -> {
			int v = IntegerArgumentType.getInteger(ctx, "value");
			MinerConfig c = MinerConfig.get();
			setter.accept(c, v);
			c.save();
			feedback(ctx, name + " set to " + v + (MinerRunner.isRunning() ? " (takes effect on the next start)" : "") + ".");
			return 1;
		}));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> boolSetting(
			String name, BiConsumer<MinerConfig, Boolean> setter) {
		return literal(name).then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
			boolean v = BoolArgumentType.getBool(ctx, "value");
			MinerConfig c = MinerConfig.get();
			setter.accept(c, v);
			c.save();
			feedback(ctx, name + " set to " + v + (MinerRunner.isRunning() ? " (takes effect on the next start)" : "") + ".");
			return 1;
		}));
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
				"§b/stripmine ores add|remove|list|clear|reset§r - which ores to mine",
				"§b/stripmine set <setting> <value>§r - branchLength, branchSpacing, torchInterval, maxLength, veinRadius, eatBelowFood, stopBelowHealth, dropJunk, compressFullStacks, mineAroundOres, isolateLava"}) {
			ctx.getSource().sendFeedback(Component.literal(line));
		}
		return 1;
	}
}
