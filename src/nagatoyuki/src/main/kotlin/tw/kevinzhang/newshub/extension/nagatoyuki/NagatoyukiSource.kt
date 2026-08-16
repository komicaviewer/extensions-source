package tw.kevinzhang.newshub.extension.nagatoyuki

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
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient
import tw.kevinzhang.newshub.extension.runtime.requireSourceSuccess

class NagatoyukiSource : SessionAwareSource {
    override val id = NagatoyukiBoardCatalog.SOURCE_ID
    override val name = "Nagatoyuki"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl = "https://eclair.nagatoyuki.org/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false
    private lateinit var client: OkHttpClient
    private val parser = NagatoyukiParser()

    override fun onAttach(runtime: SourceRuntime) {
        client = runtime.brokerBackedHttpClient()
    }

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage =
        NagatoyukiBoardCatalog.boards.toBoardPage(request)

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        NagatoyukiBoardCatalog.findByUrl(board.url)
        val request = NagatoyukiRequestBuilder.summaries(board.url, page)
        val response = client.newCall(request).await()
        response.use {
            it.requireSourceSuccess()
            return parser.parseSummaries(it.body!!.string(), board.url).map { post -> post.toSummary(board) }
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        NagatoyukiBoardCatalog.findByUrl(summary.boardUrl)
        val request = NagatoyukiRequestBuilder.thread(summary.id)
        val response = client.newCall(request).await()
        response.use {
            it.requireSourceSuccess()
            val posts = parser.parseThread(it.body!!.string(), summary.id)
            return Thread(
                id = summary.id,
                url = getWebUrl(summary),
                title = summary.title,
                posts = posts.map { post -> post.toPost() },
            )
        }
    }

    override suspend fun getWebUrl(summary: ThreadSummary) = summary.id

    private fun NagatoyukiParsedPost.toSummary(board: Board): ThreadSummary {
        val image = content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()
        return ThreadSummary(
            sourceId = this@NagatoyukiSource.id,
            boardUrl = board.url,
            id = url,
            title = title,
            author = author,
            createdAt = createdAt,
            commentCount = replies,
            thumbnail = image?.thumb,
            rawImage = image?.raw,
            previewContent = content,
            sourceIconUrl = iconUrl,
            replyCount = replies.takeIf { it > 0 },
        )
    }

    private fun NagatoyukiParsedPost.toPost(): Post {
        val image = content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()
        return Post(
            id = id,
            author = author,
            createdAt = createdAt,
            thumbnail = image?.thumb,
            content = content,
            comments = emptyList(),
            sourceIconUrl = iconUrl,
            replyCount = replies.takeIf { it > 0 },
        )
    }
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
