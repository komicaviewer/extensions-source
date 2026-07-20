package tw.kevinzhang.newshub.extension.nagatoyuki

import tw.kevinzhang.extension_api.model.Board

internal object NagatoyukiBoardCatalog {
    const val SOURCE_ID = "tw.kevinzhang.nagatoyuki"

    val boards = listOf(
        board("COSPLAY", "https://selene.zawarudo.org/costumade"),
        board("線上繪圖", "https://selene.zawarudo.org/dessin"),
        board("MMD/Vocaloid", "https://selene.zawarudo.org/avenir"),
        board("海外", "https://eclair.nagatoyuki.org/outremer"),
        board("流言終結", "https://eclair.nagatoyuki.org/myth"),
        board("相談", "https://eclair.nagatoyuki.org/conseil"),
        board("安價", "https://eclair.nagatoyuki.org/ancre"),
        board("蔚藍檔案", "https://www.gomiga.org/bluearchive"),
        board("酒", "https://eclair.nagatoyuki.org/beverage"),
    )

    fun findByUrl(url: String): Board = boards.first { it.url == url.trimEnd('/') }

    private fun board(name: String, url: String) = Board(SOURCE_ID, url, name)
}
