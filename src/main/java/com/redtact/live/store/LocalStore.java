package com.redtact.live.store;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.gson.JsonParseException;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.net.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MOD のローカル保存 (protocol.md「MOD のローカル保存」)。
 *
 * <pre>
 * &lt;root&gt;/circuits/&lt;circuitId&gt;/circuit.json
 * &lt;root&gt;/circuits/&lt;circuitId&gt;/snapshots/&lt;yyyyMMdd-HHmmss&gt;-v&lt;version&gt;.litematic
 * &lt;root&gt;/circuits/&lt;circuitId&gt;/comments.jsonl
 * </pre>
 *
 * <p>ファイルを消す操作は持たない。
 */
public final class LocalStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final Pattern CIRCUIT_ID = Pattern.compile("[a-z0-9]{8,64}");
	private static final DateTimeFormatter SNAPSHOT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Path root;
	private final Map<String, Set<String>> commentIds = new HashMap<>();
	/** circuit.json を書くたびに増える (保存済みの範囲の一覧を読み直す目印。#39)。 */
	private final AtomicInteger generation = new AtomicInteger();

	public LocalStore(Path root) {
		this.root = root;
	}

	public Path root() {
		return root;
	}

	/** circuit.json を書いた回数。変わっていれば一覧を読み直す。 */
	public int generation() {
		return generation.get();
	}

	public Path circuitsDir() {
		return root.resolve("circuits");
	}

	public Path circuitDir(String circuitId) {
		if (!CIRCUIT_ID.matcher(circuitId).matches()) {
			throw new IllegalArgumentException("bad circuitId: " + circuitId);
		}

		return circuitsDir().resolve(circuitId);
	}

	/** 消した回路を置く所 (#77)。 */
	public Path trashDir() {
		return root.resolve("trash");
	}

	private static final DateTimeFormatter TRASH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

	/**
	 * 回路 (保存済みの範囲) をフォルダごと {@code trash/<ID>-<日時>/} へ移す (#77)。範囲・ローカルの .litematic の履歴・
	 * コメント履歴も一緒に。戻すときはフォルダを {@code circuits/<ID>/} に戻す。移した先を返す。
	 */
	public synchronized Path trashCircuit(String circuitId, Instant now) throws IOException {
		Path dir = circuitDir(circuitId);

		if (!Files.isDirectory(dir)) {
			throw new java.nio.file.NoSuchFileException(dir.toString(), null, Tr.t("回路のフォルダがありません"));
		}

		Path trash = trashDir();
		Files.createDirectories(trash);
		String base = circuitId + "-" + TRASH_TIME.format(now);
		Path dest = trash.resolve(base);

		for (int i = 2; Files.exists(dest); i++) {
			dest = trash.resolve(base + "-" + i);
		}

		Files.move(dir, dest);
		commentIds.remove(circuitId);
		generation.incrementAndGet();
		return dest;
	}

	public Path snapshotsDir(String circuitId) {
		return circuitDir(circuitId).resolve("snapshots");
	}

	public synchronized List<CircuitRecord> listCircuits() throws IOException {
		List<CircuitRecord> out = new ArrayList<>();
		Path dir = circuitsDir();

		if (!Files.isDirectory(dir)) {
			return out;
		}

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				String id = p.getFileName().toString();

				if (!CIRCUIT_ID.matcher(id).matches() || !Files.isRegularFile(p.resolve("circuit.json"))) {
					continue;
				}

				try {
					CircuitRecord r = Json.GSON.fromJson(Files.readString(p.resolve("circuit.json"), StandardCharsets.UTF_8), CircuitRecord.class);

					if (r != null && id.equals(r.circuitId)) {
						if (r.sessions == null) {
							r.sessions = new ArrayList<>();
						}

						out.add(r);
					}
				} catch (JsonParseException | IOException e) {
					LOGGER.warn("[redtact-live] {} を読めません: {}", p.resolve("circuit.json"), e.toString());
				}
			}
		}

		out.sort(Comparator.comparing((CircuitRecord r) -> r.updatedAt == null ? "" : r.updatedAt).reversed());
		return out;
	}

	public synchronized Optional<CircuitRecord> findByName(String name) throws IOException {
		for (CircuitRecord r : listCircuits()) {
			if (name.equals(r.name)) {
				return Optional.of(r);
			}
		}

		return Optional.empty();
	}

	public synchronized Optional<CircuitRecord> findById(String circuitId) throws IOException {
		for (CircuitRecord r : listCircuits()) {
			if (circuitId.equals(r.circuitId)) {
				return Optional.of(r);
			}
		}

		return Optional.empty();
	}

	public synchronized CircuitRecord create(String name) throws IOException {
		CircuitRecord r = new CircuitRecord();

		do {
			byte[] b = new byte[8];
			RANDOM.nextBytes(b);
			r.circuitId = HexFormat.of().formatHex(b);
		} while (Files.exists(circuitDir(r.circuitId)));

		r.name = name;
		r.createdAt = Times.now();
		r.updatedAt = r.createdAt;
		save(r);
		return r;
	}

	public synchronized void save(CircuitRecord r) throws IOException {
		Path dir = circuitDir(r.circuitId);
		Files.createDirectories(dir);
		r.updatedAt = Times.now();
		Path target = dir.resolve("circuit.json");
		Path tmp = dir.resolve("circuit.json.tmp");
		Files.writeString(tmp, Json.PRETTY.toJson(r) + "\n", StandardCharsets.UTF_8);

		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}

		generation.incrementAndGet();
	}

	public synchronized Path saveSnapshot(String circuitId, int version, byte[] bytes, Instant time) throws IOException {
		Path dir = snapshotsDir(circuitId);
		Files.createDirectories(dir);
		String base = SNAPSHOT_TIME.format(time.atZone(ZoneId.systemDefault())) + "-v" + version;
		Path file = dir.resolve(base + ".litematic");

		for (int i = 2; Files.exists(file); i++) {
			file = dir.resolve(base + "-" + i + ".litematic");
		}

		Files.write(file, bytes);
		return file;
	}

	public synchronized int countSnapshots(String circuitId) throws IOException {
		Path dir = snapshotsDir(circuitId);

		if (!Files.isDirectory(dir)) {
			return 0;
		}

		int n = 0;

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.litematic")) {
			for (Path ignored : ds) {
				n++;
			}
		}

		return n;
	}

	public record AppendResult(List<Api.Comment> added, List<String> stored) {
	}

	/**
	 * コメントを comments.jsonl に追記する (id で重複を除く)。fsync してから返す。
	 *
	 * @return added = 今回新しく書いたもの、stored = 保存済みになった id (重複も含む。ack に使う)
	 */
	public synchronized AppendResult appendComments(String circuitId, String sessionId, List<Api.Comment> comments) throws IOException {
		Set<String> known = knownCommentIds(circuitId);
		List<Api.Comment> added = new ArrayList<>();
		List<String> stored = new ArrayList<>();
		StringBuilder lines = new StringBuilder();
		Set<String> batch = new HashSet<>();

		for (Api.Comment c : comments) {
			if (c == null || c.id == null) {
				continue;
			}

			stored.add(c.id);

			if (known.contains(c.id) || !batch.add(c.id)) {
				continue;
			}

			lines.append(Json.GSON.toJson(StoredComment.of(sessionId, c))).append('\n');
			added.add(c);
		}

		if (!added.isEmpty()) {
			Path dir = circuitDir(circuitId);
			Files.createDirectories(dir);

			try (FileOutputStream fos = new FileOutputStream(dir.resolve("comments.jsonl").toFile(), true)) {
				OutputStream out = fos;
				out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
				out.flush();
				fos.getFD().sync();
			}

			for (Api.Comment c : added) {
				known.add(c.id);
			}
		}

		return new AppendResult(added, stored);
	}

	public synchronized List<StoredComment> readComments(String circuitId) throws IOException {
		List<StoredComment> out = new ArrayList<>();
		Path file = circuitDir(circuitId).resolve("comments.jsonl");

		if (!Files.isRegularFile(file)) {
			return out;
		}

		Set<String> seen = new HashSet<>();

		try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = r.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				try {
					StoredComment c = Json.GSON.fromJson(line, StoredComment.class);

					if (c != null && c.id != null && seen.add(c.id)) {
						out.add(c);
					}
				} catch (JsonParseException e) {
					// 書きかけの行 (クラッシュ時) は飛ばす
					LOGGER.warn("[redtact-live] comments.jsonl の壊れた行を飛ばします: {}", line);
				}
			}
		}

		return out;
	}

	private Set<String> knownCommentIds(String circuitId) throws IOException {
		Set<String> known = commentIds.get(circuitId);

		if (known == null) {
			known = new HashSet<>();

			for (StoredComment c : readComments(circuitId)) {
				known.add(c.id);
			}

			commentIds.put(circuitId, known);
		}

		return known;
	}
}
