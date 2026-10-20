package com.redtact.live.mixin;

import com.redtact.live.sync.AutoSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Level.setBlock はブロック状態が実際に変わったときだけ setBlocksDirty を呼ぶ (フラグに関係なく)。
 * クライアントではサーバからのブロック更新・チャンク内の一括更新・予測の確定がここを通る。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void redtactLive$onBlocksDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		AutoSync.onBlockChanged((ClientLevel) (Object) this, pos);
	}
}
