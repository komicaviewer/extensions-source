package tw.kevinzhang.newshub.extension.ptt

import org.jsoup.Jsoup
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest

/** Parses PTT's live popular-board page and applies the host's query/page contract. */
internal object PttBoardCatalog {
    const val SOURCE_ID = "tw.kevinzhang.newshub.extension.ptt"

    fun parsePopular(html: String): List<Board> {
        val document = Jsoup.parse(html, PttUrlPolicy.popularBoardsUrl())
        return document.select("div.b-ent a.board").mapNotNull { anchor ->
            val name = anchor.selectFirst("div.board-name")?.text()?.trim().orEmpty()
            val resolved = anchor.absUrl("href")
            if (!PttUrlPolicy.isBoardName(name) || PttUrlPolicy.boardNameFromUrl(resolved) != name) {
                return@mapNotNull null
            }
            val boardClass = anchor.selectFirst("div.board-class")?.text()?.trim().orEmpty()
            val title = anchor.selectFirst("div.board-title")?.text()?.trim()?.removePrefix("◎").orEmpty()
            board(name, listOf(boardClass, title).filter(String::isNotBlank).joinToString(" · "))
        }.distinctBy(Board::url)
    }

    fun page(request: BoardPageRequest, popular: List<Board>, exact: Board? = null): BoardPage {
        val query = request.query.text.trim()
        val matches = (if (query.isEmpty()) popular else popular.filter {
            it.name.contains(query, ignoreCase = true) || it.description.orEmpty().contains(query, ignoreCase = true)
        }).toMutableList()
        if (exact != null && matches.none { it.url == exact.url }) {
            matches.add(0, exact)
        }
        val offset = request.pageToken?.toIntOrNull() ?: 0
        require(offset >= 0) { "Invalid board page token: ${request.pageToken}" }
        val items = matches.drop(offset).take(request.pageSize)
        val next = (offset + items.size).takeIf { it < matches.size }?.toString()
        return BoardPage(items, next)
    }

    fun exactBoard(name: String): Board = board(name, "PTT 看板")

    fun isBoardPage(html: String, expectedName: String): Boolean {
        val document = Jsoup.parse(html, PttUrlPolicy.boardUrl(expectedName))
        val boardUrl = document.selectFirst("#topbar a.board, a.board")?.absUrl("href") ?: return false
        return PttUrlPolicy.boardNameFromUrl(boardUrl) == expectedName
    }

    fun validate(board: Board): String {
        require(board.sourceId == SOURCE_ID) { "Board belongs to a different source" }
        return PttUrlPolicy.boardNameFromUrl(board.url)
            ?: throw IllegalArgumentException("Untrusted PTT board URL: ${board.url}")
    }

    private fun board(name: String, description: String): Board = Board(
        sourceId = SOURCE_ID,
        url = PttUrlPolicy.boardUrl(name),
        name = name,
        description = description,
    )
}
