package tw.kevinzhang.newshub.extension.komica2_zawarudo

import org.junit.Assert.assertEquals
import org.junit.Test

class Komica2ZawarudoBoardsTest {
    @Test
    fun `catalog contains requested boards in order`() {
        assertEquals(
            listOf("詢問裡", "二次元獵奇", "遊戲裡避難版"),
            Komica2ZawarudoBoards.all.map { it.name },
        )
        assertEquals(
            List(3) { Komica2ZawarudoBoards.SOURCE_ID },
            Komica2ZawarudoBoards.all.map { it.sourceId },
        )
    }
}
