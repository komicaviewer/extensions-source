package tw.kevinzhang.gamer_api.interactor

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import tw.kevinzhang.extension_api.AuthenticationRequiredException

class AuthenticationRequiredTest {

    @Test
    fun `all protected interactors convert 401 and 403 to the host exception`() = runTest {
        listOf(401, 403).forEach { statusCode ->
            assertAllInteractorsRequireAuthentication(responseClient(statusCode))
        }
    }

    @Test
    fun `all protected interactors convert Gamer login redirects to the host exception`() = runTest {
        assertAllInteractorsRequireAuthentication(
            responseClient(
                statusCode = 200,
                finalUrl = "https://user.gamer.com.tw/login.php?loginPage=forum",
            ),
        )
    }

    private suspend fun assertAllInteractorsRequireAuthentication(client: OkHttpClient) {
        val boardRequest = request("https://forum.gamer.com.tw/B.php?bsn=60076")
        val threadRequest = request("https://forum.gamer.com.tw/C.php?bsn=60076&snA=1")
        val commentRequest = request("https://forum.gamer.com.tw/ajax/moreCommend.php?bsn=60076&snB=1")

        assertAuthenticationRequired { GetThreadSummaries(client).invoke(boardRequest) }
        assertAuthenticationRequired { GetThread(client).invoke(threadRequest) }
        assertAuthenticationRequired { GetAllComment(client).invoke(commentRequest) }
    }

    private fun responseClient(statusCode: Int, finalUrl: String? = null): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(request(finalUrl ?: chain.request().url.toString()))
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("test")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()

    private fun request(url: String): Request = Request.Builder().url(url).build()

    private suspend fun assertAuthenticationRequired(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected AuthenticationRequiredException")
        } catch (error: AuthenticationRequiredException) {
            assertEquals(true, error.isUserAction)
        }
    }
}
