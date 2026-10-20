package com.redtact.live.preview.mesh;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 頂点の位置に原点を足して渡す (#102)。ゲームの液体の描き方 ({@code FluidRenderer}) はチャンクの区画の中の位置
 * ({@code x & 15}) で書くので、16 マスより大きい回路では区画の原点を足さないと水がずれる。
 */
final class OffsetVertexConsumer implements VertexConsumer {
	private final VertexConsumer delegate;
	private float ox;
	private float oy;
	private float oz;

	OffsetVertexConsumer(VertexConsumer delegate) {
		this.delegate = delegate;
	}

	/** いま描くブロックの区画の原点 (区画の中の位置に足す量)。 */
	void offset(float x, float y, float z) {
		ox = x;
		oy = y;
		oz = z;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		delegate.addVertex(x + ox, y + oy, z + oz);
		return this;
	}

	@Override
	public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
		delegate.addVertex(x + ox, y + oy, z + oz, color, u, v, overlay, light, nx, ny, nz);
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		delegate.setColor(r, g, b, a);
		return this;
	}

	@Override
	public VertexConsumer setColor(int color) {
		delegate.setColor(color);
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		delegate.setUv(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		delegate.setUv1(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		delegate.setUv2(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv3(float u, float v) {
		delegate.setUv3(u, v);
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		delegate.setNormal(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setLineWidth(float width) {
		delegate.setLineWidth(width);
		return this;
	}
}
