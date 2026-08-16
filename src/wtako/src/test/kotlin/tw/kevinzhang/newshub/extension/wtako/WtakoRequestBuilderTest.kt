package tw.kevinzhang.newshub.extension.wtako

import org.junit.Assert.assertEquals
import org.junit.Test

class WtakoRequestBuilderTest {
    @Test
    fun `board endpoint and pagination preserve a path-only board URL`() {
        assertEquals(
            "https://rthost.win/sd/index.htm",
            WtakoRequestBuilder.boardPage("https://rthost.win/sd", 1).url.toString(),
        )
        assertEquals(
            "https://rthost.win/sd/pixmicat.php?page_num=2",
            WtakoRequestBuilder.boardPage("https://rthost.win/sd", 2).url.toString(),
        )
        assertEquals(
            "https://kemono.wtako.net/kemono/pixmicat.php?page_num=2",
            WtakoRequestBuilder.boardPage("https://kemono.wtako.net/kemono", 2).url.toString(),
        )
    }

    @Test
    fun `legacy rthost board URL is upgraded to HTTPS`() {
        assertEquals(
            "https://rthost.win/sd/index.htm",
            WtakoRequestBuilder.boardPage("http://rthost.win/sd", 1).url.toString(),
        )
    }

    @Test
    fun `thread endpoint uses the Pixmicat res query`() {
        assertEquals(
            "https://www.karlsland.net/sw/pixmicat.php?res=44259",
            WtakoRequestBuilder.threadUrl("https://www.karlsland.net/sw", "44259"),
        )
    }
}
