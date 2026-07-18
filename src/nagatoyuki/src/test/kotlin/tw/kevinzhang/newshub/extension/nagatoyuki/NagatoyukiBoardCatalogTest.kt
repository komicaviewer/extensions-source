package tw.kevinzhang.newshub.extension.nagatoyuki

import org.junit.Assert.assertEquals
import org.junit.Test

class NagatoyukiBoardCatalogTest {
    @Test
    fun `catalog contains all requested boards under one source`() {
        assertEquals(
            listOf(
                "COSPLAY", "線上繪圖", "MMD/Vocaloid", "海外", "流言終結",
                "相談", "安價", "蔚藍檔案", "酒",
            ),
            NagatoyukiBoardCatalog.boards.map { it.name },
        )
        assertEquals(
            setOf(NagatoyukiBoardCatalog.SOURCE_ID),
            NagatoyukiBoardCatalog.boards.map { it.sourceId }.toSet(),
        )
    }
}
