package tw.kevinzhang.newshub.extension.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tw.kevinzhang.extension_api.AuthSpec
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class PttSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        PttSource(), PttBoardCatalog.SOURCE_ID, "PTT 批踢踢實業坊", "zh-TW", 4, true,
    )

    @Test fun `runtime auth descriptor comes from Source contract`() {
        val auth = PttSource().authSpec as AuthSpec.WebCookie
        assertEquals("https://www.ptt.cc/ask/over18?from=%2Fbbs%2FC_Chat%2Findex.html", auth.loginUrl)
        assertEquals(setOf("www.ptt.cc"), auth.allowedHosts)
        assertEquals(setOf("https://www.ptt.cc"), auth.cookieOrigins)
        assertEquals(setOf("ptt.cc"), auth.cookieDomains)
        assertFalse(auth.allowedHosts.any { '*' in it })
    }
}
