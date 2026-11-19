package tw.kevinzhang.newshub.extension.twocat.komica2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.SessionAwareSource
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.Komica2PixmicatEngine
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards
import tw.kevinzhang.newshub.extension.twocat.komica2.model.TwocatGatedBoard
import tw.kevinzhang.newshub.extension.twocat.komica2.session.TwocatBoardSession
import tw.kevinzhang.newshub.extension.twocat.komica2.session.summariesUrl
import tw.kevinzhang.newshub.extension.twocat.komica2.session.threadUrl
import okhttp3.Request
import tw.kevinzhang.extension_api.model.Board as ExtBoard
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient
import tw.kevinzhang.newshub.extension.runtime.requireSourceSuccess

class Komica2TwocatSource : SessionAwareSource {
    override val id = Komica2TwocatBoards.SOURCE_ID
    override val name = "Komica2 Twocat"
    override val language = "zh-TW"
    override val version = 5
    override val iconUrl = "https://2cat.uk/futaba.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private lateinit var client: OkHttpClient
    private lateinit var session: TwocatBoardSession
    private val engine = Komica2PixmicatEngine()

    override fun onAttach(runtime: SourceRuntime) {
        client = runtime.brokerBackedHttpClient()
        session = TwocatBoardSession(client)
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = emptyList()

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        val query = request.query.text.trim()
        val categoryBoards = if (request.query.categoryId == null) Komica2TwocatBoards.all else emptyList()
        val filtered = categoryBoards.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val offset = request.pageToken?.toIntOrNull() ?: 0
        require(offset >= 0) { "Invalid board page token: ${request.pageToken}" }
        val boards = filtered.drop(offset).take(request.pageSize)
        val nextOffset = offset + boards.size
        return BoardPage(boards, nextOffset.takeIf { it < filtered.size }?.toString())
    }

    override suspend fun getThreadSummaries(
        board: ExtBoard,
        page: Int,
    ): List<ThreadSummary> = withContext(Dispatchers.IO) {
        val supportedBoard = Komica2TwocatBoards.all.first { it.url == board.url }
        val gated = Komica2TwocatBoards.gated(supportedBoard.url)
        val (req, response) = if (gated == null) {
            val req = engine.createThreadSummariesRequestBuilder(supportedBoard)
                .setPage(page)
                .build()
            req to client.newCall(req).await().requireSourceSuccess()
        } else {
            fetchGated(gated) { decoy -> gated.summariesUrl(decoy, page) }
        }

        val parser = engine.createThreadSummariesParser(engine.createUrlParser())
        parser.parse(response.body!!, req).map { post ->
            ThreadSummary(
                sourceId = id,
                boardUrl = engine.normalizeUrl(board.url.toHttpUrl()),
                id = gated?.let { "https://2cat.uk/${it.slug}/?res=${post.id}" }
                    ?: engine.normalizeUrl(post.url.toHttpUrl()),
                title = post.title,
                author = post.poster,
                createdAt = post.createdAt,
                commentCount = post.replies,
                thumbnail = post.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                rawImage = post.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.raw,
                previewContent = post.content.map { it },
                replyCount = post.replies.takeIf { it > 0 },
            )
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread = withContext(Dispatchers.IO) {
        val supportedBoard = Komica2TwocatBoards.all.first { it.url == summary.boardUrl }
        val gated = Komica2TwocatBoards.gated(supportedBoard.url)
        val (req, response) = if (gated == null) {
            val req = engine.createThreadRequestBuilder(supportedBoard)
                .setUrl(summary.id.toHttpUrl())
                .build()
            req to client.newCall(req).await().requireSourceSuccess()
        } else {
            fetchGated(gated) { decoy -> gated.threadUrl(decoy, summary.id) }
        }

        val parser = engine.createThreadParser(engine.createUrlParser())
        val posts = parser.parse(response.body!!, req)
        Thread(
            id = summary.id,
            url = if (gated != null) summary.boardUrl else summary.id,
            title = summary.title,
            posts = posts.map { post ->
                Post(
                    id = post.id,
                    author = post.poster,
                    createdAt = post.createdAt,
                    thumbnail = post.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                    content = post.content.map { it },
                    comments = emptyList(),
                    replyCount = post.replies.takeIf { it > 0 },
                )
            },
        )
    }

    /** Gated threads only open inside a bound 2cat.uk session, so the browser gets the board entry. */
    override suspend fun getWebUrl(summary: ThreadSummary): String =
        if (Komica2TwocatBoards.gated(summary.boardUrl) != null) summary.boardUrl else summary.id

    private suspend fun fetchGated(
        board: TwocatGatedBoard,
        url: (decoy: String) -> okhttp3.HttpUrl,
    ): Pair<Request, okhttp3.Response> {
        var request: Request? = null
        val response = session.withBoard(board) { decoy ->
            Request.Builder().url(url(decoy)).build()
                .also { request = it }
                .let { client.newCall(it).await() }
        }
        return checkNotNull(request) to response
    }
}
