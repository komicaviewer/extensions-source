package tw.kevinzhang.newshub.extension.sora.komica

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
import tw.kevinzhang.extension_api.model.Board as ExtBoard

class SoraSource : Source {
    override val id = SoraBoardCatalog.SOURCE_ID
    override val name = "Sora"
    override val language = "zh-TW"
    override val version = 3
    override val iconUrl: String = "https://komica1.org/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false
    private lateinit var client: OkHttpClient

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = KOMICA_CATEGORIES

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage =
        SoraBoardCatalog.boards.toBoardPage(request) { board, categoryId ->
            when (categoryId) {
                "general" -> board.name !in ANIME_BOARDS && board.name !in GAME_BOARDS
                "anime" -> board.name in ANIME_BOARDS
                "games" -> board.name in GAME_BOARDS
                else -> false
            }
        }

    override suspend fun getThreadSummaries(board: ExtBoard, page: Int): List<ThreadSummary> {
        val supportedBoard = SoraBoardCatalog.findByUrl(board.url)
        val req = SoraFactory().createThreadSummariesRequestBuilder(supportedBoard)
            .setPage(page)
            .build()

        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = SoraFactory().createUrlParser()
        val thread = SoraFactory().createThreadSummariesParser(urlParser).parse(response.body!!, req)

        return thread.map { kPost ->
            ThreadSummary(
                sourceId = id,
                boardUrl = board.url,
                id = kPost.url,
                title = kPost.title,
                author = kPost.poster,
                createdAt = kPost.createdAt,
                commentCount = 0,
                thumbnail = kPost.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                rawImage = kPost.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.raw,
                previewContent = kPost.content.map { it },
                sourceIconUrl = iconUrl,
                replyCount = kPost.replies.takeIf { it > 0 },
            )
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        SoraBoardCatalog.findByUrl(summary.boardUrl)
        val req = SoraFactory().createThreadRequestBuilder(summary.id.toHttpUrl()).build()

        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = SoraFactory().createUrlParser()
        val thread = SoraFactory().createThreadParser(urlParser).parse(response.body!!, req)

        return Thread(
            id = summary.id,
            url = getWebUrl(summary),
            title = summary.title,
            posts = thread.map { kPost ->
                Post(
                    id = kPost.id,
                    author = kPost.poster,
                    createdAt = kPost.createdAt,
                    thumbnail = kPost.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                    content = kPost.content.map { it },
                    comments = emptyList(),
                    sourceIconUrl = iconUrl,
                    replyCount = kPost.replies.takeIf { it > 0 },
                )
            },
        )
    }

    override fun getWebUrl(summary: ThreadSummary): String = summary.id
}

private val KOMICA_CATEGORIES = listOf(
    BoardCategory("general", "綜合"),
    BoardCategory("anime", "二次元"),
    BoardCategory("games", "遊戲"),
)

private val GAME_BOARDS = setOf(
    "艦隊收藏", "獨立遊戲", "遊戲設計", "GTA", "World of Tanks", "戰地風雲",
    "戰爭雷霆", "戰機世界", "戰艦世界", "KOEI",
)

private val ANIME_BOARDS = setOf(
    "新番捏他", "四格", "女性角色", "男性角色", "新番實況", "模型", "蘿蔔", "鋼普拉",
    "特攝", "TYPE-MOON", "Vtuber", "奇幻/科幻", "掛圖", "小說", "人外", "Homestuck",
    "御姊", "機娘", "巫女",
)

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
