package tw.kevinzhang.newshub.extension.mobile01

import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.SessionAwareSource
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadPage
import tw.kevinzhang.extension_api.model.ThreadPageMetadata
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.io.IOException
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient
import tw.kevinzhang.newshub.extension.runtime.BoardCatalogSnapshotCache
import tw.kevinzhang.newshub.extension.runtime.BoardCatalogPaging
import tw.kevinzhang.newshub.extension.runtime.CatalogSnapshot
import tw.kevinzhang.extension_api.model.BoardCatalogCoverage
import kotlinx.coroutines.CancellationException

/** Public, anonymous, read-only Mobile01 source. It deliberately declines access-control challenges. */
class Mobile01Source : SessionAwareSource {
    override val id: String = Mobile01BoardCatalog.SOURCE_ID
    override val name: String = "Mobile01"
    override val language: String = "zh-TW"
    override val version: Int = 4
    override val iconUrl: String = "https://attach2.mobile01.com/images/touch/apple-touch-icon-180x180.png"
    override val supportsCommentPagination: Boolean = false
    override val alwaysUseRawImage: Boolean = false
    override val needsLogin: Boolean = false

    private lateinit var client: OkHttpClient
    private val parser = Mobile01Parser()
    private val catalogParser = Mobile01CatalogParser()
    private val catalogCache = BoardCatalogSnapshotCache<List<Board>>()
    private var catalogEpoch: String? = null

    override fun onAttach(runtime: SourceRuntime) {
        client = runtime.brokerBackedHttpClient().withMobile01NetworkPolicy()
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = loadCatalog(false).value
        .mapNotNull { it.description }.distinct().map { BoardCategory(it, it) }

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        catalogEpoch = request.contextEpoch
        val snapshot = loadCatalog(request.refresh)
        val text = request.query.text.trim()
        val filtered = snapshot.value.filter {
            (request.query.categoryId == null || it.description == request.query.categoryId) &&
                (text.isBlank() || it.name.contains(text, true) || it.description.orEmpty().contains(text, true))
        }
        val visible = if (text.isBlank() && request.query.categoryId == null) {
            val preferred = Mobile01BoardCatalog.page(BoardPageRequest(pageSize = 100)).boards.map { it.url }.toSet()
            filtered.filter { it.url in preferred }.ifEmpty { filtered.take(16) }
        } else filtered
        return BoardCatalogPaging.page(visible, request, snapshot)
    }

    private suspend fun loadCatalog(refresh: Boolean): CatalogSnapshot<List<Board>> = catalogCache.get(catalogEpoch, refresh) { budget ->
        val pending = java.util.ArrayDeque<String>()
        val visited = linkedSetOf<String>()
        val boards = linkedMapOf<String, Board>()
        pending.add("https://www.mobile01.com/forumlist.php")
        var partial = false
        while (pending.isNotEmpty() && visited.size < 50) {
            val url = pending.removeFirst()
            if (!visited.add(url)) continue
            try {
                val document = catalogParser.parse(budget.fetch { requestText(Mobile01RequestBuilder.catalog(url)) }, url)
                document.boards.forEach { boards.putIfAbsent(it.url, it) }
                document.categoryUrls.filterNot { it in visited }.forEach(pending::add)
                if (document.boards.isEmpty() && document.categoryUrls.isEmpty()) partial = true
            } catch (error: CancellationException) { throw error }
            catch (error: Mobile01AccessException) { throw error }
            catch (error: IOException) {
                if (boards.isEmpty()) throw error
                partial = true
            }
        }
        if (boards.isEmpty()) throw Mobile01PageStructureException("catalog")
        CatalogSnapshot(boards.values.toList(), updatedAtMillis = System.currentTimeMillis(),
            coverage = if (partial || pending.isNotEmpty()) BoardCatalogCoverage.PARTIAL else BoardCatalogCoverage.AVAILABLE)
    }

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
            title = page.metadata?.title ?: summary.title,
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
                title = parsed.title ?: summary.title,
            ) else null,
        )
    }

    override suspend fun getWebUrl(summary: ThreadSummary): String? = Mobile01UrlPolicy.thread(summary.id)?.url

    private suspend fun requestText(request: Request): String {
        val response = client.newCall(request).await()
        response.use {
            val body = it.body?.string().orEmpty()
            Mobile01AccessClassifier.classify(it.code, body, it.header("Server"))?.let { failure ->
                throw Mobile01AccessException(failure)
            }
            if (!it.isSuccessful) {
                throw Mobile01AccessException(Mobile01AccessFailure.HTTP_ERROR)
            }
            if (body.isBlank()) throw IOException("Mobile01 returned an empty document")
            return body
        }
    }
}

internal fun OkHttpClient.withMobile01NetworkPolicy(): OkHttpClient = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
