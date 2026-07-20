package tw.kevinzhang.newshub.extension.mobile01

import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest

/**
 * Curated, verified public leaf boards rather than a claim to be Mobile01's complete catalog.
 * Marketplace, VIP, classifieds, and account-only areas are deliberately absent.
 */
internal object Mobile01BoardCatalog {
    const val SOURCE_ID = "tw.kevinzhang.mobile01"

    private data class Entry(val category: String, val id: Int, val name: String, val description: String)

    private val entries = listOf(
        Entry("digital", 383, "iPhone", "Apple iPhone 討論"),
        Entry("digital", 567, "MOTOROLA", "MOTOROLA 手機討論"),
        Entry("digital", 350, "電腦螢幕", "螢幕、顯示器與色彩校正"),
        Entry("digital", 300, "作業系統", "Windows、macOS、Linux 與系統軟體"),
        Entry("digital", 514, "其他電腦綜合討論", "電腦相關的綜合討論"),
        Entry("digital", 174, "自組電腦分享", "桌機組裝、零件與效能分享"),
        Entry("motorcycle", 266, "輕型與重型機車綜合", "輕型機車、重型機車與騎乘生活"),
        Entry("motorcycle", 265, "人身安全部品", "安全帽、防護衣與騎士裝備"),
        Entry("motorcycle", 671, "機車行車記錄器綜合", "機車行車記錄器與周邊設備"),
        Entry("lifestyle", 283, "電腦遊戲", "PC 遊戲與遊戲硬體討論"),
        Entry("lifestyle", 594, "閱讀與創作", "閱讀、寫作與創作交流"),
        Entry("lifestyle", 356, "房地產資訊", "房市、購屋與居住資訊"),
        Entry("community", 454, "台北市", "台北市地方生活與話題"),
        Entry("community", 37, "閒聊與趣味", "日常話題與社群交流"),
        Entry("community", 638, "台灣新聞", "台灣新聞與公共議題"),
        Entry("outdoor", 776, "GARMIN", "GARMIN 運動、導航與穿戴裝置"),
    )

    private val categoryNames = linkedMapOf(
        "digital" to "數位科技",
        "motorcycle" to "機車",
        "lifestyle" to "生活與休閒",
        "community" to "地方與社群",
        "outdoor" to "運動與戶外",
    )

    fun categories(): List<BoardCategory> = categoryNames.map { (id, name) -> BoardCategory(id, name) }

    fun page(request: BoardPageRequest): BoardPage {
        val query = request.query.text.trim()
        val filtered = entries.asSequence()
            .filter { request.query.categoryId == null || it.category == request.query.categoryId }
            .filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
            .map(::toBoard)
            .toList()
        val offset = request.pageToken?.toIntOrNull() ?: 0
        require(offset >= 0) { "Invalid Mobile01 board page token: ${request.pageToken}" }
        val boards = filtered.drop(offset).take(request.pageSize)
        return BoardPage(boards, (offset + boards.size).takeIf { it < filtered.size }?.toString())
    }

    fun validate(board: Board): Int {
        require(board.sourceId == SOURCE_ID) { "Board belongs to a different source" }
        val id = Mobile01UrlPolicy.boardId(board.url)
            ?: throw IllegalArgumentException("Untrusted Mobile01 board URL: ${board.url}")
        require(entries.any { it.id == id }) { "Mobile01 board is not in the public catalog: $id" }
        return id
    }

    private fun toBoard(entry: Entry) = Board(
        sourceId = SOURCE_ID,
        url = Mobile01UrlPolicy.boardUrl(entry.id),
        name = entry.name,
        description = entry.description,
    )
}
