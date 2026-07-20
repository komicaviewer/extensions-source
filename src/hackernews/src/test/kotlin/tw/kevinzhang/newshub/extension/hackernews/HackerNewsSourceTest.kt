package tw.kevinzhang.newshub.extension.hackernews

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph

class HackerNewsSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: HackerNewsSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = fixtureDispatcher()
        source = HackerNewsSource(HackerNewsApi(OkHttpClient(), server.url("/v0/")))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `maps story metadata external link and comment count`() = runTest {
        val summary = source.getThreadSummaries(HackerNewsBoards.all.first(), page = 1).single()

        assertEquals("100", summary.id)
        assertEquals("An & Interesting Story", summary.title)
        assertEquals("alice", summary.author)
        assertEquals(1_700_000_000_000L, summary.createdAt)
        assertEquals(10, summary.commentCount)
        assertTrue(summary.previewContent.contains(Paragraph.Text("123 points · example.com")))
        assertTrue(summary.previewContent.contains(Paragraph.Link("https://www.example.com/article")))
    }

    @Test
    fun `builds stable depth first reply tree with deleted placeholder and truncation notice`() = runTest {
        val summary = source.getThreadSummaries(HackerNewsBoards.all.first(), page = 1).single()
        val thread = source.getThread(summary)

        assertEquals(listOf("100", "101", "103", "102"), thread.posts.map { it.id })
        assertEquals(Paragraph.ReplyTo("100"), thread.posts[1].content.first())
        assertEquals(Paragraph.ReplyTo("101"), thread.posts[2].content.first())
        assertEquals(
            listOf(Paragraph.ReplyTo("100"), Paragraph.Text("[deleted]")),
            thread.posts[3].content,
        )
        assertTrue(
            thread.posts.first().content.filterIsInstance<Paragraph.Text>()
                .any { it.content.contains("Loaded 3 of 10 comments") },
        )
        assertEquals("https://news.ycombinator.com/item?id=100", thread.url)
    }

    private fun fixtureDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = when (request.requestUrl?.encodedPath) {
                "/v0/topstories.json" -> "[100]"
                "/v0/item/100.json" -> """
                    {"id":100,"type":"story","by":"alice","time":1700000000,
                     "title":"An &amp; Interesting Story","url":"https://www.example.com/article",
                     "score":123,"descendants":10,"kids":[101,102],"text":"Root text"}
                """.trimIndent()
                "/v0/item/101.json" -> """
                    {"id":101,"type":"comment","by":"bob","time":1700000001,
                     "parent":100,"kids":[103],"text":"First comment"}
                """.trimIndent()
                "/v0/item/102.json" -> """
                    {"id":102,"type":"comment","deleted":true,"parent":100}
                """.trimIndent()
                "/v0/item/103.json" -> """
                    {"id":103,"type":"comment","by":"carol","parent":101,
                     "kids":[101],"text":"Nested comment"}
                """.trimIndent()
                else -> return MockResponse().setResponseCode(404)
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        }
    }
}
