package tw.kevinzhang.newshub.extension.mobile01

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mobile01UrlPolicyTest {
    @Test
    fun `accepts only canonical Mobile01 listing and thread URLs`() {
        assertEquals(350, Mobile01UrlPolicy.boardId("https://www.mobile01.com/topiclist.php?f=350"))
        assertEquals("5356590", Mobile01UrlPolicy.thread("https://www.mobile01.com/topicdetail.php?f=350&t=5356590&p=2")?.threadId)
        assertNull(Mobile01UrlPolicy.thread("http://www.mobile01.com/topicdetail.php?f=350&t=5356590"))
        assertNull(Mobile01UrlPolicy.thread("https://m.mobile01.com/topicdetail.php?f=350&t=5356590"))
        assertNull(Mobile01UrlPolicy.thread("https://www.mobile01.com/redirect?url=https://evil.example"))
    }

    @Test
    fun `request builder identifies NewsHub while requesting a public browser document`() {
        val request = Mobile01RequestBuilder.thread("https://www.mobile01.com/topicdetail.php?f=350&t=5356590")
        assertTrue(request.header("User-Agent")!!.contains("NewsHub-Mobile01/0.1.3"))
        assertEquals("document", request.header("Sec-Fetch-Dest"))
        assertEquals("navigate", request.header("Sec-Fetch-Mode"))
        assertEquals("none", request.header("Sec-Fetch-Site"))
        assertNull(request.header("Cookie"))
        assertTrue(request.headers.names().none { it.equals("Referer", true) })
    }

    @Test
    fun `network policy does not follow redirects outside the allowlist`() {
        val client = okhttp3.OkHttpClient().withMobile01NetworkPolicy()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
