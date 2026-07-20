package tw.kevinzhang.newshub.extension.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.BoardQuery

class PttBoardCatalogTest {
    private val popular = PttBoardCatalog.parsePopular(fixture("hotboards.html"))

    @Test fun `empty query returns live popular boards in server order`() {
        val page = PttBoardCatalog.page(BoardPageRequest(pageSize = 1), popular)

        assertEquals(listOf("Gossiping"), page.boards.map { it.name })
        assertEquals("1", page.nextPageToken)
        assertTrue(page.boards.first().description.orEmpty().contains("八卦"))
    }

    @Test fun `query filters popular metadata and accepts only a validated exact board`() {
        val filtered = PttBoardCatalog.page(BoardPageRequest(BoardQuery("股票")), popular)
        assertEquals(listOf("Stock"), filtered.boards.map { it.name })

        val exact = PttBoardCatalog.exactBoard("MyPrivateBoard")
        val page = PttBoardCatalog.page(BoardPageRequest(BoardQuery("MyPrivateBoard")), popular, exact)
        assertEquals("MyPrivateBoard", page.boards.single().name)
        assertEquals("https://www.ptt.cc/bbs/MyPrivateBoard/index.html", page.boards.single().url)
    }

    @Test fun `board page validation rejects an unrelated or missing page`() {
        assertTrue(PttBoardCatalog.isBoardPage(fixture("board.html"), "Stock"))
        assertTrue(!PttBoardCatalog.isBoardPage("<title>404</title>", "Stock"))
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/ptt/$name")).readText()
}
