package com.redtact.live.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RedtactApiTest {
	/** redtact 本番の GET /api/v1/circuits/{id}/detail の応答 (要る項目だけ)。 */
	private static final String DETAIL = """
			{"id": "6BxMfe5Vfjp4", "title": "msa2_ALU", "files": [
			  {"file_id": "01a0b30b-54a3-7190-970f-210aeac217d7", "file_name": "adb81e60f4b849de.schem", "format": "schem",
			   "label": "msa2_ALU", "name": "msa2_ALU.schem", "size": 2876},
			  {"file_id": "", "file_name": "broken.schem"},
			  "not an object"
			]}
			""";

	@Test
	void parsesTitleAndFiles() {
		RedtactApi.Detail d = RedtactApi.parseDetail(DETAIL);
		assertNotNull(d);
		assertEquals("msa2_ALU", d.title());
		assertEquals(1, d.files().size(), "ID の無いファイルは飛ばす");
		RedtactApi.FileInfo f = d.fileByStoredName("adb81e60f4b849de.schem");
		assertNotNull(f);
		assertEquals("01a0b30b-54a3-7190-970f-210aeac217d7", f.fileId());
		assertEquals("msa2_ALU.schem", f.name());
		assertNull(d.fileByStoredName("other.schem"));
	}

	@Test
	void unreadableIsNull() {
		assertNull(RedtactApi.parseDetail("not json"));
		assertNull(RedtactApi.parseDetail("[]"));
		RedtactApi.Detail empty = RedtactApi.parseDetail("{}");
		assertNotNull(empty);
		assertEquals("", empty.title());
	}
}
