package tw.kevinzhang.newshub.extension.zawarudo.komica2

import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.extension_api.model.Paragraph

class Komica2ZawarudoSource : Source {
    override val id = ZawarudoBoards.SOURCE_ID
    override val name = "Komica2 Zawarudo"
    override val language = "zh-TW"
    override val version = 1
    override val iconUrl: String = "https://majeur.zawarudo.org/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private val crawler = ZawarudoCrawler()
    private lateinit var client: OkHttpClient

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoards(): List<Board> = ZawarudoBoards.all

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

    override fun getWebUrl(summary: ThreadSummary): String = summary.id

    private suspend fun <T> OkHttpClient.execute(
        request: okhttp3.Request,
        parse: (okhttp3.ResponseBody) -> T,
    ): T {
        val response = newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw HttpException(it.code, request.url.toString())
            return parse(checkNotNull(it.body) { "Empty response body: ${request.url}" })
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
