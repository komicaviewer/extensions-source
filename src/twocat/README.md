# twocat

Twocat 是 Android library，依 `engine` flavor 提供兩個 Source：

- `src/komica`：`TwocatSource`，由 `komica.apk` 打包。
- `src/komica2`：`Komica2TwocatSource` 與站點專屬 Pixmicat parser，
  由 `komica2.apk` 打包。

Parser 直接使用 `extension-api` model，不依賴共用 Komica 中介模型。
