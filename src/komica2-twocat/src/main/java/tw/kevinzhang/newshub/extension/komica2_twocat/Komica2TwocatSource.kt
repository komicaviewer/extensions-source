package tw.kevinzhang.newshub.extension.komica2_twocat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.komica_api.HttpException
import tw.kevinzhang.komica_api.model.KImageInfo
import tw.kevinzhang.komica_api.model.toExtParagraph
import tw.kevinzhang.komica_api.pixmicat.Komica2PixmicatEngine
import tw.kevinzhang.newshub.extension.komica2_twocat.model.Komica2TwocatBoards
import tw.kevinzhang.extension_api.model.Board as ExtBoard

class Komica2TwocatSource : Source {
    override val id = Komica2TwocatBoards.SOURCE_ID
    override val name = "komica2 twocat"
    override val language = "zh-TW"
    override val version = 1
    override val iconUrl = "https://2cat.uk/futaba.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private lateinit var client: OkHttpClient
    private val engine = Komica2PixmicatEngine()

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoards(): List<ExtBoard> = Komica2TwocatBoards.all

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
                thumbnail = post.content.filterIsInstance<KImageInfo>().firstOrNull()?.thumb,
                rawImage = post.content.filterIsInstance<KImageInfo>().firstOrNull()?.raw,
                previewContent = post.content.map { it.toExtParagraph() },
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
                    thumbnail = post.content.filterIsInstance<KImageInfo>().firstOrNull()?.thumb,
                    content = post.content.map { it.toExtParagraph() },
                    comments = emptyList(),
                    replyCount = post.replies.takeIf { it > 0 },
                )
            },
        )
    }

    override fun getWebUrl(summary: ThreadSummary): String = summary.id
}
