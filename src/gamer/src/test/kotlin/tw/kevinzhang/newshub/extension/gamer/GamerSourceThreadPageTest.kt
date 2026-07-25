package tw.kevinzhang.newshub.extension.gamer

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.kevinzhang.extension_api.AuthState
import tw.kevinzhang.extension_api.AuthenticationRequiredException
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.RichTextRun
import tw.kevinzhang.extension_api.model.ThreadSummary

class GamerSourceThreadPageTest {

    @Test
    fun `null page token loads the thread and returns first page metadata`() = runTest {
        val requestCount = AtomicInteger()
        val source = sourceWithInterceptor { chain ->
            requestCount.incrementAndGet()
            val isCommentRequest = chain.request().url.encodedPath.endsWith("/ajax/moreCommend.php")
            response(
                chain = chain,
                body = if (isCommentRequest) "{}" else THREAD_HTML,
            )
        }

        val page = source.getThreadPage(SUMMARY, pageToken = null)

        assertEquals(2, requestCount.get())
        assertEquals(null, page.nextPageToken)
        assertEquals(SUMMARY.id, page.metadata?.id)
        assertEquals(SUMMARY.id, page.metadata?.url)
        assertEquals(SUMMARY.title, page.metadata?.title)
        assertEquals(1, page.posts.size)
        assertEquals("123", page.posts.single().id)
        assertEquals(
            listOf(Paragraph.RichText(listOf(RichTextRun("Thread body")))),
            page.posts.single().content,
        )
    }

    @Test
    fun `non-null page token is rejected without a network request`() = runTest {
        val requestCount = AtomicInteger()
        val source = sourceWithInterceptor { chain ->
            requestCount.incrementAndGet()
            response(chain, THREAD_HTML)
        }

        try {
            source.getThreadPage(SUMMARY, pageToken = "2")
            fail("Expected Gamer to reject a non-null thread page token")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }

        assertEquals(0, requestCount.get())
    }

    @Test
    fun `thread page authentication failure expires the host session`() = runTest {
        val authentication = FakeAuthenticationSession()
        val source = sourceWithInterceptor(authentication) { chain ->
            response(chain = chain, body = "", code = 403, message = "Forbidden")
        }

        try {
            source.getThreadPage(SUMMARY, pageToken = null)
            fail("Expected Gamer authentication failure")
        } catch (_: AuthenticationRequiredException) {
            // Expected.
        }

        assertTrue(authentication.expired)
        assertEquals(AuthState.Expired, authentication.state.value)
    }

    private fun sourceWithInterceptor(
        authentication: FakeAuthenticationSession = FakeAuthenticationSession(),
        interceptor: (Interceptor.Chain) -> Response,
    ): GamerSource {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor(interceptor))
            .build()
        return GamerSource().apply {
            onAttach(object : SourceRuntime {
                override val httpClient = client
                override val authentication = authentication
            })
        }
    }

    private fun response(
        chain: Interceptor.Chain,
        body: String,
        code: Int = 200,
        message: String = "OK",
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body(body.toResponseBody("text/html".toMediaType()))
        .build()

    private class FakeAuthenticationSession : AuthenticationSession {
        override val state = MutableStateFlow(AuthState.SignedIn)
        var expired = false

        override fun markExpired() {
            expired = true
            state.value = AuthState.Expired
        }
    }

    private companion object {
        val SUMMARY = ThreadSummary(
            sourceId = "tw.kevinzhang.newshub.extension.gamer",
            boardUrl = "https://forum.gamer.com.tw/B.php?bsn=1",
            id = "https://forum.gamer.com.tw/C.php?bsn=1&snA=2",
            title = "Thread title",
            author = "Summary author",
            createdAt = null,
            commentCount = null,
            rawImage = null,
            thumbnail = null,
            previewContent = emptyList(),
        )

        val THREAD_HTML = """
            <section class="c-section" id="post_123">
              <div class="c-post__header">
                <h1 class="c-post__header__title">Parsed title</h1>
                <div class="c-post__header__info">
                  <a class="edittime tippy-post-info" data-mtime="2026-07-26 12:00:00"></a>
                </div>
              </div>
              <a class="username">Author</a>
              <a class="userid">author-id</a>
              <div class="gp"><a class="count tippy-gpbp-list">0</a></div>
              <div class="bp"><a class="count tippy-gpbp-list">0</a></div>
              <div class="c-article__content">Thread body</div>
            </section>
        """.trimIndent()
    }
}
