package tw.kevinzhang.newshub.extension.twocat.komica2

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.BoardQuery
import tw.kevinzhang.extension_api.model.ThreadSummary
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards
import tw.kevinzhang.newshub.extension.runtime.asTestSourceRuntime
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableException
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableReason

class Komica2TwocatSourceTest {
    private val board = Komica2TwocatBoards.all.first()

    @Test
    fun `source version is incremented and board catalog has no categories`() = runBlocking {
        val source = Komica2TwocatSource()

        assertEquals(5, source.version)
        assertEquals(emptyList<Any>(), source.getBoardCategories())
        assertEquals(Komica2TwocatBoards.all, source.getBoardPage(BoardPageRequest(pageSize = 50)).boards)
        assertEquals(listOf(board), source.getBoardPage(BoardPageRequest(BoardQuery(text = "東方"))).boards)
        assertEquals(emptyList<Any>(), source.getBoardPage(BoardPageRequest(BoardQuery(text = "不存在"))).boards)
        assertEquals(emptyList<Any>(), source.getBoardPage(BoardPageRequest(BoardQuery(categoryId = "anime"))).boards)
    }

    @Test
    fun `summary and thread HTTP errors are typed without leaking request URLs`() = runBlocking {
        val source = Komica2TwocatSource().apply { onAttach(notFoundClient().asTestSourceRuntime()) }
        val summary = ThreadSummary(
            sourceId = source.id,
            boardUrl = board.url,
            id = "${board.url}?res=42",
            title = null,
            author = null,
            createdAt = null,
            commentCount = null,
            rawImage = null,
            thumbnail = null,
            previewContent = emptyList(),
        )

        val summaryFailure = assertSiteUnavailable { source.getThreadSummaries(board, page = 1) }
        val threadFailure = assertSiteUnavailable { source.getThread(summary) }
        assertEquals(404, summaryFailure.statusCode)
        assertEquals(404, threadFailure.statusCode)
        assertEquals(SourceSiteUnavailableReason.HTTP_ERROR, summaryFailure.reason)
        assertEquals(SourceSiteUnavailableReason.HTTP_ERROR, threadFailure.reason)
        assertEquals(false, summaryFailure.message.orEmpty().contains("2cat.org"))
        assertEquals(false, threadFailure.message.orEmpty().contains("res=42"))
    }

    private val hanime = Komica2TwocatBoards.all.single { it.url == "https://2cat.org/~hanime/" }
    private val hiso = Komica2TwocatBoards.all.single { it.url == "https://2cat.org/hiso/pixmicat.php" }

    private fun hanimeFake() = FakeTwocat().apply {
        pages = { slug, url ->
            if (slug != "hanime") null
            else resource(if ("res=" in url) "hanime-thread.html" else "hanime-summaries.html")
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource(name)) { name }.readText()

    @Test
    fun `gated board summaries use decoy and keep real-path ids`() = runBlocking {
        val fake = hanimeFake()
        val source = Komica2TwocatSource().apply { onAttach(fake.client.asTestSourceRuntime()) }

        val summaries = source.getThreadSummaries(hanime, page = 1)

        assertEquals(
            listOf("https://2cat.uk/hanime/?res=18734", "https://2cat.uk/hanime/?res=18733"),
            summaries.map { it.id },
        )
        assertEquals(setOf("https://2cat.org/~hanime/"), summaries.map { it.boardUrl }.toSet())
        assertEquals("https://2cat.uk/granblue/pixmicat.php", fake.requests.last())
    }

    @Test
    fun `gated thread fetch rewrites stored id to current decoy`() = runBlocking {
        val fake = hanimeFake()
        val source = Komica2TwocatSource().apply { onAttach(fake.client.asTestSourceRuntime()) }
        val summary = source.getThreadSummaries(hanime, page = 1).first()
        fake.decoy = "/webgame2/"
        fake.expire()

        val thread = source.getThread(summary)

        assertEquals(listOf("18734", "18738"), thread.posts.map { it.id })
        assertEquals("https://2cat.uk/hanime/?res=18734", thread.id)
        assertEquals("https://2cat.uk/webgame2/?res=18734", fake.requests.last())
    }

    @Test
    fun `hiso board uses plain pixmicat request`() = runBlocking {
        val fake = FakeTwocat()
        val source = Komica2TwocatSource().apply { onAttach(fake.client.asTestSourceRuntime()) }

        assertSiteUnavailable { source.getThreadSummaries(hiso, page = 1) }

        assertEquals(listOf("https://2cat.org/hiso/pixmicat.php"), fake.requests)
    }

    @Test
    fun `summary reply count excludes the head post`() = runBlocking {
        val fake = hanimeFake()
        val source = Komica2TwocatSource().apply { onAttach(fake.client.asTestSourceRuntime()) }

        assertEquals(listOf(1, 3), source.getThreadSummaries(hanime, page = 1).map { it.commentCount })
    }

    @Test
    fun `gated thread url is the board entry`() = runBlocking {
        val fake = hanimeFake()
        val source = Komica2TwocatSource().apply { onAttach(fake.client.asTestSourceRuntime()) }
        val summary = source.getThreadSummaries(hanime, page = 1).first()

        assertEquals("https://2cat.org/~hanime/", source.getThread(summary).url)
    }

    @Test
    fun `web url for gated thread is board entry`() = runBlocking {
        val fake = hanimeFake()
        val source = Komica2TwocatSource().apply { onAttach(fake.client.asTestSourceRuntime()) }
        val summary = source.getThreadSummaries(hanime, page = 1).first()

        assertEquals("https://2cat.org/~hanime/", source.getWebUrl(summary))
    }

    private fun notFoundClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request().newBuilder().url("https://2cat.uk/pixmicat.php/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody())
                .build()
        }
        .build()

    private suspend fun assertSiteUnavailable(
        block: suspend () -> Unit,
    ): SourceSiteUnavailableException = try {
        block()
        throw AssertionError("Expected SourceSiteUnavailableException")
    } catch (exception: SourceSiteUnavailableException) {
        exception
    }
}
