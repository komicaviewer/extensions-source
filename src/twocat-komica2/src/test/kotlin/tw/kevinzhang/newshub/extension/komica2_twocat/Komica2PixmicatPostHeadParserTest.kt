package tw.kevinzhang.newshub.extension.twocat.komica2

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.Komica2PixmicatPostHeadParser

class Komica2PixmicatPostHeadParserTest {
    @Test
    fun `bracketed title does not swallow the date metadata`() {
        val post = Jsoup.parse(
            """<div class="threadpost"><span class="title">[tamangoworks] Fixture</span> [26/08/17(一)21:18 ID:9o50D4M6]</div>""",
        ).selectFirst("div.threadpost")!!
        val parser = Komica2PixmicatPostHeadParser()
        val url = "https://2cat.uk/hanime/?res=1".toHttpUrl()

        val plain = Komica2PixmicatPostHeadParser().parseCreatedAt(
            Jsoup.parse("""<div class="threadpost"><span class="title">Fixture</span> [26/08/17(一)21:18 ID:9o50D4M6]</div>""")
                .selectFirst("div.threadpost")!!,
            url,
        )
        assertEquals(plain, parser.parseCreatedAt(post, url))
        assertEquals(true, (plain ?: 0) > 0)
    }
}
