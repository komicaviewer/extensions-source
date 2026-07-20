# NewsHub Extensions Source

Source code for third-party extensions for [NewsHub](https://github.com/komicaviewer/NewsHub).

Modeled after [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

## Architecture

The release contains exactly three installable APKs and nine Sources. Source
implementations are Kotlin/JVM libraries; Android application modules own the APK
manifest, registry asset, signing version, and installation boundary.

```text
twocat-komica ─┐
sora-komica ───┤
akraft ────────┤
nagatoyuki ────┼─→ komica.apk (5 Sources)
wtako ─────────┘

twocat-komica2 ─┐
sora-komica2 ───┼─→ komica2.apk (3 Sources)
zawarudo-komica2┘

gamer ────────────→ gamer.apk (1 Source)
```

| Module | Type | Responsibility |
|---|---|---|
| `src/twocat-komica` | Kotlin/JVM library | Twocat Komica Source |
| `src/sora-komica` | Kotlin/JVM library | Sora Komica Source |
| `src/akraft` | Kotlin/JVM library | Akraft Source |
| `src/nagatoyuki` | Kotlin/JVM library | Nagatoyuki Source |
| `src/wtako` | Kotlin/JVM library | Wtako Source |
| `src/twocat-komica2` | Kotlin/JVM library | Twocat Komica2 Source |
| `src/sora-komica2` | Kotlin/JVM library | Sora Komica2 Source |
| `src/zawarudo-komica2` | Kotlin/JVM library | Zawarudo Komica2 Source |
| `src/komica` | Android application | Komica bundle APK and five-Source registry |
| `src/komica2` | Android application | Komica2 bundle APK and three-Source registry |
| `src/gamer` | Android application | Gamer APK and one-Source registry |

Parsers use `extension-api` models directly. There is no shared Komica parser or
intermediate `KPost`/`KParagraph` model: site-specific parsing code stays inside
the corresponding Source library.

Every Source ID belongs to exactly one release APK. Moving a Source between APKs
must preserve its ID so subscriptions and cached board references remain valid.

## APK registry

Every bundle manifest must declare:

```xml
<meta-data android:name="newshub.extension" android:value="true" />
<meta-data
    android:name="newshub.extension.registry"
    android:value="newshub-extension.json" />
```

The referenced asset is the runtime and release-index source of truth:

```json
{
  "schemaVersion": 1,
  "name": "NewsHub: Komica",
  "sources": [
    {
      "className": "tw.kevinzhang.newshub.extension.twocat.komica.TwocatSource",
      "id": "tw.kevinzhang.komica.twocat",
      "name": "Twocat",
      "lang": "zh-TW",
      "baseUrl": "https://2cat.org"
    }
  ]
}
```

Source classes require a public no-argument constructor. Their runtime `id`,
`name`, and `language` must exactly match the registry descriptor.

## Adding a Source

1. Add the implementation to the appropriate Kotlin/JVM Source module.
2. Return `ThreadSummary`, `Thread`, `Post`, and `Paragraph` from `extension-api`
   directly; site-specific intermediate models must be `internal`.
3. Add the Source class and metadata to the owning bundle's
   `assets/newshub-extension.json`.
4. Add parser and request tests to that module's normal `test` source set.
5. Update `scripts/validate_release_bundles.py` when the owning APK or expected
   Source set changes. The release contract must still contain exactly three
   APKs and every intended Source exactly once.

## Building

```bash
./gradlew \
  :src:akraft:test \
  :src:nagatoyuki:test \
  :src:wtako:test \
  :src:twocat-komica:test \
  :src:twocat-komica2:test \
  :src:sora-komica:test \
  :src:sora-komica2:test \
  :src:zawarudo-komica2:test \
  :src:gamer:testDebugUnitTest \
  :src:gamer:assembleRelease \
  :src:komica:assembleRelease \
  :src:komica2:assembleRelease
```

## Releasing

Push to `main`. GitHub Actions builds and signs exactly `gamer`, `komica`, and
`komica2`; verifies every registry Source class is present and Komica/Komica2
bytecode stays isolated; then regenerates `index.json` and publishes it to the
[extensions repo](https://github.com/komicaviewer/extensions).

Publication is fail-closed. Before the distribution checkout is changed, the
scripts require the exact three-APK/nine-Source set, validate each APK package
against its release module, and reject partial or unexpected inputs. A missing
Gamer APK therefore fails the workflow instead of silently deleting Gamer from
the index.
