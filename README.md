# NewsHub Extensions Source

Source code for third-party extensions for [NewsHub](https://github.com/komicaviewer/NewsHub).

Modeled after [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

## Architecture

The current release contains five installable APKs and eleven Sources. Source
implementations are Kotlin/JVM libraries; Android application modules own the APK
manifest, registry asset, signing version, and installation boundary.

[`release-catalog.json`](release-catalog.json) is the publishing source of truth.
It maps every Source module, ID, class, test task, owning APK, Gradle build task,
APK output, package, registry, artifact filename, and producer-side icon asset.
The catalog validator also requires every `:src` module in `settings.gradle.kts`
to be registered, so a newly added module cannot be silently omitted from CI.

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
hackernews ───────→ hackernews.apk (1 Source)
ptt ──────────────→ ptt.apk (1 Source)
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
| `src/hackernews` | Android application | Hacker News APK and one-Source registry |
| `src/ptt` | Android application | PTT APK and one-Source registry |

Parsers use `extension-api` models directly. There is no shared Komica parser or
intermediate `KPost`/`KParagraph` model: site-specific parsing code stays inside
the corresponding Source library.

PTT keeps each article as a `Post` and its push messages as `Comment` values. Its
internal page-shaped result (`posts` plus `nextPageToken`) is deliberately kept
separate from the current `Thread` adapter so it can adopt the planned host
`ThreadPage` contract without changing parsing semantics.

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

The referenced asset is the runtime registry. Its Source IDs and classes must
exactly match the owning release in `release-catalog.json`:

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
5. Register the Source and its owning release data in `release-catalog.json`.
   Do not add Source/APK lists to workflow or validator code; all publishing,
   Gradle, output, registry, and icon information must be derived from the catalog.
6. Run `python3 scripts/release_catalog.py validate`. It rejects catalog/registry
   mismatches, duplicate IDs/classes, missing assets/projects, and Gradle modules
   that were added to `settings.gradle.kts` without a catalog entry.

## Building

```bash
python3 scripts/release_catalog.py validate
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew $(python3 scripts/release_catalog.py gradle-tasks)
```

## Releasing

Push to `main`. GitHub Actions derives all test, build, collection, and artifact
operations from `release-catalog.json`, signs the APKs, and builds a staged
distribution candidate for the
[extensions repo](https://github.com/komicaviewer/extensions).

Publication is fail-closed. Before the destination checkout is changed, admission
validation requires the catalog-complete APK/Source set; exact registry and
bytecode ownership; semantic equality of `index.json` and `index.min.json`;
referenced APK/icon existence; SHA-256, package, version, registry, and signing
certificate integrity; and comparison with the original destination `main`
indexes to reject version rollback, same-version APK replacement, and
unauthorized package/Source deletion. The generator works in a staging tree and
rolls back managed paths if publication replacement fails.

The workflow never pushes directly to destination `main`. It pushes an isolated
`automation/extensions-<run>-<attempt>` candidate branch, stages only the
`apk/`, `icon/`, `index.json`, and `index.min.json` allowlist, opens a pull
request, and asks GitHub to auto-merge it with squash after destination branch
protections and required checks admit the candidate. Direct publication pushes
are prohibited.
