package tw.kevinzhang.newshub.extension.mobile01

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.BoardQuery

class Mobile01BoardCatalogTest {
    @Test
    fun `catalog supports category search and opaque pagination`() {
        val digital = Mobile01BoardCatalog.page(BoardPageRequest(BoardQuery(categoryId = "digital"), pageSize = 2))
        assertEquals(2, digital.boards.size)
        assertEquals("2", digital.nextPageToken)
        assertTrue(digital.boards.all { it.url.startsWith("https://www.mobile01.com/topiclist.php?") })

        val searched = Mobile01BoardCatalog.page(BoardPageRequest(BoardQuery(text = "機車")))
        assertTrue(searched.boards.any { it.name.contains("機車") || it.description.orEmpty().contains("機車") })
    }

    @Test
    fun `curated catalog keeps verified Mobile01 board identifiers`() {
        val all = Mobile01BoardCatalog.page(BoardPageRequest(pageSize = 100)).boards.associateBy { Mobile01UrlPolicy.boardId(it.url) }

        assertEquals("iPhone", all.getValue(383).name)
        assertEquals("MOTOROLA", all.getValue(567).name)
        assertEquals("電腦螢幕", all.getValue(350).name)
        assertEquals("作業系統", all.getValue(300).name)
        assertEquals("其他電腦綜合討論", all.getValue(514).name)
        assertEquals("自組電腦分享", all.getValue(174).name)
        assertEquals("輕型與重型機車綜合", all.getValue(266).name)
        assertEquals("人身安全部品", all.getValue(265).name)
        assertEquals("機車行車記錄器綜合", all.getValue(671).name)
        assertEquals("電腦遊戲", all.getValue(283).name)
        assertEquals("閱讀與創作", all.getValue(594).name)
        assertEquals("房地產資訊", all.getValue(356).name)
        assertEquals("台北市", all.getValue(454).name)
        assertEquals("閒聊與趣味", all.getValue(37).name)
        assertEquals("台灣新聞", all.getValue(638).name)
        assertEquals("GARMIN", all.getValue(776).name)
    }
}
