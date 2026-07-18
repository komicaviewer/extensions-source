package tw.kevinzhang.newshub.extension.komica2

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.newshub.extension.komica2.model.Komica2Boards

class Komica2BoardsTest {
    @Test
    fun `catalog preserves the previously supported boards and order`() {
        assertEquals(
            listOf(
                Triple("二次裡A避難版", "https://2cat.uk/~chatura/pixmicat.php", "tw.kevinzhang.komica2"),
                Triple("三次裡避難版", "https://2cat.uk/~realura/pixmicat.php", "tw.kevinzhang.komica2"),
                Triple("高解析裡", "https://2cat.org/hiso/pixmicat.php", "tw.kevinzhang.komica2"),
            ),
            Komica2Boards.all.map { Triple(it.name, it.url, it.sourceId) },
        )
    }
}
