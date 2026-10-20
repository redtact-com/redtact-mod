package com.redtact.live.structure.bedrock;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.redtact.live.i18n.Tr;
import com.redtact.live.structure.StructureException;
import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;

/**
 * 統合版の NBT (リトルエンディアン・非圧縮) を読む (#70)。mc-nbt-converter の Go 版 (gophertunnel) が any に読むのと同じ形にする:
 * Compound → Map、List → List (Byte・Int・Long の List だけは byte[]・{@link IntList}・long[])、ByteArray → byte[]、
 * IntArray → int[]、LongArray → long[]、数は Byte・Short・Integer・Long・Float・Double、String → String (ふつうの UTF-8)。
 *
 * <p>MC の NbtIo を使わないのは、配列の長さを残りのバイト数で確かめずに確保する (数百バイトの細工したファイルで上限いっぱいまで
 * 確保する) のと、壊れたファイルで CrashReport を作るため。ここでは長さはどれも残りのバイト数で確かめてから配列を作り、
 * 持つ量と深さは NbtAccounter で抑える (数え方は NbtIo と同じ)。要らない所 ({@link Keep}) は確かめながら読み飛ばして持たない。
 */
final class LittleEndianNbt {
	static final int END = 0;
	static final int BYTE = 1;
	static final int SHORT = 2;
	static final int INT = 3;
	static final int LONG = 4;
	static final int FLOAT = 5;
	static final int DOUBLE = 6;
	static final int BYTE_ARRAY = 7;
	static final int STRING = 8;
	static final int LIST = 9;
	static final int COMPOUND = 10;
	static final int INT_ARRAY = 11;
	static final int LONG_ARRAY = 12;

	/** List[Int]。IntArray (int[]) と分けて持つ (Go は size を List[Int] のときしか読まない)。 */
	record IntList(int[] values) {
	}

	/** Compound のうち持つキーと、その下で持つもの。keys が null なら全部持つ。 */
	record Keep(@Nullable Map<String, Keep> keys) {
		static final Keep ALL = new Keep(null);
		/** .mcstructure の structure の中で使うもの (ブロックエンティティとエンティティは読み飛ばす)。 */
		static final Keep STRUCTURE = new Keep(Map.of("block_indices", ALL, "palette",
				new Keep(Map.of("default", new Keep(Map.of("block_palette", ALL))))));
		/** .mcstructure のルートで使うもの。 */
		static final Keep MCSTRUCTURE = new Keep(Map.of("size", ALL, "compression", ALL, "structure", STRUCTURE));

		/** そのキーの下で持つもの (持たないなら null)。 */
		@Nullable Keep child(String key) {
			return keys == null ? ALL : keys.get(key);
		}
	}

	private final byte[] data;
	private final ByteBuffer buf;
	private final NbtAccounter accounter;

	private LittleEndianNbt(byte[] data, NbtAccounter accounter) {
		this.data = data;
		this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		this.accounter = accounter;
	}

	/** 名前付きの Compound をルートとして読む (ルートの名前は使わない。後ろに余ったバイトは見ない)。 */
	static Map<String, Object> read(byte[] data, NbtAccounter accounter, Keep keep) throws StructureException {
		LittleEndianNbt r = new LittleEndianNbt(data, accounter);

		try {
			int type = r.u8();

			if (type != COMPOUND) {
				boolean gzip = type == 0x1F && data.length > 1 && (data[1] & 0xFF) == 0x8B;
				throw broken(gzip ? Tr.t("gzip で圧縮されています。Java 版の .nbt かもしれません") : Tr.t("統合版の構造ファイルではありません"));
			}

			r.skipString();
			return r.compound(keep);
		} catch (BufferUnderflowException | IndexOutOfBoundsException e) {
			throw broken(Tr.t("途中で切れています"));
		} catch (NbtAccounterException e) {
			throw new StructureException(Tr.t(".mcstructure の中身が大きすぎるので読みません"));
		}
	}

	static StructureException broken(String why) {
		return new StructureException(Tr.f(".mcstructure を読めません ({0})", why));
	}

	private Object payload(int type, Keep keep) throws StructureException {
		switch (type) {
			case BYTE -> {
				accounter.accountBytes(9);
				return buf.get();
			}
			case SHORT -> {
				accounter.accountBytes(10);
				return buf.getShort();
			}
			case INT -> {
				accounter.accountBytes(12);
				return buf.getInt();
			}
			case LONG -> {
				accounter.accountBytes(16);
				return buf.getLong();
			}
			case FLOAT -> {
				accounter.accountBytes(12);
				return buf.getFloat();
			}
			case DOUBLE -> {
				accounter.accountBytes(16);
				return buf.getDouble();
			}
			case BYTE_ARRAY -> {
				return bytes(length(1));
			}
			case STRING -> {
				return string(36);
			}
			case LIST -> {
				return list(keep);
			}
			case COMPOUND -> {
				return compound(keep);
			}
			case INT_ARRAY -> {
				return ints(length(4));
			}
			case LONG_ARRAY -> {
				return longs(length(8));
			}
			default -> throw unknownType(type);
		}
	}

	private Map<String, Object> compound(Keep keep) throws StructureException {
		accounter.pushDepth();

		try {
			accounter.accountBytes(48);
			Map<String, Object> m = new LinkedHashMap<>();

			for (int type = u8(); type != END; type = u8()) {
				String key = string(28);
				Keep k = keep.child(key);

				if (k == null) {
					skip(type);
				} else if (m.put(key, payload(type, k)) == null) { // 同じキーが 2 度あれば後の方 (Go の map と同じ)
					accounter.accountBytes(36);
				}
			}

			return m;
		} finally {
			accounter.popDepth();
		}
	}

	private Object list(Keep keep) throws StructureException {
		accounter.pushDepth();

		try {
			accounter.accountBytes(36);
			int elem = u8();
			int n = count(elem);

			switch (elem) {
				case BYTE -> {
					return bytes(n);
				}
				case INT -> {
					return new IntList(ints(n));
				}
				case LONG -> {
					return longs(n);
				}
				default -> {
					accounter.accountBytes(4, n);
					List<Object> out = new ArrayList<>(n);

					for (int i = 0; i < n; i++) {
						out.add(payload(elem, keep));
					}

					return out;
				}
			}
		} finally {
			accounter.popDepth();
		}
	}

	/** 確かめながら読み飛ばす (何も持たない)。どの要素も 1 バイトは進むので、時間はバイト数で抑えられる。 */
	private void skip(int type) throws StructureException {
		switch (type) {
			case BYTE -> skipBytes(1);
			case SHORT -> skipBytes(2);
			case INT, FLOAT -> skipBytes(4);
			case LONG, DOUBLE -> skipBytes(8);
			case BYTE_ARRAY -> skipBytes(length(1));
			case STRING -> skipString();
			case INT_ARRAY -> skipBytes(length(4) * 4);
			case LONG_ARRAY -> skipBytes(length(8) * 8);
			case LIST -> {
				accounter.pushDepth();

				try {
					int elem = u8();
					int n = count(elem);

					for (int i = 0; i < n; i++) {
						skip(elem);
					}
				} finally {
					accounter.popDepth();
				}
			}
			case COMPOUND -> {
				accounter.pushDepth();

				try {
					for (int t = u8(); t != END; t = u8()) {
						skipString();
						skip(t);
					}
				} finally {
					accounter.popDepth();
				}
			}
			default -> throw unknownType(type);
		}
	}

	/** List の要素の数を読み、残りのバイト数に収まるか確かめる (要素の型が正しいかも見る)。 */
	private int count(int elem) throws StructureException {
		int n = buf.getInt();
		int min = switch (elem) {
			case END -> 0;
			case BYTE, COMPOUND -> 1;
			case SHORT, STRING -> 2;
			case INT, FLOAT, BYTE_ARRAY, INT_ARRAY, LONG_ARRAY -> 4;
			case LIST -> 5;
			case LONG, DOUBLE -> 8;
			default -> throw unknownType(elem);
		};

		if (n < 0) {
			throw broken(Tr.t("List の長さが負です"));
		}

		if (min == 0 ? n > 0 : n > buf.remaining() / min) {
			throw broken(min == 0 ? Tr.t("型の無い List に中身があります") : Tr.t("途中で切れています"));
		}

		return n;
	}

	/** 配列の長さ (要素 elemSize バイト) を読み、残りのバイト数に収まるか確かめる。 */
	private int length(int elemSize) throws StructureException {
		int n = buf.getInt();

		if (n < 0 || n > buf.remaining() / elemSize) {
			throw broken(Tr.t("途中で切れています"));
		}

		return n;
	}

	private byte[] bytes(int n) {
		accounter.accountBytes(24 + (long) n);
		byte[] out = new byte[n];
		buf.get(out);
		return out;
	}

	private int[] ints(int n) {
		accounter.accountBytes(24 + 4L * n);
		int[] out = new int[n];
		buf.slice(buf.position(), n * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
		buf.position(buf.position() + n * 4);
		return out;
	}

	private long[] longs(int n) {
		accounter.accountBytes(24 + 8L * n);
		long[] out = new long[n];
		buf.slice(buf.position(), n * 8).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(out);
		buf.position(buf.position() + n * 8);
		return out;
	}

	/** 長さ (u16) + UTF-8。selfBytes は NbtIo の数え方 (値は 36、キーは 28)。 */
	private String string(long selfBytes) throws StructureException {
		int n = buf.getShort() & 0xFFFF;

		if (n > buf.remaining()) {
			throw broken(Tr.t("途中で切れています"));
		}

		accounter.accountBytes(selfBytes + 2L * n);
		String s = new String(data, buf.position(), n, StandardCharsets.UTF_8);
		buf.position(buf.position() + n);
		return s;
	}

	private void skipString() throws StructureException {
		skipBytes(buf.getShort() & 0xFFFF);
	}

	private void skipBytes(int n) throws StructureException {
		if (n > buf.remaining()) {
			throw broken(Tr.t("途中で切れています"));
		}

		buf.position(buf.position() + n);
	}

	private int u8() {
		return buf.get() & 0xFF;
	}

	private static StructureException unknownType(int type) {
		return broken(Tr.f("知らない型 ({0}) があります", type));
	}
}
