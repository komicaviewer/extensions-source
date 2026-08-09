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

class Komica2TwocatSourceTest {
    private val board = Komica2TwocatBoards.all.single()

    @Test
    fun `source version is incremented and board catalog has no categories`() = runBlocking {
        val source = Komica2TwocatSource()

        assertEquals(3, source.version)
        assertEquals(emptyList<Any>(), source.getBoardCategories())
        assertEquals(listOf(board), source.getBoardPage(BoardPageRequest()).boards)
        assertEquals(listOf(board), source.getBoardPage(BoardPageRequest(BoardQuery(text = "東方"))).boards)
        assertEquals(emptyList<Any>(), source.getBoardPage(BoardPageRequest(BoardQuery(text = "不存在"))).boards)
        assertEquals(emptyList<Any>(), source.getBoardPage(BoardPageRequest(BoardQuery(categoryId = "anime"))).boards)
    }

    @Test
    fun `summary and thread HTTP errors report the brokered request URL`() = runBlocking {
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

        assertEquals(
            "HTTP 404: https://2cat.org/touhoux/pixmicat.php",
            assertHttpException { source.getThreadSummaries(board, page = 1) }.message,
        )
        assertEquals(
            "HTTP 404: https://2cat.org/touhoux/pixmicat.php?res=42",
            assertHttpException { source.getThread(summary) }.message,
        )
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

    private suspend fun assertHttpException(block: suspend () -> Unit): HttpException = try {
        block()
        throw AssertionError("Expected HttpException")
    } catch (exception: HttpException) {
        exception
    }
}
