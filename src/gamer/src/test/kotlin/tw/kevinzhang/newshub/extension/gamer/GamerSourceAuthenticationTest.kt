package tw.kevinzhang.newshub.extension.gamer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.AuthSpec
import tw.kevinzhang.extension_api.AuthState
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime

class GamerSourceAuthenticationTest {

    @Test
    fun `source declares Gamer web cookie authentication`() {
        val spec = GamerSource().authSpec as AuthSpec.WebCookie

        assertEquals("https://user.gamer.com.tw/login.php", spec.loginUrl)
        assertEquals(
            setOf("user.gamer.com.tw", "forum.gamer.com.tw", "www.gamer.com.tw"),
            spec.allowedHosts,
        )
        assertEquals(
            setOf("https://user.gamer.com.tw", "https://forum.gamer.com.tw"),
            spec.cookieOrigins,
        )
        assertEquals(setOf("gamer.com.tw"), spec.cookieDomains)
    }

    @Test
    fun `validateSession requests the protected board and expires an invalid session`() = runTest {
        var requestedUrl: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requestedUrl = chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()
        val authentication = FakeAuthenticationSession()
        val source = GamerSource().apply {
            onAttach(object : SourceRuntime {
                override val httpClient = client
                override val authentication = authentication
            })
        }

        assertFalse(source.validateSession())
        assertEquals("https://forum.gamer.com.tw/B.php?bsn=60076", requestedUrl)
        assertTrue(authentication.expired)
    }

    private class FakeAuthenticationSession : AuthenticationSession {
        override val state = MutableStateFlow(AuthState.SignedIn)
        var expired = false

        override fun markExpired() {
            expired = true
            state.value = AuthState.Expired
        }
    }
}
