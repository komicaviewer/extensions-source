package tw.kevinzhang.newshub.extension.hackernews

import tw.kevinzhang.extension_api.SessionAwareSource
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadPage
import tw.kevinzhang.extension_api.model.ThreadPageMetadata
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.io.IOException
import java.net.URI
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient

class HackerNewsSource : SessionAwareSource {
    private lateinit var api: HackerNewsApi
    private val htmlParser = HackerNewsHtmlParser()

    constructor()

    internal constructor(api: HackerNewsApi) {
        this.api = api
    }

    override val id = HackerNewsBoards.SOURCE_ID
    override val name = "Hacker News"
    override val language = "en"
    override val version = 1
    override val iconUrl = "https://avatars.githubusercontent.com/u/4703068?s=128&v=4"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = false
    override val needsLogin = false

    override fun onAttach(runtime: SourceRuntime) {
        api = HackerNewsApi(runtime.brokerBackedHttpClient())
    }

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        require(request.query.categoryId == null) {
            "Hacker News does not define board categories: ${request.query.categoryId}"
        }
        val query = request.query.text.trim()
        val filtered = HackerNewsBoards.all.filter { board ->
            query.isEmpty() || board.name.contains(query, ignoreCase = true) ||
                board.description.orEmpty().contains(query, ignoreCase = true)
        }
        val offset = request.pageToken?.toIntOrNull() ?: 0
        require(offset >= 0) { "Invalid board page token: ${request.pageToken}" }
        val boards = filtered.drop(offset).take(request.pageSize)
        val nextOffset = offset + boards.size
        return BoardPage(
            boards = boards,
            nextPageToken = nextOffset.takeIf { it < filtered.size }?.toString(),
        )
    }

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        require(page > 0) { "Hacker News page must be 1-based: $page" }
        val feed = HackerNewsBoards.feedFor(board)
        val offset = (page.toLong() - 1L) * STORIES_PER_PAGE
        if (offset > Int.MAX_VALUE) return emptyList()
        val storyIds = api.getFeed(feed).drop(offset.toInt()).take(STORIES_PER_PAGE)
        return api.getItems(storyIds).mapNotNull { item ->
            item.takeIf { it.deleted != true && it.dead != true && it.isThreadRoot() }
                ?.toSummary(board.url)
        }
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        val page = getThreadPage(summary, pageToken = null)
        val metadata = checkNotNull(page.metadata) { "Hacker News first page must include metadata" }
        return Thread(
            id = metadata.id,
            url = metadata.url,
            title = metadata.title,
            posts = page.posts,
        )
    }

    override suspend fun getThreadPage(summary: ThreadSummary, pageToken: String?): ThreadPage {
        require(summary.sourceId == id) { "Unexpected source id: ${summary.sourceId}" }
        val rootId = summary.id.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw IOException("Invalid Hacker News story id: ${summary.id}")
        val page = HackerNewsThreadPager(api).load(rootId, pageToken)
        return ThreadPage(
            posts = page.items.map { it.toNewsHubPost() },
            nextPageToken = page.nextPageToken,
            metadata = page.root?.let { root ->
                ThreadPageMetadata(
                    id = root.id.toString(),
                    url = discussionUrl(root.id),
                    title = htmlParser.plainText(root.title) ?: summary.title,
                )
            },
        )
    }

    override suspend fun getWebUrl(summary: ThreadSummary): String? =
        summary.id.toLongOrNull()?.takeIf { it > 0 }?.let(::discussionUrl)

    private fun HackerNewsItem.toSummary(boardUrl: String): ThreadSummary {
        val metadata = metadataText()
        val preview = buildList {
            metadata.takeIf(String::isNotBlank)?.let { add(Paragraph.Text(it)) }
            url?.takeIf(String::isNotBlank)?.let { add(Paragraph.Link(it)) }
            addAll(htmlParser.parse(text).take(MAX_SUMMARY_PARAGRAPHS))
        }
        return ThreadSummary(
            sourceId = this@HackerNewsSource.id,
            boardUrl = boardUrl,
            id = this.id.toString(),
            title = htmlParser.plainText(title),
            author = by,
            createdAt = time?.times(1_000),
            commentCount = descendants,
            rawImage = null,
            thumbnail = null,
            previewContent = preview,
            sourceIconUrl = iconUrl,
            replyCount = kids.orEmpty().size.takeIf { it > 0 },
        )
    }

    private fun HackerNewsItem.toNewsHubPost(extraContent: List<Paragraph> = emptyList()): Post {
        val content = buildList {
            parent?.let { add(Paragraph.ReplyTo(it.toString())) }
            when {
                deleted == true -> add(Paragraph.Text("[deleted]"))
                dead == true -> add(Paragraph.Text("[dead]"))
                else -> {
                    if (parent == null) {
                        metadataText().takeIf(String::isNotBlank)?.let { add(Paragraph.Text(it)) }
                        url?.takeIf(String::isNotBlank)?.let { add(Paragraph.Link(it)) }
                    }
                    addAll(htmlParser.parse(text))
                }
            }
            addAll(extraContent)
        }
        return Post(
            id = id.toString(),
            author = by,
            createdAt = time?.times(1_000),
            thumbnail = null,
            content = content,
            comments = emptyList(),
            rawHtml = text,
            sourceIconUrl = iconUrl,
            replyCount = kids.orEmpty().size.takeIf { it > 0 },
        )
    }

    private fun HackerNewsItem.metadataText(): String {
        val parts = mutableListOf<String>()
        score?.let { parts += "$it points" }
        url?.hostOrNull()?.let { parts += it.removePrefix("www.") }
        return parts.joinToString(" · ")
    }

    private fun HackerNewsItem.isThreadRoot(): Boolean = type in ROOT_TYPES

    private fun String.hostOrNull(): String? = runCatching { URI(this).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)

    private companion object {
        const val STORIES_PER_PAGE = 30
        const val MAX_SUMMARY_PARAGRAPHS = 2
        val ROOT_TYPES = setOf("story", "job", "poll")

        fun discussionUrl(id: Long) = "https://news.ycombinator.com/item?id=$id"
    }
}
