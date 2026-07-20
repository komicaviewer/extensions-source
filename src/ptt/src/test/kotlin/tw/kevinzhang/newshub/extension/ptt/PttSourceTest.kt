package tw.kevinzhang.newshub.extension.ptt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.AuthState
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.BoardQuery
import tw.kevinzhang.extension_api.model.ThreadSummary

class PttSourceTest {
    @Test
    fun `thread summary pages are one-based and freeze the previous-page anchor`() = runTest {
        val paths = mutableListOf<String>()
        val source = attachedSource { chain ->
            paths += chain.request().url.encodedPath
            response(chain, fixture("board.html"))
        }
        val board = Board(PttBoardCatalog.SOURCE_ID, PttUrlPolicy.boardUrl("Stock"), "Stock")

        source.getThreadSummaries(board, 1)
        source.getThreadSummaries(board, 2)

        assertEquals(
            listOf("/bbs/Stock/index.html", "/bbs/Stock/index7123.html"),
            paths,
        )
    }

    @Test
    fun `board catalog uses live popular data and validates an exact board`() = runTest {
        val source = attachedSource { chain ->
            when (chain.request().url.encodedPath) {
                "/bbs/index.html" -> response(chain, fixture("hotboards.html"))
                "/bbs/PrivateBoard/index.html" -> response(
                    chain,
                    "<div id='topbar'><a class='board' href='/bbs/PrivateBoard/index.html'>PrivateBoard</a></div>",
                )
                else -> response(chain, "404", code = 404)
            }
        }

        val popular = source.getBoardPage(BoardPageRequest(pageSize = 2))
        val exact = source.getBoardPage(BoardPageRequest(BoardQuery("PrivateBoard")))
        val missing = source.getBoardPage(BoardPageRequest(BoardQuery("MissingBoard")))

        assertEquals(listOf("Gossiping", "Stock"), popular.boards.map { it.name })
        assertEquals(listOf("PrivateBoard"), exact.boards.map { it.name })
        assertTrue(missing.boards.isEmpty())
    }

    @Test
    fun `thread page returns canonical metadata for its only post`() = runTest {
        val source = attachedSource { chain -> response(chain, fixture("thread.html")) }
        val summary = threadSummary()

        val page = source.getThreadPage(summary, pageToken = null)

        assertEquals(2, source.version)
        assertEquals(1, page.posts.size)
        assertEquals(null, page.nextPageToken)
        assertEquals(
            "https://www.ptt.cc/bbs/Stock/M.1700000000.A.123.html",
            page.metadata?.id,
        )
        assertEquals(page.metadata?.id, page.metadata?.url)
        assertEquals(summary.title, page.metadata?.title)
    }

    @Test
    fun `thread page rejects a token without making a request`() = runTest {
        var requestCount = 0
        var rejected = false
        val source = attachedSource { chain ->
            requestCount += 1
            response(chain, fixture("thread.html"))
        }

        try {
            source.getThreadPage(threadSummary(), pageToken = "unexpected")
        } catch (_: UnsupportedOperationException) {
            // Expected: PTT articles contain one Post and no continuation token.
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(0, requestCount)
    }

    @Test
    fun `legacy thread and thread page have matching content and metadata`() = runTest {
        val source = attachedSource { chain -> response(chain, fixture("thread.html")) }
        val summary = threadSummary()

        val legacy = source.getThread(summary)
        val page = source.getThreadPage(summary, pageToken = null)

        assertEquals(legacy.posts, page.posts)
        assertEquals(legacy.id, page.metadata?.id)
        assertEquals(legacy.url, page.metadata?.url)
        assertEquals(legacy.title, page.metadata?.title)
    }

    private fun threadSummary() = ThreadSummary(
        sourceId = PttBoardCatalog.SOURCE_ID,
        boardUrl = PttUrlPolicy.boardUrl("Stock"),
        id = "https://www.ptt.cc/bbs/Stock/M.1700000000.A.123.html",
        title = "PTT test thread",
        author = "tester",
        createdAt = null,
        commentCount = 0,
        rawImage = null,
        thumbnail = null,
        previewContent = emptyList(),
        sourceIconUrl = null,
        replyCount = 0,
    )

    private fun attachedSource(handler: (Interceptor.Chain) -> Response): PttSource = PttSource().also { source ->
        val client = OkHttpClient.Builder().addInterceptor(handler).build()
        source.onAttach(object : SourceRuntime {
            override val httpClient: OkHttpClient = client
            override val authentication: AuthenticationSession = FakeAuthenticationSession()
        })
    }

    private fun response(chain: Interceptor.Chain, body: String, code: Int = 200): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Not Found")
        .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/ptt/$name")).readText()

    private class FakeAuthenticationSession : AuthenticationSession {
        private val mutableState = MutableStateFlow(AuthState.Unknown)
        override val state: StateFlow<AuthState> = mutableState
        override fun markExpired() {
            mutableState.value = AuthState.Expired
        }
    }
}
