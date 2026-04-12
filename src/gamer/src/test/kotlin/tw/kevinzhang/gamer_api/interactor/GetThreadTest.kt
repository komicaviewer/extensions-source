package tw.kevinzhang.gamer_api.interactor

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.gamer_api.request.RequestBuilderImpl

class GetThreadTest {

    private val client = OkHttpClient()
    private val getThread = GetThread(client)

    @Test
    fun `invoke returns posts for a valid thread request`() = runTest {
        val req = RequestBuilderImpl()
            .setUrl("https://forum.gamer.com.tw/C.php?bsn=60076&snA=1".toHttpUrl())
            .build()

        val result = getThread.invoke(req)

        assertTrue(result.isNotEmpty())
    }
}
