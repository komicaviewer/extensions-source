package tw.kevinzhang.newshub.extension.mobile01

import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadPage
import tw.kevinzhang.extension_api.model.ThreadPageMetadata
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.io.IOException

/** Public, anonymous, read-only Mobile01 source. It deliberately declines access-control challenges. */
class Mobile01Source : Source {
    override val id: String = Mobile01BoardCatalog.SOURCE_ID
    override val name: String = "Mobile01"
    override val language: String = "zh-TW"
    override val version: Int = 1
    override val iconUrl: String = "https://www.mobile01.com/favicon.ico"
    override val supportsCommentPagination: Boolean = false
    override val alwaysUseRawImage: Boolean = false
    override val needsLogin: Boolean = false

    private var client = OkHttpClient().withMobile01NetworkPolicy()
    private val parser = Mobile01Parser()

    override fun onAttach(client: OkHttpClient) {
        this.client = client.withMobile01NetworkPolicy()
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = Mobile01BoardCatalog.categories()

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage = Mobile01BoardCatalog.page(request)

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        require(page >= 1) { "Mobile01 listing pages are 1-based" }
        val boardId = Mobile01BoardCatalog.validate(board)
        val html = requestText(Mobile01RequestBuilder.board(boardId, page))
        return parser.parseThreadSummaries(html, Mobile01UrlPolicy.boardUrl(boardId), iconUrl, page)
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        val page = getThreadPage(summary, pageToken = null)
        val thread = requireNotNull(Mobile01UrlPolicy.thread(summary.id)) { "Untrusted Mobile01 thread URL" }
        return Thread(
            id = thread.url,
            url = thread.url,
            title = summary.title,
            posts = page.posts,
        )
    }

    override suspend fun getThreadPage(summary: ThreadSummary, pageToken: String?): ThreadPage {
        require(summary.sourceId == id) { "Thread belongs to a different source" }
        val original = requireNotNull(Mobile01UrlPolicy.thread(summary.id)) { "Untrusted Mobile01 thread URL" }
        val requested = if (pageToken == null) original else requireNotNull(
            Mobile01UrlPolicy.pageToken(pageToken)?.let(Mobile01UrlPolicy::thread),
        ) { "Untrusted Mobile01 thread page token" }
        require(requested.boardId == original.boardId && requested.threadId == original.threadId) {
            "Mobile01 page token belongs to another thread"
        }
        val parsed = parser.parseThreadPage(
            requestText(Mobile01RequestBuilder.thread(requested.url)),
            requested.url,
            iconUrl,
        )
        return ThreadPage(
            posts = parsed.posts,
            nextPageToken = parsed.nextPageToken,
            metadata = if (pageToken == null) ThreadPageMetadata(
                id = original.url,
                url = original.url,
                title = summary.title,
            ) else null,
        )
    }

    override fun getWebUrl(summary: ThreadSummary): String? = Mobile01UrlPolicy.thread(summary.id)?.url

    private suspend fun requestText(request: Request): String {
        val response = client.newCall(request).await()
        response.use {
            val body = it.body?.string().orEmpty()
            Mobile01AccessClassifier.classify(it.code, body, it.header("Server"))?.let { failure ->
                throw Mobile01AccessException(failure, it.code, request.url.toString())
            }
            if (!it.isSuccessful) {
                throw Mobile01AccessException(Mobile01AccessFailure.HTTP_ERROR, it.code, request.url.toString())
            }
            if (body.isBlank()) throw IOException("Mobile01 returned an empty document: ${request.url}")
            return body
        }
    }
}

internal fun OkHttpClient.withMobile01NetworkPolicy(): OkHttpClient = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
