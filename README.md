# NewsHub 官方 Extensions 維運原始碼

本 repository 是 NewsHub 官方 extensions 的維運 monorepo，包含官方 Source 實作、七個 APK bundle、發佈 catalog、審核過的 network policies 與 production publication automation。它不是第三方 extension starter，也不應被 fork 後直接當成第三方 repository 範本；第三方開發者應使用 NewsHub 文件所指向的獨立 starter 與 repository generator。

## 架構

目前 release 包含七個可安裝 APK 與十三個 Sources。每個 Source 都在自己的 Android isolated-process service 中執行。Extension APK 不宣告任何 permission；network 與 authentication capabilities 由 NewsHub host 透過 Source-scoped Binder broker 提供。

[`release-catalog.json`](release-catalog.json) 是發布流程的唯一事實來源，包含每個 Source 的 module、ID、class、test task、所屬 APK、Gradle build task、APK output、package、build-only release metadata、artifact filename 與 producer-side icon asset。

Catalog validator 也要求 `settings.gradle.kts` 中每個 `:src` module 都必須註冊，避免新增 module 被 CI 靜默漏掉。

```text
twocat-komica ─┐
sora-komica ───┤
akraft ────────┤
nagatoyuki ────┼─→ komica.apk（5 個 Sources）
wtako ─────────┘

twocat-komica2 ─┐
sora-komica2 ───┼─→ komica2.apk（3 個 Sources）
zawarudo-komica2┘

gamer ────────────→ gamer.apk（1 個 Source）
hackernews ───────→ hackernews.apk（1 個 Source）
eyny-source ──────→ eyny.apk（1 個 Source）
mobile01 ─────────→ mobile01.apk（1 個 Source）
ptt ──────────────→ ptt.apk（1 個 Source）
```

| Module | 類型 | 職責 |
|---|---|---|
| `src/twocat-komica` | Android library | Twocat Komica Source |
| `src/sora-komica` | Android library | Sora Komica Source |
| `src/akraft` | Android library | Akraft Source |
| `src/nagatoyuki` | Android library | Nagatoyuki Source |
| `src/wtako` | Android library | Wtako Source |
| `src/twocat-komica2` | Android library | Twocat Komica2 Source |
| `src/sora-komica2` | Android library | Sora Komica2 Source |
| `src/zawarudo-komica2` | Android library | Zawarudo Komica2 Source |
| `src/komica` | Android application | 包含五個 isolated Source services 的 Komica bundle |
| `src/komica2` | Android application | 包含三個 isolated Source services 的 Komica2 bundle |
| `src/gamer` | Android application | Gamer isolated Source service |
| `src/hackernews` | Android application | Hacker News isolated Source service |
| `src/eyny-source` | Android library | 支援分頁文章的 EYNY 伊莉討論區 Source |
| `src/eyny` | Android application | EYNY isolated Source service |
| `src/mobile01-source` | Android library | 支援分頁文章的 Mobile01 Source |
| `src/mobile01` | Android application | Mobile01 isolated Source service |
| `src/ptt` | Android application | PTT isolated Source service |

Parser 直接使用 `extension-api` models。不使用共用 Komica parser，也沒有中間層 `KPost`／`KParagraph` model；各網站專屬 parsing code 保留在對應 Source library。

PTT 會把每篇文章保留為 `Post`，推文保留為 `Comment`。內部 page-shaped result（`posts` 加 `nextPageToken`）刻意與目前的 `Thread` adapter 分開，以便未來採用 host `ThreadPage` contract 時不改變 parsing semantics。

每個 Source ID 只能屬於一個 release APK。Source 在 APK 間移動時必須保留 ID，確保 subscriptions 與 cached board references 仍有效。

## Isolated Source service contract

每個 Source 必須有且只有一個 exported Service。Service 只能由 NewsHub signature permission 呼叫，並在獨立 UID 下執行：

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
    <meta-data android:name="newshub.extension.protocol" android:value="2" />
    <meta-data android:name="newshub.extension.source_id" android:value="example" />
    <meta-data android:name="newshub.extension.source_name" android:value="Example" />
    <meta-data android:name="newshub.extension.source_lang" android:value="en" />
    <meta-data android:name="newshub.extension.source_base_url" android:value="https://example.com" />
</service>
```

禁止使用舊 application marker `assets/newshub-extension.json`、`PathClassLoader` 或 extension direct networking。驗證流程會把 service metadata 與 `release-metadata/` 比對，拒絕任何 APK permission，也拒絕仍包含 legacy registry asset 的 APK。

Protocol v2 的 runtime descriptor 由 `IsolatedSourceService` 在執行時從 `Source` 產生，包括 UI flags、登入描述與 WebView User-Agent。Manifest 不得宣告 `needs_login`、`login_url` 或 `login_hosts` 等 legacy login metadata；`AuthenticatedSource.authSpec` 與 `WebLoginUserAgentProvider` 才是唯一來源。

## 新增 Source

1. 將實作加入適當的 Android Source module。
2. 直接回傳 `extension-api` 的 `ThreadSummary`、`Thread`、`Post` 與 `Paragraph`；網站專屬中間 model 必須是 `internal`。
3. 新增 `IsolatedSourceService` wrapper，並在所屬 bundle manifest 加入完整 service declaration。
4. 在該 module 的一般 `test` source set 加入 parser 與 request tests。
5. 在 `release-catalog.json` 登記 Source 與所屬 release data。不得在 build configuration 或 validator code 另建 Source/APK 清單；publishing、Gradle、output、metadata 與 icon 資訊都必須由 catalog 衍生。
6. 執行 `python3 scripts/release_catalog.py validate` 與 `python3 scripts/validate_source_manifests.py`。驗證器會拒絕 metadata/service mismatch、permissions、重複 ID/class、缺少 assets/projects，以及未登記到 catalog 的 Gradle modules。

## 建置

```bash
python3 scripts/release_catalog.py validate
python3 scripts/validate_source_manifests.py
python3 -m unittest discover -s scripts -p 'test_*.py'
NEWSHUB_API_CHECKOUT=/absolute/path/to/pinned/NewsHub
"$NEWSHUB_API_CHECKOUT/gradlew" -p "$NEWSHUB_API_CHECKOUT" \
  --no-daemon :extension-api:assembleDebug
./gradlew -PnewshubDir="$NEWSHUB_API_CHECKOUT" \
  $(python3 scripts/release_catalog.py gradle-tasks)
```

NewsHub checkout 與 JitPack dependency 必須固定為 commit `3d63cb87eeff9ab799152db0034ab3512656d83c`。此版本包含 isolation-era network protocol、Host policy v2 與 typed access failure；candidate、publish 與本機非 override 建置必須使用同一個 exact SHA。

## 使用 GCP Cloud Build 發布

本 repository 不使用 GitHub Actions。GCP Cloud Build 同時負責零 secrets 的 PR candidate 與由 controller dispatch 的 release publication：

- `cloudbuild/pr-candidate.yaml`：驗證單一精確 PR head，以 pinned NewsHub extension API 建置，產生暫時 test-signed APK，並將 candidate 與 SHA-256 manifest 寫入 private GCS bucket。它沒有 Secret Manager 或 `secretEnv` 宣告。
- `cloudbuild/publish.yaml`：由 `release-catalog.json` 產生完整七 APK build，在不同 step 簽署每個 package，執行 distribution admission，再以短效 GitHub App installation token 建立並精確 head squash-merge distribution PR。

`publish.yaml` 的 38 個 Secret Manager `versionName` 全部以 `_SECRET_PREFIX` 組成完整 secret ID。控制面的 trigger 必須顯式傳入 `newshub-extension-ops`；檔案內的 `REQUIRED_SECRET_PREFIX` 預設值刻意無法對應 production secret，避免直接 submit 時意外讀取其他 namespace。

兩個 build 都固定使用 `E2_STANDARD_2`、10 分鐘 queue TTL、45／50 分鐘 hard timeout、有限 step timeout、`CLOUD_LOGGING_ONLY`，且不自動重試。Publication 只能在私有 controller 驗證 merge gate 寫入 GCS 的 exact squash-merge commit SHA 後，以 manual Cloud Build trigger dispatch。`_SOURCE_SHA` 不得從當下 branch HEAD 或 `$COMMIT_SHA` 推導；它不是 repository push trigger。

這些檔案只定義 build。Service accounts、buckets、triggers、secrets 與 IAM 由獨立的私有 IaC 管理。

簽署流程拆成七個 Cloud Build steps。每個 step 只取得該 bundle 的 `SIGNING_KEY_B64`、`KEY_STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 與 `SIGNING_CERT_SHA256`。Unsigned build 不取得 signing 或 GitHub credential。專用 publisher service account 可讀取這些 secrets，因此絕對不能供 PR build 使用。

Repository 沒有全域 APK certificate；signed repository metadata 會把每個 package 綁定到穩定的 `lineageRootSha256` 與目前接受的 `apkSignerPins`。

`release-catalog.json` 也定義每個 isolated service class、protocol、精確 network hosts、具名 host capabilities 與 canonical `SourceNetworkPolicy` SHA-256。Signed targets metadata 會把這些值綁定到 package 與 Source ID。

本機 emulator E2E 可使用 `scripts/generate_test_trust_fixture.py` 建立新的 P-256 root/targets/snapshot/timestamp keys、以 threshold 簽署 versioned metadata，並把七個 package-signed APK 複製到 `targets/apk/`。這些 private keys 與 APK signer keystores 都是暫時 test fixtures，禁止 commit。

在維運者建立七個互相獨立、受保護的 package signing environments 與 offline TUF role keys 前，production 必須維持 fail-closed。Embedded `metadata/root.json` bytes 必須經審核後原封不動複製到 NewsHub client trust resource，之後才能讓對應 remote metadata 可存取；不得使用 legacy global certificate 或 unversioned metadata fallback。

## 發布准入規則

Publication 採 fail-closed。修改 destination checkout 前，admission validation 必須確認：

- APK／Source 集合與 catalog 完整一致。
- Service metadata 與 bytecode ownership 精確一致。
- `index.json` 與 `index.min.json` 語意相等。
- 所有 referenced APK/icon 都存在。
- SHA-256、package、version 與 signing certificate 完整正確。
- 與原始 destination `main` indexes 比較，拒絕 version rollback、同版本 APK replacement，以及未授權的 package/Source deletion。

Generator 只在 staging tree 工作；publication replacement 失敗時會回復 managed paths。

Cloud Build 絕不直接 push destination `main`。它會 push 隔離的 `automation/extensions-cloudbuild-<build-id>` candidate branch，只 stage `apk/`、`icon/`、`index.json` 與 `index.min.json` allowlist，建立 PR，並在本機 admission 與 destination branch protection 都允許後，才 exact-head squash-merge。

禁止直接 publication push。GitHub App private key 只交換一次 installation token，且 token 剩餘有效期不得超過 65 分鐘。Token request 的 repository scope 只包含 `extensions`，權限僅限 repository contents 與 pull requests write。
