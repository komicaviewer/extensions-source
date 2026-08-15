# Source host contract

`source-host-contracts.json` 是 13 個官方 Source 的人工審核網路邊界，將 host 分成：

- `request`：extension 透過 Host broker 發出的唯讀 HTTP request。
- `resource`：icon、圖片與影片等由內容模型交給 Host 載入的資源。
- `external`：只在使用者手勢後交給安全外部連結流程的網址。
- `auth`：Host 管理的 WebView／cookie 登入範圍。

這份檔案不是 URL literal scanner，而是新版 signed network policy 的唯一人工審核來源。新增程式碼中的 URL 不會自動取得權限；修改者必須提出 call-path 或 fixture 證據並更新 explicit inventory。metadata producer 只會從這份已審核 contract 產生 v2 policy；`release-catalog.json` 的 request host mirror、capabilities 與 `policyHash` 必須完全相符。

`request.rules` 逐條綁定 exact hosts、`source_read` 的 `GET`／`HEAD`、path prefixes 與是否可攜帶該 Source 的 Host-owned cookie。規則沒有匹配或同時匹配多條時，Host 一律拒絕；resource、external、auth host 不會因此取得 request 權限。四個 scope 合計最多 32 個 exact DNS hosts，禁止 wildcard、IP literal、HTTP、POST 與未知 capability。

執行：

```shell
python3 scripts/source_host_contracts.py
python3 scripts/source_host_contracts.py --fail-on-unresolved
```

第一個命令驗證 schema 並列出差異；第二個命令供 admission gate 使用，只要還有未簽 request host、不安全 HTTP board、未記錄的 signed host、catalog 不是 v2，或 hash/capability 不一致，就以非零狀態結束。

`dynamicFromContent` 只允許出現在 resource/external/auth surface，表示 host 來自網站回應，不能轉換成 request authority。request surface 永遠不得以內容或 literal 掃描動態擴權。

Komica Sora 已移除 5 個無法在 HTTPS-only Host policy 下安全工作的舊 board：`TYPE-MOON`、`艦隊收藏`、`生活消費`、`藝術`、`圖書`。唯讀 live 檢查顯示 `acgspace.wsfun.com` 的 HTTPS 憑證已過期且目標回傳 404；`gzone-anime.info` 只提供 HTTP，HTTPS 無法連線。這些 host 不會留在 catalog 或 `blockedHttpHosts`，避免向使用者展示必然失敗的 board。
