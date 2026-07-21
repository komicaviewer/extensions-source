package tw.kevinzhang.newshub.extension.mobile01

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.ThreadSummary

class Mobile01SourceTest {
    @Test
    fun `thread pages keep first page metadata and map each floor to a comment-free post`() = runBlocking {
        val source = Mobile01Source().apply {
            onAttach(
                OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    val page = chain.request().url.queryParameter("p")
                    val html = if (page == "2") resource("topic-detail-page-2.html") else resource("topic-detail-page-1.html")
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(html.toResponseBody("text/html".toMediaType()))
                        .build()
                }).build(),
            )
        }
        val summary = ThreadSummary(
            sourceId = source.id,
            boardUrl = "https://www.mobile01.com/topiclist.php?f=350",
            id = "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
            title = "無題",
            author = "原作者",
            createdAt = null,
            commentCount = 2,
            rawImage = null,
            thumbnail = null,
            previewContent = emptyList(),
        )

        val first = source.getThreadPage(summary, null)
        val second = source.getThreadPage(summary, first.nextPageToken)
        val legacy = source.getThread(summary)

        assertEquals(summary.id, first.metadata?.id)
        assertEquals("螢幕選購與校色心得", first.metadata?.title)
        assertEquals("https://www.mobile01.com/topicdetail.php?f=350&t=5356590&p=2", first.nextPageToken)
        assertEquals(listOf("66984790", "66998109"), first.posts.map { it.id })
        assertTrue(first.posts.all { it.comments.isEmpty() })
        assertNull(second.metadata)
        assertNull(second.nextPageToken)
        assertEquals(listOf("67000123"), second.posts.map { it.id })
        assertTrue(second.posts.all { it.comments.isEmpty() })
        assertEquals("螢幕選購與校色心得", legacy.title)
    }

    @Test
    fun `pagination rejects a page-one token`() {
        val source = Mobile01Source()
        val summary = ThreadSummary(
            sourceId = source.id,
            boardUrl = "https://www.mobile01.com/topiclist.php?f=350",
            id = "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
            title = "螢幕選購與校色心得",
            author = null,
            createdAt = null,
            commentCount = null,
            rawImage = null,
            thumbnail = null,
            previewContent = emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { source.getThreadPage(summary, summary.id) }
        }
    }

    private fun resource(name: String): String = requireNotNull(javaClass.classLoader.getResource("mobile01/$name"))
        .readText()
}
