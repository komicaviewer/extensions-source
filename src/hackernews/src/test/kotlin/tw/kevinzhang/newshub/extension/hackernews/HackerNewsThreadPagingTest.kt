package tw.kevinzhang.newshub.extension.hackernews

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.ThreadSummary

class HackerNewsThreadPagingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `first page includes OP and metadata then remaining pages preserve preorder replies`() = runTest {
        val source = sourceFor(pagedTree())
        val summary = summary(100)

        val first = source.getThreadPage(summary, pageToken = null)
        val second = source.getThreadPage(summary, first.nextPageToken!!)

        assertEquals(50, first.posts.size)
        assertEquals("100", first.posts.first().id)
        assertEquals((listOf(101L, 103L) + (104L..150L)).map(Long::toString), first.posts.drop(1).map { it.id })
        assertEquals("100", first.metadata?.id)
        assertEquals("Paged root", first.metadata?.title)
        assertEquals("https://news.ycombinator.com/item?id=100", first.metadata?.url)
        assertNotNull(first.nextPageToken)

        assertEquals(listOf("151", "102"), second.posts.map { it.id })
        assertEquals(Paragraph.ReplyTo("103"), second.posts.first().content.first())
        assertEquals(Paragraph.ReplyTo("100"), second.posts.last().content.first())
        assertNull(second.metadata)
        assertNull(second.nextPageToken)

        val allIds = first.posts.map { it.id } + second.posts.map { it.id }
        assertEquals(listOf("100", "101", "103") + (104L..151L).map(Long::toString) + "102", allIds)
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test
    fun `self contained token continues in a fresh source instance and rejects invalid cursors`() = runTest {
        val responses = pagedTree()
        val original = sourceFor(responses)
        val summary = summary(100)
        val first = original.getThreadPage(summary, pageToken = null)
        val token = first.nextPageToken!!

        val restored = sourceFor(responses)
        val resumed = restored.getThreadPage(summary, token)

        assertEquals(listOf("151", "102"), resumed.posts.map { it.id })
        assertNull(resumed.nextPageToken)
        assertNotEquals(token, first.posts.first().id)

        assertRejected { original.getThreadPage(summary, "not-a-hacker-news-cursor") }
        assertRejected { original.getThreadPage(summary.copy(id = "999"), token) }
        assertRejected { original.getThreadPage(summary, tamper(token)) }
        assertRejected { original.getThreadPage(summary, "x".repeat(196_609)) }
    }

    @Test
    fun `null items cannot make a page exceed its request budget`() = runTest {
        val source = sourceFor(
            mapOf(
                300L to story(300, "Null item budget", kids = (301L..400L).toList()),
            ),
        )
        val summary = summary(300)

        val first = source.getThreadPage(summary, pageToken = null)
        val requestsAfterFirstPage = server.requestCount
        val second = source.getThreadPage(summary, first.nextPageToken!!)
        val requestsAfterSecondPage = server.requestCount

        assertEquals(listOf("300"), first.posts.map { it.id })
        assertNotNull(first.nextPageToken)
        assertEquals(50, requestsAfterFirstPage) // root + at most 49 examined comments
        assertTrue(second.posts.isEmpty())
        assertNotNull(second.nextPageToken)
        assertEquals(50, requestsAfterSecondPage - requestsAfterFirstPage)
    }

    @Test
    fun `cursor codec rejects duplicate and structurally inconsistent state`() {
        val codec = HackerNewsCursorCodec()

        assertCursorRejected {
            codec.decode(cursorToken("""{"version":1,"rootId":100,"frontier":[101,101],"visited":[100,101],"examined":0}"""), 100)
        }
        assertCursorRejected {
            codec.decode(cursorToken("""{"version":1,"rootId":100,"frontier":[101],"visited":[101],"examined":0}"""), 100)
        }
        assertCursorRejected {
            codec.decode(cursorToken("""{"version":1,"rootId":100,"frontier":[100],"visited":[100],"examined":0}"""), 100)
        }
    }

    @Test
    fun `cycles duplicates deleted and dead items remain safe and preserve reply parents`() = runTest {
        val source = sourceFor(
            mapOf(
                200L to story(200, "Special cases", kids = listOf(201, 202, 201)),
                201L to comment(201, parent = 200, kids = listOf(203), deleted = true),
                202L to comment(202, parent = 200, text = "sibling"),
                203L to comment(203, parent = 201, kids = listOf(201), dead = true),
            ),
        )

        val page = source.getThreadPage(summary(200), pageToken = null)

        assertEquals(listOf("200", "201", "203", "202"), page.posts.map { it.id })
        assertEquals(listOf(Paragraph.ReplyTo("200"), Paragraph.Text("[deleted]")), page.posts[1].content)
        assertEquals(listOf(Paragraph.ReplyTo("201"), Paragraph.Text("[dead]")), page.posts[2].content)
        assertEquals(Paragraph.ReplyTo("200"), page.posts[3].content.first())
        assertNull(page.nextPageToken)
    }

    @Test
    fun `legacy getThread returns only the initial page`() = runTest {
        val source = sourceFor(pagedTree())

        val thread = source.getThread(summary(100))

        assertEquals(50, thread.posts.size)
        assertEquals("100", thread.posts.first().id)
        assertFalse(thread.posts.any { it.id == "151" || it.id == "102" })
        assertEquals("Paged root", thread.title)
        assertEquals("https://news.ycombinator.com/item?id=100", thread.url)
    }

    private fun sourceFor(items: Map<Long, String>): HackerNewsSource {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val itemId = request.requestUrl?.encodedPath
                    ?.removePrefix("/v0/item/")
                    ?.removeSuffix(".json")
                    ?.toLongOrNull()
                val body = itemId?.let(items::get) ?: "null"
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        return HackerNewsSource(HackerNewsApi(OkHttpClient(), server.url("/v0/")))
    }

    private fun summary(id: Long) = ThreadSummary(
        sourceId = HackerNewsBoards.SOURCE_ID,
        boardUrl = HackerNewsBoards.all.first().url,
        id = id.toString(),
        title = "Fallback title",
        author = null,
        createdAt = null,
        commentCount = null,
        rawImage = null,
        thumbnail = null,
        previewContent = emptyList(),
    )

    private fun pagedTree(): Map<Long, String> = buildMap {
        put(100, story(100, "Paged root", kids = listOf(101, 102)))
        put(101, comment(101, parent = 100, kids = listOf(103)))
        put(103, comment(103, parent = 101, kids = (104L..151L).toList()))
        (104L..151L).forEach { put(it, comment(it, parent = 103)) }
        put(102, comment(102, parent = 100, text = "second top-level reply"))
    }

    private fun story(id: Long, title: String, kids: List<Long>? = null): String = """
        {"id":$id,"type":"story","title":"$title","kids":${kids.jsonArray()}}
    """.trimIndent()

    private fun comment(
        id: Long,
        parent: Long,
        kids: List<Long>? = null,
        text: String = "comment $id",
        deleted: Boolean = false,
        dead: Boolean = false,
    ): String = """
        {"id":$id,"type":"comment","parent":$parent,"text":"$text",
         "kids":${kids.jsonArray()},"deleted":$deleted,"dead":$dead}
    """.trimIndent()

    private fun List<Long>?.jsonArray(): String = this?.joinToString(prefix = "[", postfix = "]") ?: "null"

    private fun tamper(token: String): String =
        (if (token.first() == 'A') "B" else "A") + token.drop(1)

    private fun cursorToken(json: String): String = json.encodeUtf8().base64Url()

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected invalid thread page token to be rejected")
    }

    private fun assertCursorRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected invalid cursor structure to be rejected")
    }
}
