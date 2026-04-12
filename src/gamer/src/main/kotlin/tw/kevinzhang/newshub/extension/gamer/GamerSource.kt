package tw.kevinzhang.newshub.extension.gamer

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tw.kevinzhang.extension_api.Source
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

class GamerSource : Source {
    private var gamerApi = GamerApi(OkHttpClient())

    override val id = "tw.kevinzhang.newshub.extension.gamer"
    override val name = "Gamer 巴哈姆特"
    override val language = "zh-TW"
    override val version = 1
    override val iconUrl: String = "https://i2.bahamut.com.tw/apple-touch-icon-72x72.png"
    override val supportsCommentPagination: Boolean = false
    override val alwaysUseRawImage: Boolean = false
    override val needsLogin = true

    /** Replaces the default OkHttpClient with the host app's shared client (which has the cookie jar). */
    override fun onAttach(client: OkHttpClient) {
        gamerApi = GamerApi(client)
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
        val req = gamerApi.getRequestBuilder()
            .setUrl(board.url.toHttpUrl())
            .setPage(page.takeIf { it != 0 })
            .build()
        return gamerApi.getThreadSummaries(req).map { gNews ->
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
