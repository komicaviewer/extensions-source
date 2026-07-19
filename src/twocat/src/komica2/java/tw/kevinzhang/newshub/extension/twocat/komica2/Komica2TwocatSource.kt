package tw.kevinzhang.newshub.extension.twocat.komica2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.Komica2PixmicatEngine
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards
import tw.kevinzhang.extension_api.model.Board as ExtBoard

class Komica2TwocatSource : Source {
    override val id = Komica2TwocatBoards.SOURCE_ID
    override val name = "Komica2 Twocat"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl = "https://2cat.uk/futaba.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private lateinit var client: OkHttpClient
    private val engine = Komica2PixmicatEngine()

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = listOf(
        BoardCategory("general", "綜合"),
        BoardCategory("anime", "二次元"),
    )

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        val query = request.query.text.trim()
        val categoryBoards = when (request.query.categoryId) {
            null -> Komica2TwocatBoards.all
            "anime" -> Komica2TwocatBoards.all.filter { it.name in setOf("動畫裡", "東方裡", "偽娘裡") }
            "general" -> Komica2TwocatBoards.all.filter { it.name !in setOf("動畫裡", "東方裡", "偽娘裡") }
            else -> emptyList()
        }
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
        val req = engine.createThreadSummariesRequestBuilder(supportedBoard)
            .setPage(page)
            .build()
        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())

        val parser = engine.createThreadSummariesParser(engine.createUrlParser())
        parser.parse(response.body!!, req).map { post ->
            ThreadSummary(
                sourceId = id,
                boardUrl = engine.normalizeUrl(board.url.toHttpUrl()),
                id = engine.normalizeUrl(post.url.toHttpUrl()),
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
        val req = engine.createThreadRequestBuilder(supportedBoard)
            .setUrl(summary.id.toHttpUrl())
            .build()
        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())

        val parser = engine.createThreadParser(engine.createUrlParser())
        val posts = parser.parse(response.body!!, req)
        Thread(
            id = summary.id,
            url = summary.id,
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

    override fun getWebUrl(summary: ThreadSummary): String = summary.id
}
