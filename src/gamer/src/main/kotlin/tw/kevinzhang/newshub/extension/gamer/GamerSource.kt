package tw.kevinzhang.newshub.extension.gamer

import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.kevinzhang.extension_api.AuthSpec
import tw.kevinzhang.extension_api.AuthenticatedSource
import tw.kevinzhang.extension_api.AuthenticationRequiredException
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Comment
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadPage
import tw.kevinzhang.extension_api.model.ThreadPageMetadata
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.gamer_api.GamerApi
import tw.kevinzhang.gamer_api.model.GImageInfo
import tw.kevinzhang.gamer_api.model.GLink
import tw.kevinzhang.gamer_api.model.GParagraph
import tw.kevinzhang.gamer_api.model.GQuote
import tw.kevinzhang.gamer_api.model.GReplyTo
import tw.kevinzhang.gamer_api.model.GRichText
import tw.kevinzhang.gamer_api.model.GText
import tw.kevinzhang.gamer_api.model.GTextColor
import tw.kevinzhang.gamer_api.model.GTextEmphasis
import tw.kevinzhang.gamer_api.model.GVideoInfo
import tw.kevinzhang.gamer_api.model.GVideoSite
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient

class GamerSource : AuthenticatedSource {
    private lateinit var gamerApi: GamerApi
    private var authenticationSession: AuthenticationSession? = null

    override val id = "tw.kevinzhang.newshub.extension.gamer"
    override val name = "Gamer 巴哈姆特"
    override val language = "zh-TW"
    override val version = 6
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
        gamerApi = GamerApi(runtime.brokerBackedHttpClient())
        authenticationSession = runtime.authentication
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = GAMER_CATEGORIES

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        val page = request.pageToken?.toIntOrNull() ?: 1
        require(page > 0) { "Invalid board page token: ${request.pageToken}" }
        val query = request.query.text.trim()
        val categoryCode = request.query.categoryId?.let { categoryId ->
            requireNotNull(GAMER_CATEGORY_CODES[categoryId]) {
                "Unknown Gamer board category: $categoryId"
            }
        } ?: ALL_BOARDS_CATEGORY_CODE
        val result = if (query.isEmpty()) {
            val parsed = gamerApi.getBoardPage(categoryCode, page)
            if (categoryCode == ALL_BOARDS_CATEGORY_CODE) emptyList() else parsed
        } else {
            gamerApi.searchBoards(query, page)
        }
        val boards = result.take(request.pageSize).map { gBoard ->
            Board(
                sourceId = id,
                url = gBoard.url,
                name = gBoard.name,
                description = gBoard.category,
            )
        }
        return BoardPage(
            boards = boards,
            nextPageToken = (page + 1).toString().takeIf {
                result.size >= if (query.isEmpty()) BOARD_API_PAGE_SIZE else SEARCH_API_PAGE_SIZE
            },
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
                    content = gPost.content.map { it.toParagraph() },
                    comments = comments,
                    rawHtml = gPost.rawHtml,
                    sourceIconUrl = iconUrl,
                    replyCount = null,
                )
            },
        )
    }

    override suspend fun getThreadPage(summary: ThreadSummary, pageToken: String?): ThreadPage {
        if (pageToken != null) {
            throw UnsupportedOperationException("Gamer threads do not support post pagination")
        }
        val thread = getThread(summary)
        return ThreadPage(
            posts = thread.posts,
            nextPageToken = null,
            metadata = ThreadPageMetadata(
                id = thread.id,
                url = thread.url,
                title = thread.title,
            ),
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

    override suspend fun getWebUrl(summary: ThreadSummary): String = summary.id

    private suspend fun <T> withAuthenticationExpiry(block: suspend () -> T): T = try {
        block()
    } catch (error: AuthenticationRequiredException) {
        authenticationSession?.markExpired()
        throw error
    }
}

private const val ALL_BOARDS_CATEGORY_CODE = 21
private const val BOARD_API_PAGE_SIZE = 30
private const val SEARCH_API_PAGE_SIZE = 20

private val GAMER_CATEGORY_CODES = mapOf(
    "mobile" to 94,
    "pc" to 500,
    "console" to 52,
    "anime" to 22,
    "lifestyle" to 100,
    "site" to 28,
)

private val GAMER_CATEGORIES = listOf(
    BoardCategory("mobile", "手機"),
    BoardCategory("pc", "PC"),
    BoardCategory("console", "TV／掌機"),
    BoardCategory("anime", "動漫畫"),
    BoardCategory("lifestyle", "宅生活"),
    BoardCategory("site", "站務"),
)

private fun GParagraph.toParagraph(): tw.kevinzhang.extension_api.model.Paragraph = when (this) {
    is GQuote   -> tw.kevinzhang.extension_api.model.Paragraph.Quote(content)
    is GReplyTo -> tw.kevinzhang.extension_api.model.Paragraph.ReplyTo(targetId = content)
    is GText    -> tw.kevinzhang.extension_api.model.Paragraph.Text(content)
    is GRichText -> tw.kevinzhang.extension_api.model.Paragraph.RichText(
        runs = runs.map { run ->
            tw.kevinzhang.extension_api.model.RichTextRun(
                text = run.text,
                color = when (run.color) {
                    GTextColor.DEFAULT -> tw.kevinzhang.extension_api.model.RichTextColor.DEFAULT
                    GTextColor.BLACK -> tw.kevinzhang.extension_api.model.RichTextColor.BLACK
                    GTextColor.RED -> tw.kevinzhang.extension_api.model.RichTextColor.RED
                    GTextColor.GREEN -> tw.kevinzhang.extension_api.model.RichTextColor.GREEN
                    GTextColor.YELLOW -> tw.kevinzhang.extension_api.model.RichTextColor.YELLOW
                    GTextColor.BLUE -> tw.kevinzhang.extension_api.model.RichTextColor.BLUE
                    GTextColor.MAGENTA -> tw.kevinzhang.extension_api.model.RichTextColor.MAGENTA
                    GTextColor.CYAN -> tw.kevinzhang.extension_api.model.RichTextColor.CYAN
                    GTextColor.WHITE -> tw.kevinzhang.extension_api.model.RichTextColor.WHITE
                },
                emphasis = when (run.emphasis) {
                    GTextEmphasis.NORMAL -> tw.kevinzhang.extension_api.model.RichTextEmphasis.NORMAL
                    GTextEmphasis.BRIGHT -> tw.kevinzhang.extension_api.model.RichTextEmphasis.BRIGHT
                },
                linkUrl = run.linkUrl,
            )
        },
        layout = tw.kevinzhang.extension_api.model.RichTextLayout.PROSE,
    )
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
