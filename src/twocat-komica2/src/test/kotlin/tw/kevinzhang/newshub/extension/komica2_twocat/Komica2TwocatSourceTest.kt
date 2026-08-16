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
    private val board = Komica2TwocatBoards.all.single()

    @Test
    fun `source version is incremented and board catalog has no categories`() = runBlocking {
        val source = Komica2TwocatSource()

        assertEquals(4, source.version)
        assertEquals(emptyList<Any>(), source.getBoardCategories())
        assertEquals(listOf(board), source.getBoardPage(BoardPageRequest()).boards)
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
