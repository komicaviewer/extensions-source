package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import tw.kevinzhang.extension_api.EynyChallengeProof
import tw.kevinzhang.extension_api.NamedCookieCapability
import java.io.IOException

class EynyGatewayTest {
    @Test
    fun `trusted base element selects the rotating exact host`() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedHosts += chain.request().url.host
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<base href='https://www.eyny.com/'><main>EYNY</main>".toResponseBody())
                .build()
        }.build()
        val gateway = EynyGateway(client, RecordingNamedCookies())

        gateway.get("https://eyny.com/")
        gateway.get("https://eyny.com/forum-27-1.html")

        assertEquals("www.eyny.com", gateway.activeHost)
        assertEquals(listOf("eyny.com", "www.eyny.com"), requestedHosts)
    }

    @Test
    fun `observed www53 redirect is followed but unknown numbered host remains rejected`() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedHosts += chain.request().url.host
            if (requestedHosts.size == 1) {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://www53.eyny.com/")
                    .body("".toResponseBody())
                    .build()
            } else {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<main>EYNY</main>".toResponseBody())
                    .build()
            }
        }.build()

        val gateway = EynyGateway(client, RecordingNamedCookies())
        gateway.get("https://eyny.com/")

        assertEquals(listOf("eyny.com", "www53.eyny.com"), requestedHosts)
        assertEquals("www53.eyny.com", gateway.activeHost)
        assertNull(EynyUrlPolicy.resolve("https://eyny.com/", "https://www54.eyny.com/"))
    }

    @Test
    fun `foreign base element cannot expand the host allowlist`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<base href='https://evil.example/'><main>EYNY</main>".toResponseBody())
                .build()
        }.build()
        val gateway = EynyGateway(client, RecordingNamedCookies())

        gateway.get("https://eyny.com/")

        assertEquals("eyny.com", gateway.activeHost)
    }

    @Test
    fun `redirect exchanges honour their own limit`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val nextHost = if (chain.request().url.host == "eyny.com") "www.eyny.com" else "eyny.com"
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "https://$nextHost/")
                .body("".toResponseBody())
                .build()
        }.build()

        try {
            EynyGateway(client, RecordingNamedCookies()).get("https://eyny.com/")
            fail("expected redirect bound")
        } catch (error: IOException) {
            assertEquals("Too many EYNY redirects", error.message)
        }
        assertEquals(6, calls)
    }

    @Test
    fun `challenge delegates one constrained proof to the host capability`() = runBlocking {
        var calls = 0
        val namedCookies = RecordingNamedCookies()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls++
                when (calls) {
                    1 -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://www.eyny.com/")
                        .body("".toResponseBody())
                        .build()
                    2 -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """<script>
                                var challenge = "abcdeffedcba1234";
                                var ts = "1784585056";
                                var diff = 1;
                                document.cookie = "9bd3f9c_n=" + nonce;
                            </script>""".trimIndent().toResponseBody(),
                        )
                        .build()
                    else -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<main>EYNY</main>".toResponseBody())
                        .build()
                }
            }
            .build()

        EynyGateway(client, namedCookies).get("https://eyny.com/")

        assertEquals(3, calls)
        assertEquals(
            EynyChallengeProof(
                host = "www.eyny.com",
                cookiePrefix = "9bd3f9c",
                nonce = 5,
                timestamp = "1784585056",
                challenge = "abcdeffedcba1234",
            ),
            namedCookies.proofs.single(),
        )
    }

    private class RecordingNamedCookies : NamedCookieCapability {
        val proofs = mutableListOf<EynyChallengeProof>()

        override suspend fun hasPttAdultConsent(): Boolean = false

        override suspend fun storeEynyChallengeProof(proof: EynyChallengeProof) {
            proofs += proof
        }
    }
}
