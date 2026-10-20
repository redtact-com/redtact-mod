package com.redtact.live.mixin;

import com.redtact.live.sync.AutoSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;

/** コンパレーターの出力値は setChanged を通らずに変わる (ブロック状態も変わらないことがある)。 */
@Mixin(ComparatorBlockEntity.class)
public abstract class ComparatorBlockEntityMixin {
	@Shadow
	private int output;

	@Inject(method = "setOutputSignal", at = @At("HEAD"))
	private void redtactLive$onSetOutputSignal(int value, CallbackInfo ci) {
		if (value != output) {
			BlockEntity self = (BlockEntity) (Object) this;
			Level level = self.getLevel();

			if (level != null && !level.isClientSide()) {
				AutoSync.onServerBlockEntityChanged(level, self.getBlockPos());
			}
		}
	}
}
