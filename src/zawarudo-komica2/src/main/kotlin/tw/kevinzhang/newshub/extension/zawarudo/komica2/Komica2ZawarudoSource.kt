package tw.kevinzhang.newshub.extension.zawarudo.komica2

import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.SessionAwareSource
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient
import tw.kevinzhang.newshub.extension.runtime.SourceParserContractException
import tw.kevinzhang.newshub.extension.runtime.requireSourceSuccess

class Komica2ZawarudoSource : SessionAwareSource {
    override val id = ZawarudoBoards.SOURCE_ID
    override val name = "Komica2 Zawarudo"
    override val language = "zh-TW"
    override val version = 3
    override val iconUrl: String = "https://majeur.zawarudo.org/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private val crawler = ZawarudoCrawler()
    private lateinit var client: OkHttpClient

    override fun onAttach(runtime: SourceRuntime) {
        client = runtime.brokerBackedHttpClient()
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = listOf(
        BoardCategory("general", "綜合"),
        BoardCategory("anime", "二次元"),
        BoardCategory("games", "遊戲"),
    )

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        val query = request.query.text.trim()
        val categorized = when (request.query.categoryId) {
            null -> ZawarudoBoards.all
            "general" -> ZawarudoBoards.all.filter { it.name == "詢問裡" }
            "anime" -> ZawarudoBoards.all.filter { it.name == "二次元獵奇" }
            "games" -> ZawarudoBoards.all.filter { it.name.contains("遊戲") }
            else -> emptyList()
        }
        val filtered = categorized.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val offset = request.pageToken?.toIntOrNull() ?: 0
        require(offset >= 0) { "Invalid board page token: ${request.pageToken}" }
        val boards = filtered.drop(offset).take(request.pageSize)
        val nextOffset = offset + boards.size
        return BoardPage(boards, nextOffset.takeIf { it < filtered.size }?.toString())
    }

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        val supportedBoard = ZawarudoBoards.requireByUrl(board.url)
        val request = crawler.boardRequest(supportedBoard.url, page)
        val posts = client.execute(request) { body -> crawler.parseBoard(body, request) }
        return posts.map { post -> post.toSummary(supportedBoard) }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        ZawarudoBoards.requireByUrl(summary.boardUrl)
        val request = crawler.threadRequest(summary.id)
        val posts = client.execute(request) { body -> crawler.parseThread(body, request) }
        return Thread(
            id = summary.id,
            url = getWebUrl(summary),
            title = summary.title,
            posts = posts.map { it.toPost() },
        )
    }

    override suspend fun getWebUrl(summary: ThreadSummary): String = summary.id

    private suspend fun <T> OkHttpClient.execute(
        request: okhttp3.Request,
        parse: (okhttp3.ResponseBody) -> T,
    ): T {
        val response = newCall(request).await()
        response.use {
            it.requireSourceSuccess()
            val body = it.body ?: throw SourceParserContractException("missing_response_body")
            return parse(body)
        }
    }

    private fun ZawarudoParsedPost.toSummary(board: Board): ThreadSummary {
        val image = content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()
        val threadUrl = url.substringBeforeLast('#')
        return ThreadSummary(
            sourceId = this@Komica2ZawarudoSource.id,
            boardUrl = board.url,
            id = threadUrl,
            title = title,
            author = poster,
            createdAt = createdAt,
            commentCount = replies,
            rawImage = image?.raw,
            thumbnail = image?.thumb,
            previewContent = content.map { it },
            sourceIconUrl = iconUrl,
            replyCount = replies.takeIf { it > 0 },
        )
    }

    private fun ZawarudoParsedPost.toPost(): Post {
        val image = content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()
        return Post(
            id = id,
            author = poster,
            createdAt = createdAt,
            thumbnail = image?.thumb,
            content = content.map { it },
            comments = emptyList(),
            sourceIconUrl = iconUrl,
            replyCount = replies.takeIf { it > 0 },
        )
    }
}
