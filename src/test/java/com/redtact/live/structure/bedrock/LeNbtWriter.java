package com.redtact.live.structure.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 試験用: {@link LittleEndianNbt} の形 (Map・List・IntList・配列・数・字) をリトルエンディアンの NBT に書く。
 * byte[] は ByteArray、空の List は型の無い List (End) にする。細工したバイト列を手で組むときにも使う。
 */
final class LeNbtWriter {
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	/** 名前の無い Compound をルートにして書く。 */
	static byte[] write(Map<String, ?> root) {
		return new LeNbtWriter().u8(LittleEndianNbt.COMPOUND).string("").payload(root).bytes();
	}

	static int type(Object v) {
		return switch (v) {
			case Byte b -> LittleEndianNbt.BYTE;
			case Short s -> LittleEndianNbt.SHORT;
			case Integer i -> LittleEndianNbt.INT;
			case Long l -> LittleEndianNbt.LONG;
			case Float f -> LittleEndianNbt.FLOAT;
			case Double d -> LittleEndianNbt.DOUBLE;
			case byte[] a -> LittleEndianNbt.BYTE_ARRAY;
			case String s -> LittleEndianNbt.STRING;
			case List<?> l -> LittleEndianNbt.LIST;
			case LittleEndianNbt.IntList l -> LittleEndianNbt.LIST;
			case Map<?, ?> m -> LittleEndianNbt.COMPOUND;
			case int[] a -> LittleEndianNbt.INT_ARRAY;
			case long[] a -> LittleEndianNbt.LONG_ARRAY;
			default -> throw new IllegalArgumentException(v.getClass().getName());
		};
	}

	LeNbtWriter payload(Object v) {
		switch (v) {
			case Byte b -> u8(b);
			case Short s -> put(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s));
			case Integer i -> i32(i);
			case Long l -> i64(l);
			case Float f -> put(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f));
			case Double d -> put(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(d));
			case byte[] a -> i32(a.length).raw(a);
			case String s -> string(s);
			case LittleEndianNbt.IntList l -> u8(LittleEndianNbt.INT).payload(l.values());
			case List<?> l -> {
				u8(l.isEmpty() ? LittleEndianNbt.END : type(l.get(0))).i32(l.size());
				l.forEach(this::payload);
			}
			case Map<?, ?> m -> {
				m.forEach((k, e) -> u8(type(e)).string((String) k).payload(e));
				u8(LittleEndianNbt.END);
			}
			case int[] a -> {
				i32(a.length);

				for (int x : a) {
					i32(x);
				}
			}
			case long[] a -> {
				i32(a.length);

				for (long x : a) {
					i64(x);
				}
			}
			default -> throw new IllegalArgumentException(v.getClass().getName());
		}

		return this;
	}

	LeNbtWriter string(String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		return put(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) b.length)).raw(b);
	}

	LeNbtWriter u8(int b) {
		out.write(b);
		return this;
	}

	LeNbtWriter i32(int v) {
		return put(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v));
	}

	LeNbtWriter i64(long v) {
		return put(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v));
	}

	LeNbtWriter raw(byte[] b) {
		out.writeBytes(b);
		return this;
	}

	byte[] bytes() {
		return out.toByteArray();
	}

	private LeNbtWriter put(ByteBuffer b) {
		return raw(b.array());
	}
}
