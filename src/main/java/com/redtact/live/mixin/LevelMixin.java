package com.redtact.live.mixin;

import com.redtact.live.sync.AutoSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** BlockEntity.setChanged → Level.blockEntityChanged。統合サーバ側のチェストの中身などの変化を拾う。 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "blockEntityChanged", at = @At("HEAD"))
	private void redtactLive$onBlockEntityChanged(BlockPos pos, CallbackInfo ci) {
		Level level = (Level) (Object) this;

		if (!level.isClientSide()) {
			AutoSync.onServerBlockEntityChanged(level, pos);
		}
	}
}
