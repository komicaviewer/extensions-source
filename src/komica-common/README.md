# komica-common

---
`komica-common` 是一個 Android library 模組，建置時會被 sora、site2cat、komica2 打包進自己的 APK，**而非作為共用的 runtime 依賴**。

這意味著：

- 每個 APK（sora、site2cat、komica2）都包含一份 komica-common 的代碼副本
- 三個 APK 各自獨立部署，不需要協調版本相依性

這是刻意的設計：

| 考量 | 說明 |
|------|------|
| **獨立部署** | 三個 extension 可以各自發版，互不影響 |
| **版本演進自由** | 若某個 extension 需要修改解析邏輯，不會影響其他 extension |
| **代價** | 每個 APK 多了約數十 KB 的代碼體積，可接受 |

---

## 模組依賴圖

```mermaid
graph TD
    extension-api["extension-api (JitPack, compileOnly)"]
    komica-common[":src:komica-common (Android library)"]
    sora[":src:sora (APK extension)"]
    site2cat[":src:site2cat (APK extension)"]
    komica2[":src:komica2 (APK extension)"]

    sora --> komica-common
    site2cat --> komica-common
    komica2 --> komica-common
    sora --> extension-api
    site2cat --> extension-api
    komica2 --> extension-api
```
