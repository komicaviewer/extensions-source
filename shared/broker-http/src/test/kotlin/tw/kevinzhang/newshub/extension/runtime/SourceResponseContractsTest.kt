package tw.kevinzhang.newshub.extension.runtime

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import tw.kevinzhang.extension_api.SourceFailureCode
import tw.kevinzhang.extension_api.SourceFailures

class SourceResponseContractsTest {
    @Test
    fun `successful response remains available to its parser`() {
        val response = response(200)

        assertSame(response, response.requireSourceSuccess())
    }

    @Test
    fun `ordinary non-success response crosses Binder as site unavailable`() {
        val failure = assertThrows(SourceSiteUnavailableException::class.java) {
            response(502).requireSourceSuccess()
        }

        assertEquals(502, failure.statusCode)
        assertEquals(SourceSiteUnavailableReason.HTTP_ERROR, failure.reason)
        assertEquals(
            SourceFailureCode.SITE_UNAVAILABLE,
            SourceFailures.fromThrowable(failure, "thread_summaries").code,
        )
    }

    @Test
    fun `Cloudflare challenge has a stable site-unavailable reason`() {
        val failure = assertThrows(SourceSiteUnavailableException::class.java) {
            response(403, mapOf("cf-mitigated" to "challenge")).requireSourceSuccess()
        }

        assertEquals(SourceSiteUnavailableReason.ACCESS_CHALLENGE, failure.reason)
        assertEquals(
            SourceFailureCode.SITE_UNAVAILABLE,
            SourceFailures.fromThrowable(failure, "thread_summaries").code,
        )
    }

    @Test
    fun `HTML contract failure crosses Binder as parser contract`() {
        val failure = SourceParserContractException("missing_thread_container")

        assertEquals(
            SourceFailureCode.PARSER_CONTRACT,
            SourceFailures.fromThrowable(failure, "thread_page").code,
        )
    }

    private fun response(code: Int, headers: Map<String, String> = emptyMap()): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.invalid/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fixture")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(ByteArray(0).toResponseBody())
            .build()
}
