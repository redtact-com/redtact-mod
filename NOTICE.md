# Third-party notices

redtact (Fabric mod) — Copyright (c) 2026 redtact, licensed under the MIT License (see LICENSE).

This project includes code and data derived from the following projects, which are licensed under the MIT License:

- [mc-nbt-converter](https://github.com/Taku128/mc-nbt-converter) — Copyright (c) 2026 Taku128 / Copyright (c) 2024 mcworld_render Project.
  The Bedrock block mapping files in `src/main/resources/assets/redtact-live/bedrock/` are copied from it, and the code that reads Bedrock `.mcstructure` files (`com.redtact.live.structure.bedrock`) is a Java port of its Go code.
- [Chunker](https://github.com/HiveGamesOSS/Chunker) — Copyright (c) 2024 Hive Games.
  `chunker-mappings.json` (Bedrock → Java block names) is extracted from it.

The full license text is in `src/main/resources/assets/redtact-live/bedrock/NOTICE.md`.

Optional integrations that are not bundled with this mod: Fabric API (Apache-2.0), Litematica and MaLiLib (LGPL-3.0), MCEF Modern (LGPL-2.1).
