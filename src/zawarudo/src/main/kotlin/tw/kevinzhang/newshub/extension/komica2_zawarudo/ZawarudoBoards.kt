package tw.kevinzhang.newshub.extension.zawarudo.komica2

import tw.kevinzhang.extension_api.model.Board

internal object ZawarudoBoards {
    const val SOURCE_ID = "tw.kevinzhang.komica2.zawarudo"

    val all: List<Board> = listOf(
        Board(SOURCE_ID, "https://majeur.zawarudo.org/demande", "詢問裡"),
        Board(SOURCE_ID, "https://majeur.zawarudo.org/guro", "二次元獵奇"),
        Board(SOURCE_ID, "https://majeur.zawarudo.org/hgame", "遊戲裡避難版"),
    )

    fun requireByUrl(url: String): Board =
        all.firstOrNull { normalizeBoardUrl(it.url) == normalizeBoardUrl(url) }
            ?: throw IllegalArgumentException("Unsupported Komica2 Zawarudo board: $url")

    private fun normalizeBoardUrl(url: String) = url.trimEnd('/')
}
