package tw.kevinzhang.newshub.extension.gamer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import tw.kevinzhang.extension_api.model.*
import tw.kevinzhang.gamer_api.interactor.GBoardDirectoryPage
import tw.kevinzhang.newshub.extension.runtime.BoardCatalogPaging

/** Bounded independent query sessions: another consumer's first page must not revoke a token. */
internal class GamerBoardPager(private val sourceId: String) {
    private class Session(val id: String, val queryKey: String, val epoch: String?, val updatedAt: Long) {
        val pages = linkedMapOf<Int, GBoardDirectoryPage>()
    }
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, Session>()
    private var activeEpoch: String? = null

    suspend fun page(request: BoardPageRequest, apiPageSize: Int, load: suspend (Int) -> GBoardDirectoryPage): BoardPage = mutex.withLock {
        if (request.contextEpoch != null && request.contextEpoch != activeEpoch) {
            sessions.entries.removeAll { it.value.epoch != null }
            activeEpoch = request.contextEpoch
        }
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value.updatedAt !in 0 until 86_400_000L }
        val queryKey = BoardCatalogPaging.key(request, "gamer-query-v1")
        val session: Session
        val position: Int
        if (request.pageToken == null) {
            if (request.refresh) sessions.entries.removeAll { it.value.queryKey == queryKey }
            session = Session(UUID.randomUUID().toString(), queryKey, request.contextEpoch, now)
            sessions[session.id] = session
            while (sessions.size > 20) sessions.remove(sessions.keys.first())
            position = 0
        } else {
            session = requireNotNull(sessions.values.firstOrNull {
                it.queryKey == queryKey && runCatching { BoardCatalogPaging.offset(request, it.id) }.isSuccess
            }) { "Catalog continuation expired; reload first page" }
            position = BoardCatalogPaging.offset(request, session.id)
        }
        val remotePage = position / 1000 + 1
        val offset = position % 1000
        val chunk = session.pages[remotePage] ?: load(remotePage).also {
            require(it.boards.size < 1000) { "Remote board page is oversized" }
            session.pages[remotePage] = it
            while (session.pages.size > 2) session.pages.remove(session.pages.keys.first())
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
            next?.let { BoardCatalogPaging.token(request, session.id, it) },
            BoardCatalogInfo(BoardCatalogMode.REMOTE_SEARCH, session.updatedAt, false,
                BoardCatalogCoverage.AVAILABLE, request.contextEpoch))
    }
}
