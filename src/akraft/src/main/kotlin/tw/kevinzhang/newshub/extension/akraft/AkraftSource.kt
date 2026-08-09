package tw.kevinzhang.newshub.extension.akraft

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

class AkraftSource : SessionAwareSource {
    override val id = AkraftBoards.SOURCE_ID
    override val name = "Akraft"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl = "https://www.akraft.net/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = true
    override val needsLogin = false

    private lateinit var client: OkHttpClient
    private val parser = AkraftParser()

    override fun onAttach(runtime: SourceRuntime) {
        client = runtime.brokerBackedHttpClient()
    }

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage =
        AkraftBoards.all.toBoardPage(request)

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        val supportedBoard = AkraftBoards.require(board.url)
        val request = AkraftRequestBuilder.board(supportedBoard.url, page)
        val posts = execute(request) { html -> parser.parseSummaries(html, request.url.toString()) }
        return posts.map { post ->
            val image = post.content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()
            ThreadSummary(
                sourceId = id,
                boardUrl = supportedBoard.url,
                id = post.url,
                title = post.title,
                author = post.author,
                createdAt = post.createdAt,
                commentCount = post.replies,
                thumbnail = image?.thumb,
                rawImage = image?.raw,
                previewContent = post.content,
                sourceIconUrl = iconUrl,
                replyCount = post.replies.takeIf { it > 0 },
            )
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        AkraftBoards.require(summary.boardUrl)
        val request = AkraftRequestBuilder.thread(summary.id)
        val posts = execute(request) { html -> parser.parseThread(html, request.url.toString()) }
        return Thread(
            id = summary.id,
            url = getWebUrl(summary),
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

    override suspend fun getWebUrl(summary: ThreadSummary): String = summary.id

    private suspend fun <T> execute(request: okhttp3.Request, parse: (String) -> T): T {
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${request.url}")
            return parse(response.body?.string().orEmpty())
        }
    }
}

private fun List<Board>.toBoardPage(request: BoardPageRequest): BoardPage {
    val filtered = filter { board ->
        request.query.text.trim().isEmpty() ||
            board.name.contains(request.query.text.trim(), ignoreCase = true)
    }
    val offset = request.pageToken?.toIntOrNull() ?: 0
    require(offset >= 0) { "Invalid board page token: ${request.pageToken}" }
    val boards = filtered.drop(offset).take(request.pageSize)
    val nextOffset = offset + boards.size
    return BoardPage(boards, nextOffset.takeIf { it < filtered.size }?.toString())
}
