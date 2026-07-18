package tw.kevinzhang.newshub.extension.gamer

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tw.kevinzhang.extension_api.AuthSpec
import tw.kevinzhang.extension_api.AuthenticatedSource
import tw.kevinzhang.extension_api.AuthenticationRequiredException
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.Comment
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.gamer_api.GamerApi
import tw.kevinzhang.gamer_api.model.GImageInfo
import tw.kevinzhang.gamer_api.model.GLink
import tw.kevinzhang.gamer_api.model.GParagraph
import tw.kevinzhang.gamer_api.model.GQuote
import tw.kevinzhang.gamer_api.model.GReplyTo
import tw.kevinzhang.gamer_api.model.GText
import tw.kevinzhang.gamer_api.model.GVideoInfo
import tw.kevinzhang.gamer_api.model.GVideoSite

class GamerSource : AuthenticatedSource {
    private var gamerApi = GamerApi(OkHttpClient())
    private var authenticationSession: AuthenticationSession? = null

    override val id = "tw.kevinzhang.newshub.extension.gamer"
    override val name = "Gamer 巴哈姆特"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl: String = "https://i2.bahamut.com.tw/apple-touch-icon-72x72.png"
    override val supportsCommentPagination: Boolean = false
    override val alwaysUseRawImage: Boolean = false
    override val needsLogin = false
    override val authSpec = AuthSpec.WebCookie(
        loginUrl = "https://user.gamer.com.tw/login.php",
        allowedHosts = setOf(
            "user.gamer.com.tw",
            "forum.gamer.com.tw",
            "www.gamer.com.tw",
        ),
        cookieOrigins = setOf(
            "https://user.gamer.com.tw",
            "https://forum.gamer.com.tw",
        ),
        cookieDomains = setOf("gamer.com.tw"),
    )

    /** Uses the host's source-scoped client, so Gamer cookies cannot leak to another source. */
    override fun onAttach(runtime: SourceRuntime) {
        gamerApi = GamerApi(runtime.httpClient)
        authenticationSession = runtime.authentication
    }

    override suspend fun getBoards(): List<Board> =
        gamerApi.getAllBoards().map { gBoard ->
            Board(
                sourceId = id,
                url = gBoard.url,
                name = gBoard.name,
            )
        }

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> = withAuthenticationExpiry {
        val req = gamerApi.getRequestBuilder()
            .setUrl(board.url.toHttpUrl())
            .setPage(page.takeIf { it != 0 })
            .build()
        gamerApi.getThreadSummaries(req).map { gNews ->
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

    override suspend fun getThread(summary: ThreadSummary): Thread = withAuthenticationExpiry {
        val req = gamerApi.getRequestBuilder()
            .setUrl(summary.id.toHttpUrl())
            .setPage(1)
            .build()
        val gPosts = gamerApi.getThread(req)
        Thread(
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
                    } catch (error: AuthenticationRequiredException) {
                        throw error
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

    /** Checks a protected board instead of treating the presence of a cookie as proof of login. */
    override suspend fun validateSession(): Boolean {
        val protectedBoard = Board(
            sourceId = id,
            url = "https://forum.gamer.com.tw/B.php?bsn=60076",
            name = "場外討論區",
        )
        return try {
            // A valid session must be able to parse at least one thread from this
            // active protected board. An empty result is not enough evidence of a
            // valid session, while ordinary empty boards remain valid elsewhere.
            getThreadSummaries(protectedBoard, page = 0).isNotEmpty()
        } catch (_: AuthenticationRequiredException) {
            false
        }
    }

    override fun getWebUrl(summary: ThreadSummary): String = summary.id

    private suspend fun <T> withAuthenticationExpiry(block: suspend () -> T): T = try {
        block()
    } catch (error: AuthenticationRequiredException) {
        authenticationSession?.markExpired()
        throw error
    }
}

private fun GParagraph.toExtParagraph(): tw.kevinzhang.extension_api.model.Paragraph = when (this) {
    is GQuote   -> tw.kevinzhang.extension_api.model.Paragraph.Quote(content)
    is GReplyTo -> tw.kevinzhang.extension_api.model.Paragraph.ReplyTo(targetId = content)
    is GText    -> tw.kevinzhang.extension_api.model.Paragraph.Text(content)
    is GImageInfo -> tw.kevinzhang.extension_api.model.Paragraph.ImageInfo(thumb, raw)
    is GLink    -> tw.kevinzhang.extension_api.model.Paragraph.Link(content)
    is GVideoInfo -> tw.kevinzhang.extension_api.model.Paragraph.VideoInfo(
        url = url,
        site = when (site) {
            GVideoSite.YOUTUBE -> tw.kevinzhang.extension_api.model.Paragraph.VideoInfo.Site.YOUTUBE
            GVideoSite.OTHER   -> tw.kevinzhang.extension_api.model.Paragraph.VideoInfo.Site.OTHER
        }
    )
    else        -> throw IllegalArgumentException("Unknown GParagraph: $this")
}
