package tw.kevinzhang.newshub.extension.nagatoyuki

import org.junit.Assert.assertEquals
import org.junit.Test

class NagatoyukiRequestBuilderTest {
    @Test
    fun `board page URLs follow Vichan index convention`() {
        assertEquals(
            "https://selene.zawarudo.org/costumade",
            NagatoyukiRequestBuilder.summaries("https://selene.zawarudo.org/costumade", 1).url.toString(),
        )
        assertEquals(
            "https://selene.zawarudo.org/costumade/2.html",
            NagatoyukiRequestBuilder.summaries("https://selene.zawarudo.org/costumade", 2).url.toString(),
        )
        assertEquals(
            "https://www.gomiga.org/bluearchive/res/3212.html",
            NagatoyukiRequestBuilder.threadUrl("https://www.gomiga.org/bluearchive", "3212"),
        )
    }
}
