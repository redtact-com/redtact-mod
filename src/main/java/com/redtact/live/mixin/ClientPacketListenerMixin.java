package com.redtact.live.mixin;

import com.redtact.live.sync.AutoSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;

/** ブロックエンティティのデータ更新 (看板の文字など。マルチプレイで届くもの)。 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleBlockEntityData", at = @At("TAIL"))
	private void redtactLive$onBlockEntityData(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
		AutoSync.onClientBlockEntityData(packet.getPos());
	}
}
