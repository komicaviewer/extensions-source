package tw.kevinzhang.newshub.extension.nagatoyuki

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import tw.kevinzhang.extension_api.SourceFailureCode
import tw.kevinzhang.extension_api.SourceFailureWire
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableException
import tw.kevinzhang.newshub.extension.runtime.asTestSourceRuntime

class NagatoyukiSourceTest {
    @Test
    fun `Cloudflare challenge is typed without leaking request or response evidence`() = runBlocking {
        val secret = "session=private-cookie"
        val source = NagatoyukiSource().apply {
            onAttach(
                OkHttpClient.Builder().addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden $secret")
                        .header("cf-mitigated", "challenge")
                        .body("challenge body $secret".toResponseBody())
                        .build()
                }.build().asTestSourceRuntime(),
            )
        }
        val board = source.getBoardPage(BoardPageRequest()).boards.first()

        val failure = assertThrows(SourceSiteUnavailableException::class.java) {
            runBlocking { source.getThreadSummaries(board, 1) }
        }
        val wire = SourceFailureWire.encode(failure.failure)

        assertEquals(SourceFailureCode.ACCESS_CHALLENGE, failure.failure.code)
        assertFalse(wire.contains(secret))
        assertFalse(wire.contains(board.url))
        assertFalse(failure.message.orEmpty().contains(secret))
    }
}
