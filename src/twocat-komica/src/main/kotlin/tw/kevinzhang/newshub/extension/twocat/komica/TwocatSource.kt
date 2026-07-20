package tw.kevinzhang.newshub.extension.twocat.komica

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
import tw.kevinzhang.newshub.extension.twocat.komica.request.TwocatRequestBuilder
import tw.kevinzhang.extension_api.model.Board as ExtBoard

class TwocatSource : Source {
    override val id = TwocatBoardCatalog.SOURCE_ID
    override val name = "Twocat"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl: String = "https://2cat.uk/futaba.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false
    private lateinit var client: OkHttpClient

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = TWOCAT_CATEGORIES

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage =
        TwocatBoardCatalog.boards.toBoardPage(request) { board, categoryId ->
            when (categoryId) {
                "general" -> board.name !in ANIME_BOARDS && board.name !in GAME_BOARDS
                "anime" -> board.name in ANIME_BOARDS
                "games" -> board.name in GAME_BOARDS
                else -> false
            }
        }

    override suspend fun getThreadSummaries(board: ExtBoard, page: Int): List<ThreadSummary> {
        val twocatBoard = TwocatBoardCatalog.findByUrl(board.url)
        val req = TwocatFactory().createThreadSummariesRequestBuilder(twocatBoard)
            .setPage(page)
            .build()

        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = TwocatFactory().createUrlParser()
        val thread = TwocatFactory().createThreadSummariesParser(urlParser).parse(response.body!!, req)

        return thread.map { kPost ->
            val boardUrl =
                TwocatRequestBuilder().setUrl(board.url.toHttpUrl()).setPage(null)
                    .build().url.toString()
            val postUrl =
                TwocatRequestBuilder().setUrl(kPost.url.toHttpUrl()).setPage(null)
                    .build().url.toString()
            ThreadSummary(
                sourceId = id,
                boardUrl = boardUrl,
                id = postUrl,
                title = kPost.title,
                author = kPost.poster,
                createdAt = kPost.createdAt,
                commentCount = kPost.replies,
                thumbnail = kPost.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                rawImage = kPost.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.raw,
                previewContent = kPost.content.map { it },
                replyCount = kPost.replies.takeIf { it > 0 },
            )
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        TwocatBoardCatalog.findByUrl(summary.boardUrl)
        val req = TwocatFactory().createThreadRequestBuilder()
            .setUrl(summary.id.toHttpUrl())
            .build()

        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = TwocatFactory().createUrlParser()
        val posts = TwocatFactory().createThreadParser(urlParser).parse(response.body!!, req)

        return Thread(
            id = summary.id,
            url = getWebUrl(summary),
            title = summary.title,
            posts = posts.map { kPost ->
                Post(
                    id = kPost.id,
                    author = kPost.poster,
                    createdAt = kPost.createdAt,
                    thumbnail = kPost.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                    content = kPost.content.map { it },
                    comments = emptyList(),
                    replyCount = kPost.replies.takeIf { it > 0 },
                )
            },
        )
    }

    override fun getWebUrl(summary: ThreadSummary): String = summary.id
}

private val TWOCAT_CATEGORIES = listOf(
    BoardCategory("general", "綜合"),
    BoardCategory("anime", "二次元"),
    BoardCategory("games", "遊戲"),
)

private val GAME_BOARDS = setOf(
    "碧藍幻想", "手機遊戲", "網頁遊戲", "手機/平板遊戲", "體感遊戲", "女性向遊戲",
    "桌上遊戲", "Azur Lane",
)

private val ANIME_BOARDS = setOf("繪師版", "東方", "龍騎士07", "涼宮", "二次壁")

private fun List<ExtBoard>.toBoardPage(
    request: BoardPageRequest,
    inCategory: (ExtBoard, String) -> Boolean,
): BoardPage {
    val query = request.query.text.trim()
    val filtered = filter { board ->
        (request.query.categoryId == null || inCategory(board, request.query.categoryId!!)) &&
            (query.isEmpty() || board.name.contains(query, ignoreCase = true))
    }
    val offset = request.pageToken?.toIntOrNull() ?: 0
    require(offset >= 0) { "Invalid board page token: ${request.pageToken}" }
    val boards = filtered.drop(offset).take(request.pageSize)
    val nextOffset = offset + boards.size
    return BoardPage(boards, nextOffset.takeIf { it < filtered.size }?.toString())
}
