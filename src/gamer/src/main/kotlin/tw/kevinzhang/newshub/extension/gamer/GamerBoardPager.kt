package tw.kevinzhang.newshub.extension.gamer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import tw.kevinzhang.extension_api.model.*
import tw.kevinzhang.gamer_api.interactor.GBoardDirectoryPage
import tw.kevinzhang.newshub.extension.runtime.BoardCatalogPaging

/** Keeps the remainder of a website page when the Host asks for fewer rows. */
internal class GamerBoardPager(private val sourceId: String) {
    private val mutex = Mutex()
    private var session = UUID.randomUUID().toString()
    private val pages = linkedMapOf<Int, GBoardDirectoryPage>()
    private var updatedAt = 0L
    suspend fun page(request: BoardPageRequest, apiPageSize: Int, load: suspend (Int) -> GBoardDirectoryPage): BoardPage = mutex.withLock {
        if (request.pageToken == null) {
            session = UUID.randomUUID().toString()
            pages.clear()
            updatedAt = System.currentTimeMillis()
        }
        // Each remote page has at most 1000 rows. Bind encoded position to query, epoch and session.
        val position = BoardCatalogPaging.offset(request, session)
        val remotePage = position / 1000 + 1
        val offset = position % 1000
        val chunk = pages[remotePage] ?: load(remotePage).also {
            require(it.boards.size < 1000) { "Remote board page is oversized" }
            pages[remotePage] = it
            while (pages.size > 2) pages.remove(pages.keys.first())
        }
        require(offset <= chunk.boards.size) { "Invalid remote board offset" }
        val values = chunk.boards.drop(offset).take(request.pageSize)
        val nextOffset = offset + values.size
        val next = when {
            nextOffset < chunk.boards.size -> (remotePage - 1) * 1000 + nextOffset
            chunk.itemCount >= apiPageSize -> remotePage * 1000
            else -> null
        }
        BoardPage(values.map { Board(sourceId, it.url, it.name, it.category) },
            next?.let { BoardCatalogPaging.token(request, session, it) },
            BoardCatalogInfo(BoardCatalogMode.REMOTE_SEARCH, updatedAt, false, BoardCatalogCoverage.AVAILABLE))
    }
}
