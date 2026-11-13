package com.stripminer.mixin;

import com.stripminer.MinerRunner;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla calls continueAttack(false) every tick when the attack button is not held, which
 * aborts any block breaking in progress. While the miner is running it controls block breaking
 * itself, so the vanilla handling is skipped.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void stripminer$skipVanillaAttack(boolean leftClick, CallbackInfo ci) {
		if (MinerRunner.isRunning() && !MinerRunner.isPaused()) {
			ci.cancel();
		}
	}
}
