package tw.kevinzhang.newshub.extension.zawarudo.komica2

import org.junit.Assert.assertEquals
import org.junit.Test

class ZawarudoBoardsTest {
    @Test
    fun `catalog contains requested boards in order`() {
        assertEquals(
            listOf("詢問裡", "二次元獵奇", "遊戲裡避難版"),
            ZawarudoBoards.all.map { it.name },
        )
        assertEquals(
            List(3) { ZawarudoBoards.SOURCE_ID },
            ZawarudoBoards.all.map { it.sourceId },
        )
    }
}
