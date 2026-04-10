# NewsHub Extensions Source

Source code for third-party extensions for [NewsHub](https://github.com/twkevinzhang/NewsHub).

Modeled after [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

## Structure

- `src/<extensionName>/` — Individual extension modules (flat, no language subdirectories)
- `lib/` — Shared utility modules
- `common.gradle` — Shared Android build configuration applied by all extensions

## Adding an Extension

1. Create a new directory under `src/`
2. Add `build.gradle.kts` with `extName`, `extClass`, `extVersionCode`
3. Add `AndroidManifest.xml` (see existing extensions for the metadata keys)
4. Implement the `Source` interface from `extension-api`
5. Apply `common.gradle` at the bottom of your `build.gradle.kts`

## Building

```bash
./gradlew :src:gamer:assembleRelease
```

## Releasing

Push to `main` branch. GitHub Actions will build all extensions and publish them to the [extensions repo](https://github.com/komicaviewer/extensions).
