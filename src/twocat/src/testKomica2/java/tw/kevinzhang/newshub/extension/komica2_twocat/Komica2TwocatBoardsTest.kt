package tw.kevinzhang.newshub.extension.twocat.komica2

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards

class Komica2TwocatBoardsTest {
    @Test
    fun `catalog contains only the available touhoux board`() {
        assertEquals(
            listOf(
                Triple("東方裡", "https://2cat.org/touhoux/pixmicat.php", "tw.kevinzhang.komica2.twocat"),
            ),
            Komica2TwocatBoards.all.map { Triple(it.name, it.url, it.sourceId) },
        )
    }
}
