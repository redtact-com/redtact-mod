package com.redtact.live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * MCEF Modern (と同梱の jcef) は任意の依存 (#46)。compileOnly なのでどこからでも使えてしまうが、使ってよいのは
 * {@code browser/cef/} の中だけ (そこは MCEF Modern が入っているときにしか呼ばない)。ほかで使うと、入れていない利用者の手元で落ちる。
 */
class McefIsolationTest {
	@Test
	void onlyBrowserCefUsesMcef() throws IOException {
		Path root = Path.of("src/main/java");
		Path allowed = root.resolve("com/redtact/live/browser/cef");

		try (Stream<Path> files = Files.walk(root)) {
			List<String> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> !p.startsWith(allowed)).filter(p -> {
				try {
					String s = Files.readString(p, StandardCharsets.UTF_8);
					return s.contains("net.dimaskama.mcef") || s.contains("org.cef.");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}).map(p -> root.relativize(p).toString()).toList();
			assertEquals(List.of(), offenders, "browser/cef の外で MCEF Modern を使っている");
		}
	}
}
