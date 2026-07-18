package tw.kevinzhang.newshub.extension.komica2_twocat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.newshub.extension.komica2_twocat.model.Komica2TwocatBoards

class Komica2TwocatBoardsTest {
    @Test
    fun `catalog preserves requested order and has one animation board`() {
        assertEquals(
            listOf(
                Triple("GIF裡", "https://2cat.org/~gifura/pixmicat.php", "tw.kevinzhang.komica2_twocat"),
                Triple("動畫裡", "https://2cat.org/~hanime/pixmicat.php", "tw.kevinzhang.komica2_twocat"),
                Triple("成人玩具", "https://2cat.org/~toy/pixmicat.php", "tw.kevinzhang.komica2_twocat"),
                Triple("知識裡", "https://2cat.org/~know/pixmicat.php", "tw.kevinzhang.komica2_twocat"),
                Triple("偽娘裡", "https://2cat.org/~futa/pixmicat.php", "tw.kevinzhang.komica2_twocat"),
                Triple("東方裡", "https://2cat.org/touhoux/pixmicat.php", "tw.kevinzhang.komica2_twocat"),
            ),
            Komica2TwocatBoards.all.map { Triple(it.name, it.url, it.sourceId) },
        )
        assertEquals(1, Komica2TwocatBoards.all.count { it.name == "動畫裡" })
        assertTrue(Komica2TwocatBoards.all.map { it.url }.distinct().size == Komica2TwocatBoards.all.size)
        assertTrue(Komica2TwocatBoards.all.all { it.url.endsWith("/pixmicat.php") })
    }
}
