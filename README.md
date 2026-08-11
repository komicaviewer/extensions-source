# NewsHub Extensions Source

Source code for third-party extensions for [NewsHub](https://github.com/komicaviewer/NewsHub).

Modeled after [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

## Architecture

The current release contains seven installable APKs and thirteen Sources. Every
Source runs in its own Android isolated-process service. Extension APKs declare
no permissions; network and authentication capabilities are supplied by the
NewsHub host through a source-scoped Binder broker.

[`release-catalog.json`](release-catalog.json) is the publishing source of truth.
It maps every Source module, ID, class, test task, owning APK, Gradle build task,
APK output, package, build-only release metadata, artifact filename, and
producer-side icon asset.
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
eyny-source ──────→ eyny.apk (1 Source)
mobile01 ─────────→ mobile01.apk (1 Source)
ptt ──────────────→ ptt.apk (1 Source)
```

| Module | Type | Responsibility |
|---|---|---|
| `src/twocat-komica` | Android library | Twocat Komica Source |
| `src/sora-komica` | Android library | Sora Komica Source |
| `src/akraft` | Android library | Akraft Source |
| `src/nagatoyuki` | Android library | Nagatoyuki Source |
| `src/wtako` | Android library | Wtako Source |
| `src/twocat-komica2` | Android library | Twocat Komica2 Source |
| `src/sora-komica2` | Android library | Sora Komica2 Source |
| `src/zawarudo-komica2` | Android library | Zawarudo Komica2 Source |
| `src/komica` | Android application | Komica bundle with five isolated Source services |
| `src/komica2` | Android application | Komica2 bundle with three isolated Source services |
| `src/gamer` | Android application | Gamer isolated Source service |
| `src/hackernews` | Android application | Hacker News isolated Source service |
| `src/eyny-source` | Android library | EYNY 伊莉討論區 Source with paginated forum posts |
| `src/eyny` | Android application | EYNY isolated Source service |
| `src/mobile01-source` | Android library | Mobile01 Source with paginated forum posts |
| `src/mobile01` | Android application | Mobile01 isolated Source service |
| `src/ptt` | Android application | PTT isolated Source service |

Parsers use `extension-api` models directly. There is no shared Komica parser or
intermediate `KPost`/`KParagraph` model: site-specific parsing code stays inside
the corresponding Source library.

PTT keeps each article as a `Post` and its push messages as `Comment` values. Its
internal page-shaped result (`posts` plus `nextPageToken`) is deliberately kept
separate from the current `Thread` adapter so it can adopt the planned host
`ThreadPage` contract without changing parsing semantics.

Every Source ID belongs to exactly one release APK. Moving a Source between APKs
must preserve its ID so subscriptions and cached board references remain valid.

## Isolated Source service contract

Each Source has exactly one exported Service. The Service is callable only by
the NewsHub signature permission and executes under a unique isolated UID:

```xml
<service
    android:name=".ExampleExtensionService"
    android:exported="true"
    android:isolatedProcess="true"
    android:permission="tw.kevinzhang.newshub.permission.BIND_EXTENSION"
    android:process=":source_example">
    <intent-filter>
        <action android:name="tw.kevinzhang.newshub.extension.SERVICE" />
    </intent-filter>
    <meta-data android:name="newshub.extension.protocol" android:value="1" />
    <meta-data android:name="newshub.extension.source_id" android:value="example" />
    <meta-data android:name="newshub.extension.source_name" android:value="Example" />
    <meta-data android:name="newshub.extension.source_lang" android:value="en" />
    <meta-data android:name="newshub.extension.source_base_url" android:value="https://example.com" />
</service>
```

`assets/newshub-extension.json`, the old application marker, `PathClassLoader`,
and direct extension networking are forbidden. CI compares service metadata to
`release-metadata/`, rejects every declared APK permission, and rejects any APK
that still packages the legacy registry asset.

## Adding a Source

1. Add the implementation to the appropriate Android Source module.
2. Return `ThreadSummary`, `Thread`, `Post`, and `Paragraph` from `extension-api`
   directly; site-specific intermediate models must be `internal`.
3. Add an `IsolatedSourceService` wrapper and the complete service declaration
   to the owning bundle manifest.
4. Add parser and request tests to that module's normal `test` source set.
5. Register the Source and its owning release data in `release-catalog.json`.
   Do not add Source/APK lists to build configuration or validator code; all
   publishing, Gradle, output, metadata, and icon information must be derived
   from the catalog.
6. Run `python3 scripts/release_catalog.py validate` and
   `python3 scripts/validate_source_manifests.py`. They reject metadata/service
   mismatches, permissions, duplicate IDs/classes, missing assets/projects, and Gradle modules
   that were added to `settings.gradle.kts` without a catalog entry.

## Building

```bash
python3 scripts/release_catalog.py validate
python3 scripts/validate_source_manifests.py
python3 -m unittest discover -s scripts -p 'test_*.py'
NEWSHUB_API_CHECKOUT=/absolute/path/to/pinned/NewsHub
"$NEWSHUB_API_CHECKOUT/gradlew" -p "$NEWSHUB_API_CHECKOUT" \
  --no-daemon :extension-api:assembleDebug
./gradlew -PnewshubDir="$NEWSHUB_API_CHECKOUT" $(python3 scripts/release_catalog.py gradle-tasks)
```

The checkout must be commit
`53d421492614c13e2a5984b4991513d993d44246` until that API is published. A
plain Gradle invocation currently resolves the older JitPack pin and cannot
compile `shared:broker-http` because it lacks the isolation-era network types.

## Releasing with GCP Cloud Build

This repository does not use GitHub Actions. GCP Cloud Build owns both
zero-secret PR candidates and controller-dispatched release publication:

- `cloudbuild/pr-candidate.yaml` validates one exact PR head, builds against the
  pinned NewsHub extension API, produces an ephemeral test-signed APK, and
  writes the candidate plus SHA-256 manifest to a private GCS bucket. It has no
  Secret Manager or `secretEnv` declaration.
- `cloudbuild/publish.yaml` derives the complete seven-APK build from
  `release-catalog.json`, signs each package in a separate step, admits the
  generated distribution, and uses a short-lived GitHub App installation token
  to create and exact-head squash-merge a distribution PR.

Both definitions default to `E2_STANDARD_2`, have 10-minute queue TTLs,
45/50-minute hard timeouts, finite step timeouts, Cloud Logging-only logs, and
no automatic retry. Publication is a manual Cloud Build trigger dispatched by
the private controller only after it observes the exact-SHA merge; it is not a
repository push trigger. These files define builds only; provisioning service accounts, buckets,
triggers, secrets, and IAM is intentionally separate.

Signing is split across seven Cloud Build steps. Each step receives only that
bundle's `SIGNING_KEY_B64`, `KEY_STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
and `SIGNING_CERT_SHA256` through Secret Manager `secretEnv`. The unsigned
build receives no signing or GitHub credential. The dedicated publisher service
account can access the listed secrets, so it must never be used by the PR build.
There is no repository-global APK certificate: the signed repository metadata
binds each package to its stable `lineageRootSha256` and its currently accepted
`apkSignerPins` set.

`release-catalog.json` also owns each isolated service class, protocol, exact
network hosts, named host capabilities, and canonical `SourceNetworkPolicy`
SHA-256. Signed targets metadata binds those values to the package and Source ID.
For local emulator E2E, `scripts/generate_test_trust_fixture.py` creates fresh
P-256 root/targets/snapshot/timestamp keys, threshold-signs versioned metadata,
and copies the seven package-signed APKs under `targets/apk/`. Its private keys
and APK signer keystores are ephemeral test fixtures and must never be committed.

Production remains fail-closed until maintainers provision seven independent
protected package-signing environments and offline TUF role keys. The embedded
`metadata/root.json` bytes must be reviewed and copied unchanged into the
NewsHub client trust resource before the corresponding remote metadata is made
reachable; no legacy global certificate or unversioned metadata fallback is allowed.

Publication is fail-closed. Before the destination checkout is changed, admission
validation requires the catalog-complete APK/Source set; exact service metadata
and bytecode ownership; semantic equality of `index.json` and `index.min.json`;
referenced APK/icon existence; SHA-256, package, version, and signing
certificate integrity; and comparison with the original destination `main`
indexes to reject version rollback, same-version APK replacement, and
unauthorized package/Source deletion. The generator works in a staging tree and
rolls back managed paths if publication replacement fails.

Cloud Build never pushes directly to destination `main`. It pushes an isolated
`automation/extensions-cloudbuild-<build-id>` candidate branch, stages only the
`apk/`, `icon/`, `index.json`, and `index.min.json` allowlist, opens a pull
request, and exact-head squash-merges it only after local admission and
destination branch protection admit the candidate. Direct publication pushes
are prohibited. The GitHub App private key is exchanged once for an
installation token whose remaining lifetime must be no more than 65 minutes.
The token request is repository-scoped to `extensions` with only write access
to repository contents and pull requests.
