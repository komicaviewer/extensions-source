package tw.kevinzhang.newshub.extension.komica2_sora

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.newshub.extension.komica2_sora.model.Komica2SoraBoards

class Komica2SoraBoardsTest {
    @Test
    fun `catalog preserves the previously supported boards and order`() {
        assertEquals(
            listOf(
                Triple("二次裡A避難版", "https://2cat.uk/~chatura/pixmicat.php", "tw.kevinzhang.komica2_sora"),
                Triple("三次裡避難版", "https://2cat.uk/~realura/pixmicat.php", "tw.kevinzhang.komica2_sora"),
                Triple("高解析裡", "https://2cat.org/hiso/pixmicat.php", "tw.kevinzhang.komica2_sora"),
            ),
            Komica2SoraBoards.all.map { Triple(it.name, it.url, it.sourceId) },
        )
    }
}
