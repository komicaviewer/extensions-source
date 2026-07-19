package tw.kevinzhang.newshub.extension.sora.komica

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Board as ExtBoard

class SoraSource : Source {
    override val id = SoraBoardCatalog.SOURCE_ID
    override val name = "Sora"
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

    override suspend fun getBoards(): List<ExtBoard> = SoraBoardCatalog.boards

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
