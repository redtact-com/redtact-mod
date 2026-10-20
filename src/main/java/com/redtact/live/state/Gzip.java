package com.redtact.live.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.redtact.live.i18n.Tr;

public final class Gzip {
	private Gzip() {
	}

	public static byte[] compress(byte[] data) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));

			try (GZIPOutputStream gz = new GZIPOutputStream(out, 64 * 1024)) {
				gz.write(data);
			}

			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** gzip を戻す。戻したあとが maxBytes を超えたら IOException (サーバの応答を信じすぎない)。 */
	public static byte[] decompress(byte[] data, long maxBytes) throws IOException {
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data), 64 * 1024)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 4));
			byte[] buf = new byte[64 * 1024];
			long total = 0;

			for (int n; (n = in.read(buf)) > 0;) {
				total += n;

				if (total > maxBytes) {
					throw new IOException(Tr.n("展開後が {0} バイトを超えました", maxBytes));
				}

				out.write(buf, 0, n);
			}

			return out.toByteArray();
		}
	}
}
