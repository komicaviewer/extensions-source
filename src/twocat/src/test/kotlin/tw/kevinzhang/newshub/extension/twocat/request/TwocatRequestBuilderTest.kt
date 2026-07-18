package tw.kevinzhang.newshub.extension.twocat.request

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class TwocatRequestBuilderTest {

    @Test
    fun `pagination uses the explicit board URL as its base path`() {
        val boardUrl = "https://2cat.org/career".toHttpUrl()

        val request = TwocatRequestBuilder(baseBoardUrl = boardUrl)
            .setUrl(boardUrl)
            .setPage(2)
            .build()

        assertEquals("https://2cat.org/career?page=2", request.url.toString())
    }

    @Test
    fun `pagination replaces the page on a path below the explicit board URL`() {
        val boardUrl = "https://2cat.org/career".toHttpUrl()

        val request = TwocatRequestBuilder(baseBoardUrl = boardUrl)
            .setUrl("https://2cat.org/career/thread/123?page=1".toHttpUrl())
            .setPage(2)
            .build()

        assertEquals("https://2cat.org/career/thread/123?page=2", request.url.toString())
    }
}
