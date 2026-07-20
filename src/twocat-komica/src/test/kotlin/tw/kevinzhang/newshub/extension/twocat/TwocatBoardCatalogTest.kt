package tw.kevinzhang.newshub.extension.twocat.komica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwocatBoardCatalogTest {

    @Test
    fun `catalog keeps the previous twocat board identity and order`() {
        assertEquals(
            listOf(
                "碧藍幻想" to "https://2cat.org/granblue",
                "手機遊戲" to "https://2cat.org/handheld",
                "網頁遊戲" to "https://2cat.org/webgame2",
                "職業相談" to "https://2cat.org/career",
                "理財" to "https://cat.2nyan.org/finance",
                "法律" to "https://cat.2nyan.org/law",
                "繪師版" to "https://2cat.org/artist",
                "猜謎" to "https://cat.2nyan.org/quiz",
                "天文" to "https://2cat.org/astronomy",
                "超常現象" to "https://cat.2nyan.org/supernature",
                "服飾穿搭" to "https://2cat.org/dressing",
                "手機/平板遊戲" to "https://2cat.org/cellphone",
                "體感遊戲" to "https://2cat.org/motion",
                "女性向遊戲" to "https://2cat.org/boylove",
                "桌上遊戲" to "https://2cat.org/boardgame",
                "Azur Lane" to "https://2cat.org/azurlane",
                "東方" to "https://2cat.org/~touhou",
                "龍騎士07" to "https://2cat.org/07expansion",
                "涼宮" to "https://eclair.nagatoyuki.org/szmy",
                "咖啡-茶飲" to "https://2cat.org/coffee",
                "烹飪版" to "https://2cat.org/cooking",
                "動物寵物" to "https://2cat.org/animal",
                "鳥" to "https://2cat.org/bird",
                "二次壁" to "https://2cat.org/wallpaper",
                "程設交流" to "https://www.gomiga.org/cs",
                "人工智慧" to "https://www.gomiga.org/ai",
            ),
            TwocatBoardCatalog.boards.map { it.name to it.url },
        )
        assertTrue(TwocatBoardCatalog.boards.all { it.sourceId == TwocatBoardCatalog.SOURCE_ID })
    }
}
