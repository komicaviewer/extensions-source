package tw.kevinzhang.newshub.extension.wtako

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WtakoBoardCatalogTest {
    @Test
    fun `catalog contains the three requested boards`() {
        assertEquals(
            listOf("祭典(双猫村祭典広場)", "Strike-Witches", "獸"),
            WtakoBoardCatalog.boards.map { it.name },
        )
        assertTrue(WtakoBoardCatalog.boards.all { it.sourceId == WtakoBoardCatalog.SOURCE_ID })
    }
}
