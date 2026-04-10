package tw.kevinzhang.extension.gamer

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tw.kevinzhang.extension_api.AuthResult
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.SourceContext
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.Comment
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.gamer_api.AuthRequiredException
import tw.kevinzhang.gamer_api.GamerApi
import tw.kevinzhang.gamer_api.model.GImageInfo
import tw.kevinzhang.gamer_api.model.GLink
import tw.kevinzhang.gamer_api.model.GParagraph
import tw.kevinzhang.gamer_api.model.GQuote
import tw.kevinzhang.gamer_api.model.GReplyTo
import tw.kevinzhang.gamer_api.model.GText

private const val TAG = "GamerSource"

class GamerSource : Source {
    private val gamerApi = GamerApi(OkHttpClient())

    override val id = "tw.kevinzhang.gamer"
    override val name = "Gamer 巴哈姆特"
    override val language = "zh-TW"
    override val version = 1
    override val iconUrl: String = "https://i2.bahamut.com.tw/apple-touch-icon-72x72.png"
    override val supportsCommentPagination: Boolean = false
    override val alwaysUseRawImage: Boolean = false
    override val requiresLogin = true
    override val loginUrl = "https://user.gamer.com.tw/login.php"
    override val loginPageLoadJs: String? = null

    private var sourceContext: SourceContext? = null

    override fun onAttach(context: SourceContext) {
        sourceContext = context
    }

    override suspend fun getBoards(): List<Board> =
        gamerApi.getAllBoards().map { gBoard ->
            Board(
                sourceId = id,
                url = gBoard.url,
                name = gBoard.name,
            )
        }

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        return try {
            fetchThreadSummaries(board, page)
        } catch (e: AuthRequiredException) {
            Log.w(TAG, "Auth required for board=${board.url}, requesting auth…")
            val result = requestAuth()
            if (result is AuthResult.Success) fetchThreadSummaries(board, page)
            else emptyList()
        }
    }

    private suspend fun fetchThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        val req = gamerApi.getRequestBuilder()
            .setUrl(board.url.toHttpUrl())
            .setPage(page.takeIf { it != 0 })
            .build()
        val result = gamerApi.getThreadSummaries(req)
        return result.map { gNews ->
            ThreadSummary(
                sourceId = id,
                boardUrl = board.url,
                id = gNews.url,
                title = gNews.title,
                author = gNews.posterName,
                createdAt = null,
                commentCount = gNews.interactions,
                thumbnail = gNews.thumb,
                rawImage = gNews.thumb,
                previewContent = listOf(Paragraph.Text(gNews.preview)),
                sourceIconUrl = iconUrl,
                replyCount = null,
            )
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        return try {
            fetchThread(summary)
        } catch (e: AuthRequiredException) {
            Log.w(TAG, "Auth required for thread=${summary.id}, requesting auth…")
            val result = requestAuth()
            if (result is AuthResult.Success) fetchThread(summary)
            else Thread(id = summary.id, url = getWebUrl(summary), title = summary.title, posts = emptyList())
        }
    }

    private suspend fun fetchThread(summary: ThreadSummary): Thread {
        val req = gamerApi.getRequestBuilder()
            .setUrl(summary.id.toHttpUrl())
            .setPage(1)
            .build()
        val gPosts = gamerApi.getThread(req)
        return Thread(
            id = summary.id,
            url = getWebUrl(summary),
            title = summary.title,
            posts = gPosts.map { gPost ->
                val comments = if (gPost.commentsUrl.isNotBlank()) {
                    try {
                        val commentReq = gamerApi.getRequestBuilder()
                            .setUrl(gPost.commentsUrl.toHttpUrl())
                            .build()
                        gamerApi.getAllComment(commentReq).map { gComment ->
                            Comment(
                                id = gComment.sn,
                                author = gComment.nick,
                                createdAt = gComment.wtime.toLongOrNull()?.times(1000),
                                content = listOf(Paragraph.Text(gComment.content)),
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                Post(
                    id = gPost.id,
                    author = gPost.posterName,
                    createdAt = gPost.createdAt,
                    thumbnail = gPost.content.filterIsInstance<GImageInfo>().firstOrNull()?.thumb,
                    content = gPost.content.map { it.toExtParagraph() },
                    comments = comments,
                    rawHtml = gPost.rawHtml,
                    sourceIconUrl = iconUrl,
                    replyCount = null,
                )
            },
        )
    }

    override fun getWebUrl(summary: ThreadSummary): String = summary.id

    private suspend fun requestAuth(): AuthResult =
        sourceContext?.requestWebViewAuth(loginUrl = loginUrl) ?: AuthResult.Cancelled
}

// Inline paragraph mapper (replaces ParagraphMapper from extensions-builtin)
private fun GParagraph.toExtParagraph(): tw.kevinzhang.extension_api.model.Paragraph = when (this) {
    is GQuote   -> tw.kevinzhang.extension_api.model.Paragraph.Quote(content)
    is GReplyTo -> tw.kevinzhang.extension_api.model.Paragraph.ReplyTo(targetId = content)
    is GText    -> tw.kevinzhang.extension_api.model.Paragraph.Text(content)
    is GImageInfo -> tw.kevinzhang.extension_api.model.Paragraph.ImageInfo(thumb, raw)
    is GLink    -> tw.kevinzhang.extension_api.model.Paragraph.Link(content)
    else        -> throw IllegalArgumentException("Unknown GParagraph: $this")
}
