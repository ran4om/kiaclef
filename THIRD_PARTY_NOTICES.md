# Third-party notices

Kiaclef is a modified version of AltoClef. The release jar bundles the components marked "bundled". The others are separate mods you install yourself.

| Component | Version | Use | License | Source |
| --- | --- | --- | --- | --- |
| AltoClef | upstream `main` (2022) | Base of this fork | MIT ([LICENSE](LICENSE)) | https://github.com/gaucho-matrero/altoclef |
| Baritone (Fabric, unoptimized) | 1.19.0 | Bundled, unmodified, as a nested Fabric mod | LGPL-3.0 ([LICENSE-BARITONE](LICENSE-BARITONE)) | https://github.com/cabaletta/baritone |
| Jackson Core | 2.20.0 | Bundled | Apache License 2.0 | https://github.com/FasterXML/jackson-core |
| Jackson Databind | 2.20.0 | Bundled | Apache License 2.0 | https://github.com/FasterXML/jackson-databind |
| Jackson Annotations | 2.20 | Bundled | Apache License 2.0 | https://github.com/FasterXML/jackson-annotations |
| Apache Commons Lang | 3.20.0 | Bundled | Apache License 2.0 | https://github.com/apache/commons-lang |
| Fabric Loader | 0.19.5+ | Required, not bundled | Apache License 2.0 | https://github.com/FabricMC/fabric-loader |
| Fabric API | 0.154.2+26.2 | Required, not bundled | Apache License 2.0 | https://github.com/FabricMC/fabric |
| Litematica / MaLiLib | 0.28.8 / 0.29.6 | Optional, not bundled | LGPL-3.0 | https://github.com/maruohon/litematica |

## Baritone (LGPL-3.0)

The Baritone jar inside Kiaclef (`META-INF/jars/baritone-unoptimized-fabric-1.19.0.jar`) is the official, unmodified Baritone 1.19.0 release. It stays a separate nested mod, so you can replace it with any compatible build of the same version. Kiaclef changes some Baritone behaviour at runtime through Mixin classes in `adris.altoclef.mixins.baritonecompat`, whose source is in this repository. Baritone's own source is available at the link above. The full license text is in [LICENSE-BARITONE](LICENSE-BARITONE) and is also packaged in the jar as `LICENSE-BARITONE_baritone`.

## Apache-licensed libraries

Jackson and Commons Lang are included unmodified, along with their license and notice files.
