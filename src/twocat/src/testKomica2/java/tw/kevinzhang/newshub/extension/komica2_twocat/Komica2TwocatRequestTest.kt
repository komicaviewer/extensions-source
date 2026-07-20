package tw.kevinzhang.newshub.extension.twocat.komica2

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.Komica2PixmicatEngine
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards

class Komica2TwocatRequestTest {
    private val engine = Komica2PixmicatEngine()
    private val board = Komica2TwocatBoards.all.single()

    @Test
    fun `page number and referer follow Komica2 Pixmicat rules`() {
        val firstPage = engine.createThreadSummariesRequestBuilder(board).setPage(1).build()
        val secondPage = engine.createThreadSummariesRequestBuilder(board).setPage(2).build()
        val thirdPage = engine.createThreadSummariesRequestBuilder(board).setPage(3).build()

        assertEquals("https://2cat.org/touhoux/pixmicat.php", firstPage.url.toString())
        assertEquals("https://2cat.org/touhoux/pixmicat.php?page_num=1", secondPage.url.toString())
        assertEquals("https://2cat.org/touhoux/pixmicat.php?page_num=2", thirdPage.url.toString())
        assertEquals("https://komica2.cc/mainmenu2022.html", secondPage.header("Referer"))
    }

    @Test
    fun `thread request keeps pixmicat endpoint res query and referer`() {
        val request = engine.createThreadRequestBuilder(board)
            .setUrl("https://2cat.org/touhoux/pixmicat.php?res=42".toHttpUrl())
            .build()

        assertEquals("https://2cat.org/touhoux/pixmicat.php?res=42", request.url.toString())
        assertEquals("https://komica2.cc/mainmenu2022.html", request.header("Referer"))
    }

    @Test
    fun `url normalization removes pagination but preserves the thread identity`() {
        assertEquals(
            "https://2cat.org/touhoux/pixmicat.php?res=42#r100",
            engine.normalizeUrl(
                "https://2cat.org/touhoux/pixmicat.php?res=42&page_num=3&page=4#r100".toHttpUrl(),
            ),
        )
    }
}
