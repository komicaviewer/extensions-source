# NewsHub Extensions Source

Source code for third-party extensions for [NewsHub](https://github.com/twkevinzhang/NewsHub).

Modeled after [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

## Architecture

Komica-family Sources are grouped into two installable bundle APKs. Site modules
are Android libraries with an `engine` flavor; bundle modules select the matching
variant and package several Sources together.

```text
twocat[komica] ─┐
sora[komica] ───┴─→ komica.apk

twocat[komica2] ─┐
sora[komica2] ───┼─→ komica2.apk
zawarudo[komica2]┘
```

| Module | Type | Responsibility |
|---|---|---|
| `src/twocat` | Android library | Twocat Komica and Komica2 Source variants |
| `src/sora` | Android library | Sora Komica and Komica2 Source variants |
| `src/zawarudo` | Android library | Zawarudo shared crawler and Komica2 Source |
| `src/komica` | Android application | Komica bundle APK and Source registry |
| `src/komica2` | Android application | Komica2 bundle APK and Source registry |

Parsers use `extension-api` models directly. There is no shared Komica parser or
intermediate `KPost`/`KParagraph` model: site-specific parsing code stays inside
the corresponding library and flavor source set.

`akraft`, `nagatoyuki`, `wtako`, and `gamer` remain registry-based standalone
application modules. They compile against the same clean-break bundle ABI but
are not part of the two-bundle release workflow described below.

## Bundle registry

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

1. Add the implementation to the appropriate site module and engine source set.
2. Return `ThreadSummary`, `Thread`, `Post`, and `Paragraph` from `extension-api`
   directly; site-specific intermediate models must be `internal`.
3. Add the Source class and metadata to the owning bundle's
   `assets/newshub-extension.json`.
4. Add parser tests under the matching `testKomica` or `testKomica2` source set.
5. Update the release-bundle assertions when the expected Source set changes.

## Building

```bash
./gradlew \
  :src:twocat:testKomicaDebugUnitTest \
  :src:twocat:testKomica2DebugUnitTest \
  :src:sora:testKomicaDebugUnitTest \
  :src:sora:testKomica2DebugUnitTest \
  :src:zawarudo:testKomica2DebugUnitTest \
  :src:komica:assembleRelease \
  :src:komica2:assembleRelease
```

## Releasing

Push to `main`. GitHub Actions builds and signs the two bundle APKs, validates
their registries, regenerates `index.json` from only those APKs, and publishes
the result to the [extensions repo](https://github.com/komicaviewer/extensions).
