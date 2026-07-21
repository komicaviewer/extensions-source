package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
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
                .body("<base href='https://www53.eyny.com/'><main>EYNY</main>".toResponseBody())
                .build()
        }.build()
        val gateway = EynyGateway(client)

        gateway.get("https://eyny.com/")
        gateway.get("https://eyny.com/forum-27-1.html")

        assertEquals("www53.eyny.com", gateway.activeHost)
        assertEquals(listOf("eyny.com", "www53.eyny.com"), requestedHosts)
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
        val gateway = EynyGateway(client)

        gateway.get("https://eyny.com/")

        assertEquals("eyny.com", gateway.activeHost)
    }

    @Test
    fun `redirect exchanges honour their own limit`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val nextHost = if (chain.request().url.host == "eyny.com") "www53.eyny.com" else "eyny.com"
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
            EynyGateway(client).get("https://eyny.com/")
            fail("expected redirect bound")
        } catch (error: IOException) {
            assertEquals("Too many EYNY redirects", error.message)
        }
        assertEquals(6, calls)
    }

    @Test
    fun `challenge refreshes host-only and shared-domain cookie identities with one proof`() = runBlocking {
        var calls = 0
        var savedCookies = emptyList<Cookie>()
        val cookieJar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                savedCookies = cookies
            }
        }
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                calls++
                when (calls) {
                    1 -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://www53.eyny.com/")
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

        EynyGateway(client).get("https://eyny.com/")

        assertEquals(3, calls)
        assertEquals(
            setOf("9bd3f9c_n", "9bd3f9c_ts", "9bd3f9c_ch"),
            savedCookies.mapTo(linkedSetOf()) { it.name },
        )
        savedCookies.groupBy { it.name }.values.forEach { sameName ->
            assertEquals(2, sameName.size)
            assertEquals(1, sameName.mapTo(linkedSetOf()) { it.value }.size)
            assertTrue(sameName.any { it.hostOnly && it.domain == "www53.eyny.com" })
            assertTrue(sameName.any { !it.hostOnly && it.domain == "eyny.com" })
        }
    }
}
