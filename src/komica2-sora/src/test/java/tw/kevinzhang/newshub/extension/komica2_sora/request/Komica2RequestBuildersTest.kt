package tw.kevinzhang.newshub.extension.komica2_sora.request

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.newshub.extension.komica2_sora.model.Komica2SoraBoards
import tw.kevinzhang.newshub.extension.twocat.request.TwocatRequestBuilder

class Komica2RequestBuildersTest {
    @Test
    fun `high resolution board maps app pages to its pixmicat endpoint`() {
        val firstPageRequest = Komica2SoraThreadSummariesRequestBuilder()
            .setBoard(Komica2SoraBoards.all.last())
            .setPage(1)
            .build()
        val request = Komica2SoraThreadSummariesRequestBuilder()
            .setBoard(Komica2SoraBoards.all.last())
            .setPage(2)
            .build()
        val thirdPageRequest = Komica2SoraThreadSummariesRequestBuilder()
            .setBoard(Komica2SoraBoards.all.last())
            .setPage(3)
            .build()

        assertEquals("https://2cat.org/hiso/pixmicat.php", firstPageRequest.url.toString())
        assertEquals("https://2cat.org/hiso/pixmicat.php?page_num=1", request.url.toString())
        assertEquals("https://2cat.org/hiso/pixmicat.php?page_num=2", thirdPageRequest.url.toString())
        assertEquals("https://komica2.cc/mainmenu2022.html", request.header("Referer"))
    }

    @Test
    fun `non 2cat org board preserves Sora pagination`() {
        val request = Komica2SoraThreadSummariesRequestBuilder()
            .setBoard(Komica2SoraBoards.all.first())
            .setPage(2)
            .build()

        assertEquals("https://2cat.uk/~chatura/pixmicat.php?page_num=2", request.url.toString())
    }

    @Test
    fun `thread builder preserves Sora pixmicat endpoint and res query`() {
        val request = Komica2SoraThreadRequestBuilder()
            .setBoard(Komica2SoraBoards.all.last())
            .setRes("42")
            .build()

        assertEquals("https://2cat.org/hiso/pixmicat.php?res=42", request.url.toString())
        assertEquals("https://komica2.cc/mainmenu2022.html", request.header("Referer"))
    }

    @Test
    fun `twocat builder uses its explicitly supplied board URL for pagination`() {
        val board = Board("test", "https://2cat.org/~gifura/pixmicat.php", "GIF裏")
        val request = TwocatRequestBuilder()
            .setBoard(board)
            .setUrl("https://2cat.org/~gifura/pixmicat.php/res/123".toHttpUrl())
            .setPage(2)
            .build()

        assertEquals("https://2cat.org/~gifura/pixmicat.php/res/123?page=2", request.url.toString())
    }
}
