package tw.kevinzhang.newshub.extension.wtako

import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.io.IOException

class WtakoSource : Source {
    override val id = WtakoBoardCatalog.SOURCE_ID
    override val name = "Wtako"
    override val language = "zh-TW"
    override val version = 1
    override val iconUrl = "https://kemono.wtako.net/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private lateinit var client: OkHttpClient
    private val parser = WtakoParser()

    override fun onAttach(client: OkHttpClient) {
        this.client = client
    }

    override suspend fun getBoards(): List<Board> = WtakoBoardCatalog.boards

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        WtakoBoardCatalog.findByUrl(board.url)
        val request = WtakoRequestBuilder.boardPage(board.url, page)
        val response = client.newCall(request).await()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${request.url}")
        val posts = response.use { parser.parseSummaries(it.body!!.string(), board.url) }
        return posts.map { post ->
            val image = post.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()
            ThreadSummary(
                sourceId = id,
                boardUrl = board.url,
                id = post.url,
                title = post.title,
                author = post.author,
                createdAt = post.createdAt,
                commentCount = post.replies,
                rawImage = image?.raw,
                thumbnail = image?.thumb,
                previewContent = post.content,
                sourceIconUrl = iconUrl,
                replyCount = post.replies.takeIf { it > 0 },
            )
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        WtakoBoardCatalog.findByUrl(summary.boardUrl)
        val request = WtakoRequestBuilder.thread(summary.id)
        val response = client.newCall(request).await()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${request.url}")
        val posts = response.use { parser.parseThread(it.body!!.string(), request.url.toString()) }
        return Thread(
            id = summary.id,
            url = summary.id,
            title = summary.title,
            posts = posts.map { post ->
                Post(
                    id = post.id,
                    author = post.author,
                    createdAt = post.createdAt,
                    thumbnail = post.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                    content = post.content,
                    comments = emptyList(),
                    sourceIconUrl = iconUrl,
                    replyCount = post.replies.takeIf { it > 0 },
                )
            },
        )
    }

    override fun getWebUrl(summary: ThreadSummary): String = summary.id
}
