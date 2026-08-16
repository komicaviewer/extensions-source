package tw.kevinzhang.newshub.extension.zawarudo.komica2

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.extension_api.SourceFailureCode
import tw.kevinzhang.extension_api.SourceFailures
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableException
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableReason
import tw.kevinzhang.newshub.extension.runtime.asTestSourceRuntime

class Komica2ZawarudoSourceTest {
    @Test
    fun `Cloudflare challenge crosses extension boundary as access challenge`() = runBlocking {
        val source = Komica2ZawarudoSource().apply {
            onAttach(cloudflareChallengeClient().asTestSourceRuntime())
        }
        val board = source.getBoardPage(BoardPageRequest()).boards.first()

        val failure = try {
            source.getThreadSummaries(board, page = 1)
            throw AssertionError("Expected SourceSiteUnavailableException")
        } catch (exception: SourceSiteUnavailableException) {
            exception
        }

        assertEquals(403, failure.statusCode)
        assertEquals(SourceSiteUnavailableReason.ACCESS_CHALLENGE, failure.reason)
        assertEquals(
            SourceFailureCode.ACCESS_CHALLENGE,
            SourceFailures.fromThrowable(failure, "thread_summaries").code,
        )
    }

    private fun cloudflareChallengeClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .header("cf-mitigated", "challenge")
                .body("challenge fixture".toResponseBody())
                .build()
        }
        .build()
}
