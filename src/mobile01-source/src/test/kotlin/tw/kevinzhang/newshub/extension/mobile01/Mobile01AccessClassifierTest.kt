package tw.kevinzhang.newshub.extension.mobile01

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mobile01AccessClassifierTest {
    @Test
    fun `classifies Akamai denial rate limits and successful challenge documents`() {
        assertEquals(
            Mobile01AccessFailure.ACCESS_DENIED,
            Mobile01AccessClassifier.classify(403, "<h1>Access Denied</h1> errors.edgesuite.net"),
        )
        assertEquals(Mobile01AccessFailure.RATE_LIMITED, Mobile01AccessClassifier.classify(429, "slow down"))
        assertEquals(
            Mobile01AccessFailure.BOT_CHALLENGE,
            Mobile01AccessClassifier.classify(200, "<form id=\"challenge-form\"></form>"),
        )
    }

    @Test
    fun `does not classify ordinary posts that mention bot protection terms`() {
        val body = "<article>比較 Akamai、captcha 與 challenge 的實作方式</article>"

        assertNull(Mobile01AccessClassifier.classify(200, body))
    }
}
