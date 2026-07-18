package tw.kevinzhang.newshub.extension.komica2_sora.request

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.komica_api.pixmicat.request.Komica2PixmicatThreadRequestBuilder
import tw.kevinzhang.komica_api.pixmicat.request.Komica2PixmicatThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.komica2_sora.model.Komica2SoraBoards

class Komica2RequestBuildersTest {
    @Test
    fun `high resolution board maps app pages to its pixmicat endpoint`() {
        val firstPageRequest = Komica2PixmicatThreadSummariesRequestBuilder()
            .setBoard(Komica2SoraBoards.all.last())
            .setPage(1)
            .build()
        val request = Komica2PixmicatThreadSummariesRequestBuilder()
            .setBoard(Komica2SoraBoards.all.last())
            .setPage(2)
            .build()
        val thirdPageRequest = Komica2PixmicatThreadSummariesRequestBuilder()
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
        val request = Komica2PixmicatThreadSummariesRequestBuilder()
            .setBoard(Komica2SoraBoards.all.first())
            .setPage(2)
            .build()

        assertEquals("https://2cat.uk/~chatura/pixmicat.php?page_num=2", request.url.toString())
    }

    @Test
    fun `thread builder preserves Sora pixmicat endpoint and res query`() {
        val request = Komica2PixmicatThreadRequestBuilder()
            .setBoard(Komica2SoraBoards.all.last())
            .setRes("42")
            .build()

        assertEquals("https://2cat.org/hiso/pixmicat.php?res=42", request.url.toString())
        assertEquals("https://komica2.cc/mainmenu2022.html", request.header("Referer"))
    }

}
