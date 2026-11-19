package tw.kevinzhang.newshub.extension.twocat.komica2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards
import tw.kevinzhang.newshub.extension.twocat.komica2.model.TwocatGatedBoard

class Komica2TwocatBoardsTest {
    private val sourceId = "tw.kevinzhang.komica2.twocat"

    @Test
    fun `catalog lists touhoux, hiso and five session-gated boards`() {
        assertEquals(
            listOf(
                Triple("東方裡", "https://2cat.org/touhoux/pixmicat.php", sourceId),
                Triple("動畫裡", "https://2cat.org/~hanime/", sourceId),
                Triple("成人玩具", "https://2cat.org/~toy/", sourceId),
                Triple("偽娘裡", "https://2cat.org/~futa/", sourceId),
                Triple("知識裡", "https://2cat.org/~know/", sourceId),
                Triple("高解析裡", "https://2cat.org/hiso/pixmicat.php", sourceId),
                Triple("GIF裡", "https://2cat.org/~gifura/", sourceId),
            ),
            Komica2TwocatBoards.all.map { Triple(it.name, it.url, it.sourceId) },
        )
    }

    @Test
    fun `gated lookup maps entry urls to slug and expected title`() {
        assertEquals(TwocatGatedBoard("hanime", "動畫裏"), Komica2TwocatBoards.gated("https://2cat.org/~hanime/"))
        assertEquals(TwocatGatedBoard("toy", "玩具裏"), Komica2TwocatBoards.gated("https://2cat.org/~toy/"))
        assertEquals(TwocatGatedBoard("futa", "偽娘裏"), Komica2TwocatBoards.gated("https://2cat.org/~futa/"))
        assertEquals(TwocatGatedBoard("know", "裏知識"), Komica2TwocatBoards.gated("https://2cat.org/~know/"))
        assertEquals(TwocatGatedBoard("gifura", "短片裏"), Komica2TwocatBoards.gated("https://2cat.org/~gifura/"))
        assertNull(Komica2TwocatBoards.gated("https://2cat.org/touhoux/pixmicat.php"))
        assertNull(Komica2TwocatBoards.gated("https://2cat.org/hiso/pixmicat.php"))
    }
}
