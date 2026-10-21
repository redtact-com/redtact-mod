package com.redtact.live.preview.mesh;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
//? if >=26.1 {
import com.redtact.live.mixin.BlockModelRenderStateAccessor;
import org.joml.Matrix4fc;
//?}
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if >=26.1 {
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
//?} else {
/*import com.mojang.math.Axis;
import com.redtact.live.mixin.SpecialBlockModelRendererAccessor;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.properties.RotationSegment;
*///?}
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 組み立てた形 ({@link CircuitMesh}) に入らないブロックを、止まったプレビューと同じゲームのモデルで描き足す (#102)。
 * ゲームの地形ではブロックエンティティが描くもの (チェスト・シュルカーボックス・頭・旗・ベルの本体・エンチャントテーブルの本など)。
 * ブロックの状態ごとに 1 度だけ見分けて覚える。描画のスレッドだけで使う。
 */
public final class SpecialBlocks {
	/** 見分けた結果。 */
	public enum Kind {
		/** 形に入る (描き足さない)。 */
		NONE,
		/** 形に入らない (モデルを全部描く)。 */
		WHOLE,
		/** 形に入るが、特別な描き方の分 (ベルの本体など) が別にある (その分だけ描く)。 */
		EXTRA
	}

	private static final int FULL_BRIGHT = 15728880;
	//? if >=26.1 {
	private static final BlockDisplayContext CONTEXT = BlockDisplayContext.create();
	private final BlockModelResolver resolver = new BlockModelResolver(Minecraft.getInstance().getModelManager());
	private final BlockModelRenderState state = new BlockModelRenderState();
	//?}
	private final Map<BlockState, Kind> kinds = new IdentityHashMap<>();

	/** そのブロックを描き足すか。 */
	public Kind kind(BlockState s) {
		Kind k = kinds.get(s);

		if (k == null) {
			k = classify(s);
			kinds.put(s, k);
		}

		return k;
	}

	private Kind classify(BlockState s) {
		if (s.isAir() || s.getBlock() instanceof LiquidBlock) {
			return Kind.NONE; // 水・溶岩は形の組み立てで描く
		}

		//? if >=26.1 {
		resolver.update(state, s, CONTEXT);

		if (state.isEmpty()) {
			return Kind.NONE;
		}

		boolean special = ((BlockModelRenderStateAccessor) state).redtactLive$specialRenderer() != null;

		if (s.getRenderShape() == RenderShape.MODEL) {
			return special ? Kind.EXTRA : Kind.NONE;
		}

		return Kind.WHOLE;
		//?} else {
		/*// 1.21.x: チェスト・頭・旗などは形が空 (組み立てた形には何も入らない) で、見た目はすべてゲームの表の特別な描き方
		return specialRenderers().containsKey(s.getBlock()) ? Kind.EXTRA : Kind.NONE;
		*///?}
	}

	/** パレットのうち描き足すものの集まり (形を組み立てる前に、描画のスレッドで)。 */
	public Set<BlockState> pick(@Nullable BlockState[] palette) {
		Set<BlockState> out = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

		for (BlockState s : palette) {
			if (s != null && kind(s) != Kind.NONE) {
				out.add(s);
			}
		}

		return out;
	}

	/** 1 つ描く。pose はそのブロックの角 (回路の座標の x, y, z) に置いてあること。明るさは最大。 */
	public void submit(BlockState s, PoseStack pose, SubmitNodeCollector out) {
		Kind k = kind(s);

		if (k == Kind.NONE) {
			return;
		}

		//? if <26.1 {
		/*pose.pushPose();
		orient(s, pose);
		Minecraft.getInstance().getModelManager().specialBlockModelRenderer()
				.renderByBlock(s.getBlock(), ItemDisplayContext.NONE, pose, out, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
		*///?} else {
		resolver.update(state, s, CONTEXT);

		if (k == Kind.WHOLE) {
			state.submit(pose, out, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			return;
		}

		// 特別な描き方の分だけ (ふつうの面は形に入っている。BlockModelRenderState#submit の後半と同じ)
		BlockModelRenderStateAccessor a = (BlockModelRenderStateAccessor) state;
		SpecialModelRenderer<?> renderer = a.redtactLive$specialRenderer();

		if (renderer == null) {
			return;
		}

		Matrix4fc t = a.redtactLive$specialTransformation();
		pose.pushPose();

		if (t != null) {
			pose.mulPose(t);
		}

		@SuppressWarnings("unchecked")
		SpecialModelRenderer<Object> r = (SpecialModelRenderer<Object>) renderer;
		r.submit(null, pose, out, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false, 0);
		pose.popPose();
		//?}
	}

	//? if <26.1 {
	/*private static Map<Block, ?> specialRenderers() {
		return ((SpecialBlockModelRendererAccessor) Minecraft.getInstance().getModelManager().specialBlockModelRenderer()).redtactLive$renderers();
	}

	// 1.21.x の特別な描き方は向きを持たない (チェストは南向き・頭は 180°) ので、ブロックの向きにする (ゲームの ChestRenderer・
	// SkullBlockRenderer と同じ回し方。壁の頭は壁へ寄せる)。pose はブロックの角に置いてあること。ほかのブロックは何もしない
	public static void orient(BlockState s, PoseStack pose) {
		Block b = s.getBlock();

		if (b instanceof AbstractChestBlock<?> && s.hasProperty(HorizontalDirectionalBlock.FACING)) {
			pose.rotateAround(Axis.YP.rotationDegrees(-s.getValue(HorizontalDirectionalBlock.FACING).toYRot()), 0.5F, 0.5F, 0.5F);
		} else if (b instanceof SkullBlock) {
			float r = RotationSegment.convertToDegrees(s.getValue(SkullBlock.ROTATION));
			pose.rotateAround(Axis.YP.rotationDegrees(180.0F - r), 0.5F, 0.0F, 0.5F);
		} else if (b instanceof WallSkullBlock) {
			Direction d = s.getValue(WallSkullBlock.FACING);
			pose.translate(-d.getStepX() * 0.25F, 0.25F, -d.getStepZ() * 0.25F);
			float r = RotationSegment.convertToDegrees(RotationSegment.convertToSegment(d.getOpposite()));
			pose.rotateAround(Axis.YP.rotationDegrees(180.0F - r), 0.5F, 0.0F, 0.5F);
		}
	}
	*///?}
}
