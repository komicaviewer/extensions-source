package tw.kevinzhang.newshub.extension.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttUrlPolicyTest {
    @Test fun `only canonical HTTPS PTT article URLs are requestable`() {
        assertEquals(
            "https://www.ptt.cc/bbs/C_Chat/M.1720000000.A.ABC.html",
            PttUrlPolicy.articleUrl("https://www.ptt.cc/bbs/C_Chat/M.1720000000.A.ABC.html"),
        )
        assertNull(PttUrlPolicy.articleUrl("http://www.ptt.cc/bbs/C_Chat/M.1720000000.A.ABC.html"))
        assertNull(PttUrlPolicy.articleUrl("https://evil.example/bbs/C_Chat/M.1720000000.A.ABC.html"))
    }

    @Test fun `external content only permits web URLs`() {
        assertEquals("https://example.com/a", PttUrlPolicy.safeExternalUrl("https://example.com/a"))
        assertEquals("https://i.imgur.com/image.jpg", PttUrlPolicy.safeExternalUrl("http://i.imgur.com/image.jpg"))
        assertEquals("http://example.com/image.jpg", PttUrlPolicy.safeExternalUrl("http://example.com/image.jpg"))
        assertNull(PttUrlPolicy.safeExternalUrl("javascript:alert(1)"))
        assertTrue(PttUrlPolicy.isBoardName("C_Chat"))
    }

    @Test fun `over18 gate detects the current javascript redirect and form fallback`() {
        assertTrue(PttConsentGate.isRequired(fixture("over18-script.html"), "https://www.ptt.cc/bbs/C_Chat/index.html"))
        assertTrue(!PttConsentGate.isRequired(
            fixture("over18-script.html"),
            "https://www.ptt.cc/bbs/C_Chat/index.html",
            true,
        ))
        assertTrue(PttConsentGate.isRequired("<form action='/ask/over18'>十八歲</form>", "https://www.ptt.cc/ask/over18"))
        assertTrue(!PttConsentGate.isRequired(fixture("board.html"), "https://www.ptt.cc/bbs/Stock/index.html"))
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/ptt/$name")).readText()
}
