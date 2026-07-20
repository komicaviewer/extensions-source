package tw.kevinzhang.newshub.extension.wtako

import tw.kevinzhang.extension_api.model.Board

/** The three independent Pixmicat installations served by this extension. */
internal object WtakoBoardCatalog {
    const val SOURCE_ID = "tw.kevinzhang.wtako"

    val boards = listOf(
        board("祭典(双猫村祭典広場)", "https://rthost.win/sd"),
        board("Strike-Witches", "https://www.karlsland.net/sw"),
        board("獸", "https://kemono.wtako.net/kemono"),
    )

    private fun board(name: String, url: String) = Board(SOURCE_ID, url, name)

    fun findByUrl(url: String): Board {
        val canonical = WtakoUrlPolicy.canonicalize(url)
        return boards.first { it.url == canonical }
    }
}
