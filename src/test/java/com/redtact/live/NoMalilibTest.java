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
 * この MOD は malilib を使わない (#28)。malilib は Litematica 連携のコンパイルのためだけに compileOnly で参照しているので、
 * うっかり使うとコンパイルは通り、malilib を入れていない利用者の手元で落ちる。ソースに import が無いことを確かめる。
 */
class NoMalilibTest {
	@Test
	void mainSourcesDoNotImportMalilib() throws IOException {
		Path root = Path.of("src/main/java");

		try (Stream<Path> files = Files.walk(root)) {
			List<String> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
				try {
					return Files.readString(p, StandardCharsets.UTF_8).contains("fi.dy.masa.malilib");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}).map(p -> root.relativize(p).toString()).toList();
			assertEquals(List.of(), offenders, "malilib を使っているソースがある");
		}
	}
}
