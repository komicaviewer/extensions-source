package tw.kevinzhang.newshub.extension.komica2.request

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.newshub.extension.komica2.model.Komica2Boards
import tw.kevinzhang.newshub.extension.site2cat.request.Site2catRequestBuilder

class Komica2RequestBuildersTest {
    @Test
    fun `summary builder preserves Sora pagination and Komica2 referer`() {
        val request = Komica2ThreadSummariesRequestBuilder()
            .setBoard(Komica2Boards.all.last())
            .setPage(2)
            .build()

        assertEquals("https://2cat.org/hiso?page_num=2", request.url.toString())
        assertEquals("https://komica2.cc/mainmenu2022.html", request.header("Referer"))
    }

    @Test
    fun `thread builder preserves Sora pixmicat endpoint and res query`() {
        val request = Komica2ThreadRequestBuilder()
            .setBoard(Komica2Boards.all.last())
            .setRes("42")
            .build()

        assertEquals("https://2cat.org/hiso/pixmicat.php?res=42", request.url.toString())
        assertEquals("https://komica2.cc/mainmenu2022.html", request.header("Referer"))
    }

    @Test
    fun `site2cat builder uses its explicitly supplied board URL for pagination`() {
        val board = Board("test", "https://2cat.org/~gifura/pixmicat.php", "GIF裏")
        val request = Site2catRequestBuilder()
            .setBoard(board)
            .setUrl("https://2cat.org/~gifura/pixmicat.php/res/123".toHttpUrl())
            .setPage(2)
            .build()

        assertEquals("https://2cat.org/~gifura/pixmicat.php/res/123?page=2", request.url.toString())
    }
}
