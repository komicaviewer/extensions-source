package tw.kevinzhang.newshub.extension.ptt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.extension_api.AuthSpec
import tw.kevinzhang.extension_api.AuthenticatedSource
import tw.kevinzhang.extension_api.AuthenticationRequiredException
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadPage
import tw.kevinzhang.extension_api.model.ThreadPageMetadata
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Read-only PTT source. PTT's over-18 confirmation is host-managed WebCookie authentication. */
class PttSource : AuthenticatedSource {
    override val id: String = PttBoardCatalog.SOURCE_ID
    override val name: String = "PTT 批踢踢實業坊"
    override val language: String = "zh-TW"
    override val version: Int = 3
    override val iconUrl: String = "https://www.ptt.cc/favicon.ico"
    override val supportsCommentPagination: Boolean = false
    override val alwaysUseRawImage: Boolean = true
    // Public boards work without a cookie. The AuthSpec is solely for PTT's over-18 gate.
    override val needsLogin: Boolean = false
    override val authSpec: AuthSpec = AuthSpec.WebCookie(
        loginUrl = "https://www.ptt.cc/ask/over18?from=%2Fbbs%2FC_Chat%2Findex.html",
        allowedHosts = setOf("www.ptt.cc"),
        cookieOrigins = setOf("https://www.ptt.cc"),
        cookieDomains = setOf("ptt.cc"),
    )

    private var client = OkHttpClient()
    private var authentication: AuthenticationSession? = null
    private val parser = PttParser()
    private val paging = ConcurrentHashMap<String, PttBoardPagingState>()
    private val popularBoardsMutex = Mutex()
    private var popularBoardsCache = emptyList<Board>()
    private var popularBoardsCachedAt = 0L

    override fun onAttach(runtime: SourceRuntime) {
        client = runtime.httpClient.newBuilder()
            .addNetworkInterceptor { chain ->
                val hasConsentCookie = chain.request().header("Cookie").orEmpty()
                    .split(';')
                    .any { it.trim() == "over18=1" }
                chain.proceed(chain.request()).newBuilder()
                    .header(CONSENT_MARKER_HEADER, if (hasConsentCookie) "1" else "0")
                    .build()
            }
            .build()
        authentication = runtime.authentication
    }

    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        val popular = loadPopularBoards()
        val query = request.query.text.trim()
        val exact = if (
            query.isNotEmpty() && PttUrlPolicy.isBoardName(query) &&
            popular.none { it.name.equals(query, ignoreCase = true) }
        ) {
            requestOptionalText(PttRequestBuilder.boardPage(query, null))
                ?.takeIf { PttBoardCatalog.isBoardPage(it, query) }
                ?.let { PttBoardCatalog.exactBoard(query) }
        } else {
            null
        }
        return PttBoardCatalog.page(request, popular, exact)
    }

    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        require(page >= 1) { "PTT page must be 1-based" }
        val boardName = PttBoardCatalog.validate(board)
        return loadListing(boardName, page).summaries
    }

    override suspend fun getThread(summary: ThreadSummary): Thread {
        val parsed = loadThreadPage(summary)
        val articleUrl = requireNotNull(PttUrlPolicy.articleUrl(summary.id)) { "Untrusted PTT article URL" }
        return Thread(
            id = articleUrl,
            url = articleUrl,
            title = summary.title,
            posts = parsed.posts,
        )
    }

    override suspend fun getThreadPage(summary: ThreadSummary, pageToken: String?): ThreadPage {
        if (pageToken != null) {
            throw UnsupportedOperationException("PTT articles do not support post pagination")
        }
        val parsed = loadThreadPage(summary)
        val articleUrl = requireNotNull(PttUrlPolicy.articleUrl(summary.id)) { "Untrusted PTT article URL" }
        return ThreadPage(
            posts = parsed.posts,
            nextPageToken = null,
            metadata = ThreadPageMetadata(
                id = articleUrl,
                url = articleUrl,
                title = summary.title,
            ),
        )
    }

    /** Fetches PTT's single-post article page; the public ThreadPage contract is implemented above. */
    internal suspend fun loadThreadPage(summary: ThreadSummary): PttParsedThreadPage {
        require(summary.sourceId == id) { "Thread belongs to a different source" }
        val articleUrl = requireNotNull(PttUrlPolicy.articleUrl(summary.id)) { "Untrusted PTT article URL" }
        val body = requestText(PttRequestBuilder.thread(articleUrl))
        return parser.parseThreadPage(body, articleUrl, iconUrl)
    }

    override suspend fun validateSession(): Boolean = try {
        // Gossiping is consistently protected by PTT's over-18 form. A parsed page is proof
        // that the WebView-created cookie was accepted, rather than merely present in storage.
        loadListing("Gossiping", page = 1).summaries.isNotEmpty()
    } catch (_: AuthenticationRequiredException) {
        false
    }

    override fun getWebUrl(summary: ThreadSummary): String? = PttUrlPolicy.articleUrl(summary.id)

    private suspend fun loadPopularBoards(): List<Board> = popularBoardsMutex.withLock {
        val now = System.currentTimeMillis()
        if (popularBoardsCache.isNotEmpty() && now - popularBoardsCachedAt < POPULAR_CACHE_MILLIS) {
            return@withLock popularBoardsCache
        }
        try {
            PttBoardCatalog.parsePopular(requestText(PttRequestBuilder.popularBoards())).also { boards ->
                if (boards.isEmpty()) throw IOException("PTT popular-board page contained no boards")
                popularBoardsCache = boards
                popularBoardsCachedAt = now
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (popularBoardsCache.isNotEmpty()) popularBoardsCache else throw error
        }
    }

    private suspend fun loadListing(boardName: String, page: Int): PttBoardListing {
        val state = paging.getOrPut(boardName) { PttBoardPagingState() }
        return state.mutex.withLock {
            val position = page - 1
            if (position == 0) {
                val latest = fetchListing(boardName, null)
                state.anchors.clear()
                state.anchors += null
                latest.previousPageIndex?.let { state.anchors += it }
                return@withLock latest
            }
            while (state.anchors.size <= position) {
                val parent = fetchListing(boardName, state.anchors.last())
                val previous = parent.previousPageIndex ?: return@withLock EMPTY_LISTING
                state.anchors += previous
            }
            fetchListing(boardName, state.anchors[position])
        }
    }

    private suspend fun fetchListing(boardName: String, pageIndex: Int?): PttBoardListing = parser.parseBoardListing(
        requestText(PttRequestBuilder.boardPage(boardName, pageIndex)),
        boardName,
        iconUrl,
    )

    private suspend fun requestText(request: Request): String {
        return requestOptionalText(request) ?: throw IOException("HTTP 404: ${request.url}")
    }

    private suspend fun requestOptionalText(request: Request): String? {
        val response = client.newCall(request).await()
        response.use {
            val body = it.body?.string().orEmpty()
            if (
                it.code == 401 || it.code == 403 ||
                PttConsentGate.isRequired(
                    body,
                    it.request.url.toString(),
                    cookieHeader = "over18=1".takeIf { _ -> it.header(CONSENT_MARKER_HEADER) == "1" },
                )
            ) {
                authentication?.markExpired()
                throw AuthenticationRequiredException("PTT requires over-18 confirmation")
            }
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}: ${request.url}")
            return body
        }
    }

    private class PttBoardPagingState {
        /** position 0 is index.html; later values are frozen from each page's "上頁" link. */
        val anchors = mutableListOf<Int?>(null)
        val mutex = Mutex()
    }

    private companion object {
        const val CONSENT_MARKER_HEADER = "X-NewsHub-Ptt-Over18"
        const val POPULAR_CACHE_MILLIS = 5 * 60 * 1_000L
        val EMPTY_LISTING = PttBoardListing(emptyList(), null)
    }
}
