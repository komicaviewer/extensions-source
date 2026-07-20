package tw.kevinzhang.newshub.extension.hackernews

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HackerNewsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HackerNewsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HackerNewsApi(OkHttpClient(), server.url("/v0/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `feed and item batches preserve feed order`() = runTest {
        server.dispatcher = jsonDispatcher(
            mapOf(
                "/v0/topstories.json" to "[3,1,2]",
                "/v0/item/1.json" to """{"id":1,"type":"story","title":"One"}""",
                "/v0/item/2.json" to """{"id":2,"type":"story","title":"Two"}""",
                "/v0/item/3.json" to """{"id":3,"type":"story","title":"Three"}""",
            ),
        )

        val ids = api.getFeed(HackerNewsFeed.TOP)
        val items = api.getItems(ids)

        assertEquals(listOf(3L, 1L, 2L), ids)
        assertEquals(listOf(3L, 1L, 2L), items.map { it.id })
    }

    @Test
    fun `concurrent duplicate item requests share one network call and use cache`() = runTest {
        server.dispatcher = jsonDispatcher(
            mapOf("/v0/item/7.json" to """{"id":7,"type":"story"}"""),
        )

        (1..10).map { async { api.getItem(7) } }.awaitAll()
        api.getItem(7)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `non successful responses surface as io errors`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { api.getFeed(HackerNewsFeed.TOP) }
        }
    }

    private fun jsonDispatcher(responses: Map<String, String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = responses[request.requestUrl?.encodedPath]
                ?: return MockResponse().setResponseCode(404)
            return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        }
    }
}
