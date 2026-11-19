package tw.kevinzhang.newshub.extension.twocat.komica2.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableException
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableReason
import tw.kevinzhang.newshub.extension.twocat.komica2.model.TwocatGatedBoard

private const val ENTRY_REFERER = "https://komica1.org/"
private val DECOY_PATH = Regex("^/[A-Za-z0-9_-]+/$")
private val TITLE = Regex("<title>([^<]*)", RegexOption.IGNORE_CASE)

/**
 * 2cat binds the KOMICA2 adult boards to the PHPSESSID of a 2cat.uk session and serves the bound
 * board under a random public-board path ("decoy"). One session holds one board at a time, so
 * activation and fetch are serialized; the Host keeps the cookie in this Source's credentialed jar.
 */
internal class TwocatBoardSession(private val client: OkHttpClient) {
    private val mutex = Mutex()
    private var activeSlug: String? = null
    private var decoy: String? = null

    suspend fun withBoard(board: TwocatGatedBoard, fetch: suspend (decoy: String) -> Response): Response =
        mutex.withLock {
            if (activeSlug != board.slug) activate(board)
            fetchBound(board, fetch) ?: run {
                activate(board)
                fetchBound(board, fetch) ?: throw SourceSiteUnavailableException(200, SourceSiteUnavailableReason.HTTP_ERROR)
            }
        }

    /** Returns a buffered response only when it is the 2xx page of [board]; null means the binding was lost. */
    private suspend fun fetchBound(board: TwocatGatedBoard, fetch: suspend (String) -> Response): Response? {
        val response = fetch(checkNotNull(decoy))
        if (!response.isSuccessful) {
            response.close()
            if (response.code in 300..399) return null
            throw SourceSiteUnavailableException(response.code, SourceSiteUnavailableReason.HTTP_ERROR)
        }
        val body = response.body!!
        val bytes = body.bytes()
        val title = TITLE.find(bytes.toString(Charsets.UTF_8))?.groupValues?.get(1).orEmpty()
        if (board.expectedTitle !in title) return null
        return response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
    }

    private suspend fun activate(board: TwocatGatedBoard) {
        activeSlug = null
        decoy = null
        // 2cat.uk binds the board on this same-origin entry; 2cat.org's redirect hop adds nothing
        // but an extra request to a host that throttles bursts.
        val shell = get("https://2cat.uk/~${board.slug}/").use { response ->
            if (!response.isSuccessful) fail(response.code)
            response.body!!.string()
        }
        decoy = parseDecoyPath(shell) ?: fail()
        activeSlug = board.slug
    }

    private suspend fun get(url: String): Response =
        client.newCall(Request.Builder().url(url).header("Referer", ENTRY_REFERER).build()).await()

    private fun fail(code: Int = 200): Nothing =
        throw SourceSiteUnavailableException(code, SourceSiteUnavailableReason.HTTP_ERROR)
}

internal fun parseDecoyPath(shellHtml: String): String? =
    Jsoup.parse(shellHtml).selectFirst("iframe#main[src]")?.attr("src")?.takeIf(DECOY_PATH::matches)

/** Gated boards page with the board index's 0-based `page`; `pixmicat.php?page_num` is ignored there. */
internal fun TwocatGatedBoard.summariesUrl(decoy: String, page: Int): HttpUrl =
    "https://2cat.uk$decoy".toHttpUrl().newBuilder().apply {
        if (page > 1) setQueryParameter("page", (page - 1).toString())
    }.build()

internal fun TwocatGatedBoard.threadUrl(decoy: String, threadId: String): HttpUrl {
    val res = requireNotNull(threadId.toHttpUrl().queryParameter("res")) { "Thread id has no res parameter" }
    return "https://2cat.uk$decoy".toHttpUrl().newBuilder().setQueryParameter("res", res).build()
}
