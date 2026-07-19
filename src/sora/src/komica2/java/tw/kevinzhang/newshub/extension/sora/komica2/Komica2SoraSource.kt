package tw.kevinzhang.newshub.extension.sora.komica2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.sora.komica2.model.Komica2SoraBoards
import tw.kevinzhang.extension_api.model.Board as ExtBoard

class Komica2SoraSource : Source {
    override val id = Komica2SoraBoards.SOURCE_ID
    override val name = "Komica2 Sora"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl: String = "https://komica1.org/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false
    private lateinit var client: OkHttpClient

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoards(): List<ExtBoard> = Komica2SoraBoards.all

    override suspend fun getThreadSummaries(
        board: ExtBoard,
        page: Int,
    ): List<ThreadSummary> = withContext(Dispatchers.IO) {
        val supportedBoard = Komica2SoraBoards.all.first { it.url == board.url }
        val req = Komica2SoraFactory().createThreadSummariesRequestBuilder(supportedBoard)
            .setPage(page)
            .build()

        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = Komica2SoraFactory().createThreadUrlParser()
        val thread = Komica2SoraFactory().createThreadSummariesParser(urlParser).parse(response.body!!, req)

        thread.map { kPost ->
            val boardUrl = Komica2SoraFactory().normalizeUrl(board.url.toHttpUrl())
            val postUrl = Komica2SoraFactory().normalizeUrl(kPost.url.toHttpUrl())
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

    override suspend fun getThread(summary: ThreadSummary): Thread = withContext(Dispatchers.IO) {
        val supportedBoard = Komica2SoraBoards.all.first { it.url == summary.boardUrl }
        val req = Komica2SoraFactory().createThreadRequestBuilder(supportedBoard)
            .setUrl(summary.id.toHttpUrl())
            .build()

        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = Komica2SoraFactory().createThreadUrlParser()
        val posts = Komica2SoraFactory().createThreadParser(urlParser).parse(response.body!!, req)

        Thread(
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
