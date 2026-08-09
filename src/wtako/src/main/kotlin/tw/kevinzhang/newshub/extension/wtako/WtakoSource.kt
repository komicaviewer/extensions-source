package tw.kevinzhang.newshub.extension.wtako

import okhttp3.OkHttpClient
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.SessionAwareSource
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.io.IOException
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient

class WtakoSource : SessionAwareSource {
    override val id = WtakoBoardCatalog.SOURCE_ID
    override val name = "Wtako"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl = "https://kemono.wtako.net/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private lateinit var client: OkHttpClient
    private val parser = WtakoParser()

    override fun onAttach(runtime: SourceRuntime) {
        client = runtime.brokerBackedHttpClient().newBuilder()
            .addNetworkInterceptor(WtakoHttpsRedirectInterceptor())
            .build()
    }

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage =
        WtakoBoardCatalog.boards.toBoardPage(request)

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        val boardUrl = WtakoUrlPolicy.canonicalize(board.url)
        WtakoBoardCatalog.findByUrl(boardUrl)
        val request = WtakoRequestBuilder.boardPage(boardUrl, page)
        val response = client.newCall(request).await()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${request.url}")
        val posts = response.use { parser.parseSummaries(it.body!!.string(), boardUrl) }
        return posts.map { post ->
            val image = post.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()
            ThreadSummary(
                sourceId = id,
                boardUrl = boardUrl,
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

    override suspend fun getWebUrl(summary: ThreadSummary): String = WtakoUrlPolicy.canonicalize(summary.id)
}

private fun List<Board>.toBoardPage(request: BoardPageRequest): BoardPage {
    val query = request.query.text.trim()
    val filtered = filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
    val offset = request.pageToken?.toIntOrNull() ?: 0
    require(offset >= 0) { "Invalid board page token: ${request.pageToken}" }
    val boards = filtered.drop(offset).take(request.pageSize)
    val nextOffset = offset + boards.size
    return BoardPage(boards, nextOffset.takeIf { it < filtered.size }?.toString())
}
