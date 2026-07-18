package tw.kevinzhang.newshub.extension.twocat

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
import tw.kevinzhang.newshub.extension.twocat.request.TwocatRequestBuilder
import tw.kevinzhang.extension_api.model.Board as ExtBoard

class TwocatSource : Source {
    override val id = TwocatBoardCatalog.SOURCE_ID
    override val name = "twocat"
    override val language = "zh-TW"
    override val version = 1
    override val iconUrl: String = "https://2cat.uk/futaba.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false
    private lateinit var client: OkHttpClient

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoards(): List<ExtBoard> = TwocatBoardCatalog.boards

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
                thumbnail = kPost.content.filterIsInstance<KImageInfo>().firstOrNull()?.thumb,
                rawImage = kPost.content.filterIsInstance<KImageInfo>().firstOrNull()?.raw,
                previewContent = kPost.content.map { it.toExtParagraph() },
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
                    thumbnail = kPost.content.filterIsInstance<KImageInfo>().firstOrNull()?.thumb,
                    content = kPost.content.map { it.toExtParagraph() },
                    comments = emptyList(),
                    replyCount = kPost.replies.takeIf { it > 0 },
                )
            },
        )
    }

    override fun getWebUrl(summary: ThreadSummary): String = summary.id
}
