package tw.kevinzhang.newshub.extension.wtako

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class WtakoUrlPolicyTest {
    @Test
    fun `rthost HTTP URL is upgraded to HTTPS`() {
        assertEquals(
            "https://rthost.win/sd/index.htm?123",
            WtakoUrlPolicy.canonicalize("http://rthost.win/sd/index.htm?123"),
        )
    }

    @Test
    fun `unverified HTTP host remains unchanged`() {
        assertEquals(
            "http://example.com/image.jpg",
            WtakoUrlPolicy.canonicalize("http://example.com/image.jpg"),
        )
    }

    @Test
    fun `rthost HTTP redirect location is upgraded to HTTPS`() {
        assertEquals(
            "https://rthost.win/sd/index.htm?123",
            WtakoUrlPolicy.canonicalizeRedirect(
                "https://rthost.win/sd/pixmicat.php".toHttpUrl(),
                "http://rthost.win/sd/index.htm?123",
            ),
        )
    }
}
