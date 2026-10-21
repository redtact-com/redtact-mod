package com.redtact.live.ui;

import java.nio.ByteBuffer;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
//? if >=26.3 {
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDL_ClipboardCleanupCallback;
import org.lwjgl.sdl.SDL_ClipboardDataCallback;
//?}
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 絵 (PNG) をクリップボードに写す (#102 の撮影)。ゲームの窓と同じ SDL3 で写す (マイクラは AWT を使えない設定で動くため)。
 * SDL 3.4 は Windows では PNG の形式 (アプリが読む "PNG")、Linux と macOS では image/png で置く。
 *
 * <p>写した中身は、ほかのものが写されるまで SDL が取りにくるので、それまで手元に持つ (描画のスレッドだけで使う)。
 *
 * <p>26.2 まで (GLFW) は絵をクリップボードに置く口が無いので、写さずに false を返す (撮影はファイルに保存するだけ。#112)。
 */
public final class ImageClipboard {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final String PNG = "image/png";

	private ImageClipboard() {
	}

	//? if >=26.3 {
	// いまクリップボードに置いている中身 (無ければ null)
	private static @Nullable Held held;

	// SDL に渡した中身とその呼び口。呼び口は SDL が手放してから (次に写したとき) 片付ける
	private static final class Held {
		final SDL_ClipboardDataCallback data;
		final SDL_ClipboardCleanupCallback cleanup;
		@Nullable ByteBuffer bytes;

		Held(ByteBuffer bytes) {
			this.bytes = bytes;
			this.data = SDL_ClipboardDataCallback.create((userdata, mimeType, size) -> {
				ByteBuffer b = this.bytes;

				if (b == null) {
					MemoryUtil.memPutAddress(size, 0L);
					return MemoryUtil.NULL;
				}

				MemoryUtil.memPutAddress(size, b.remaining());
				return MemoryUtil.memAddress(b);
			});
			this.cleanup = SDL_ClipboardCleanupCallback.create(userdata -> releaseBytes());
		}

		// 中身を捨てる (SDL が手放した・写せなかった)
		void releaseBytes() {
			ByteBuffer b = bytes;
			bytes = null;

			if (b != null) {
				MemoryUtil.memFree(b);
			}
		}

		void free() {
			releaseBytes();
			data.free();
			cleanup.free();
		}
	}
	//?}

	/** PNG のバイト列を写す (描画のスレッドから)。写せたら true (写せなければ理由をログに出して false)。 */
	public static boolean copyPng(byte[] png) {
		//? if >=26.3 {
		ByteBuffer bytes = MemoryUtil.memAlloc(png.length);
		bytes.put(png).flip();
		Held next = new Held(bytes);
		boolean ok;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer types = stack.pointers(stack.UTF8(PNG));
			// 前に置いた中身は、ここで SDL が片付けの呼び口を呼んで手放す (写せても写せなくても)
			ok = SDLClipboard.SDL_SetClipboardData(next.data, next.cleanup, 0L, types);
		} catch (RuntimeException | LinkageError e) {
			// SDL に渡る前に失敗した (前の中身は SDL が持ったまま)
			LOGGER.warn("[redtact-live] 絵をクリップボードに写せません", e);
			next.free();
			return false;
		}

		Held previous = held;
		held = null;

		if (previous != null) {
			previous.free(); // SDL はもう前の呼び口を使わない
		}

		if (ok) {
			held = next;
			return true;
		}

		LOGGER.warn("[redtact-live] 絵をクリップボードに写せません: {}", SDLError.SDL_GetError());

		try {
			// 写せなくても SDL は呼び口を持ったままなので、片付けさせてから捨てる
			SDLClipboard.SDL_ClearClipboardData();
		} catch (RuntimeException | LinkageError e) {
			LOGGER.warn("[redtact-live] クリップボードを片付けられません", e);
			return false; // 呼び口は捨てない (SDL がまだ呼ぶかもしれない)
		}

		next.free();
		return false;
		//?} else
		/*return false;*/
	}

	/** クリップボードに置いてある PNG (テスト用。無ければ null)。 */
	public static byte @Nullable [] pastePng() {
		//? if >=26.3 {
		if (!SDLClipboard.SDL_HasClipboardData(PNG)) {
			return null;
		}

		ByteBuffer b = SDLClipboard.SDL_GetClipboardData(PNG);

		if (b == null) {
			return null;
		}

		try {
			byte[] out = new byte[b.remaining()];
			b.duplicate().get(out); // b の位置は動かさない (SDL_free には先頭を渡す)
			return out;
		} finally {
			SDLStdinc.SDL_free(b);
		}
		//?} else
		/*return null;*/
	}
}
