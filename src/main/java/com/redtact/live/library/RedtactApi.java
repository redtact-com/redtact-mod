package com.redtact.live.library;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * redtact の公開 API (未ログインで読める回路の詳細) から、保管庫の表示名とファイルの ID を取る (#46)。
 * 取れなくても保存はする (表示名はファイル名から作る) ので、失敗は null で返す。
 */
public final class RedtactApi {
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private final HttpClient http;

	/** 回路の題と、添付のファイル。 */
	public record Detail(String title, List<FileInfo> files) {
		/** redtact の保存先での名前 (期限付き URL の最後) が一致するファイル。 */
		public @Nullable FileInfo fileByStoredName(String storedFileName) {
			for (FileInfo f : files) {
				if (storedFileName.equals(f.storedFileName())) {
					return f;
				}
			}

			return null;
		}
	}

	/** fileId: file_id、storedFileName: file_name (保存先での名前)、name: 利用者が付けた名前 (拡張子込み)、label: 表示名。 */
	public record FileInfo(String fileId, String storedFileName, String name, String label) {
	}

	public RedtactApi(HttpClient http) {
		this.http = http;
	}

	public static HttpClient newHttpClient() {
		return HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	/** {@code GET <api>/api/v1/circuits/<id>/detail}。取れなければ null。 */
	public @Nullable Detail detail(String apiBase, String circuitId) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/api/v1/circuits/" + circuitId + "/detail"))
					.timeout(TIMEOUT).header("Accept", "application/json").header("Accept-Language", "ja").GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return res.statusCode() == 200 ? parseDetail(res.body()) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** 詳細の応答を読む (読めなければ null)。 */
	public static @Nullable Detail parseDetail(String body) {
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			String title = str(o, "title");
			List<FileInfo> files = new ArrayList<>();
			JsonElement fs = o.get("files");

			if (fs != null && fs.isJsonArray()) {
				for (JsonElement el : fs.getAsJsonArray()) {
					if (!el.isJsonObject()) {
						continue;
					}

					JsonObject f = el.getAsJsonObject();
					String id = str(f, "file_id");
					String stored = str(f, "file_name");

					if (!id.isEmpty() && !stored.isEmpty()) {
						files.add(new FileInfo(id, stored, str(f, "name"), str(f, "label")));
					}
				}
			}

			return new Detail(title, files);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
	}
}
