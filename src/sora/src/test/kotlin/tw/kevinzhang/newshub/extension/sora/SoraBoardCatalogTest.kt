package tw.kevinzhang.newshub.extension.sora

import org.junit.Assert.assertEquals
import org.junit.Test

class SoraBoardCatalogTest {

    @Test
    fun `catalog preserves the former Sora board identity and order`() {
        assertEquals(
            listOf(
                "綜合" to "https://gita.komica1.org/00b/pixmicat.php",
                "新番捏他" to "https://gaia.komica1.org/79/pixmicat.php",
                "四格" to "https://gaia.komica1.org/42/pixmicat.php",
                "女性角色" to "https://gaia.komica1.org/19/pixmicat.php",
                "男性角色" to "https://gaia.komica1.org/38/pixmicat.php",
                "新番實況" to "https://gaia.komica1.org/78/pixmicat.php",
                "歡樂惡搞" to "https://iris.komica1.org/12/pixmicat.php",
                "GIF" to "https://iris.komica1.org/23/pixmicat.php",
                "政治" to "https://iris.komica1.org/67/pixmicat.php",
                "模型" to "https://gaia.komica1.org/09/pixmicat.php",
                "蘿蔔" to "https://gaia.komica1.org/15/pixmicat.php",
                "鋼普拉" to "https://iris.komica1.org/61/pixmicat.php",
                "軍武" to "https://gaia.komica1.org/17/pixmicat.php",
                "特攝" to "https://gaia.komica1.org/13/pixmicat.php",
                "TYPE-MOON" to "http://gzone-anime.info/UnitedSites/TypeMoon/pixmicat.php",
                "Vtuber" to "https://gaia.komica1.org/74/pixmicat.php",
                "奇幻/科幻" to "https://gaia.komica1.org/60/pixmicat.php",
                "掛圖" to "https://iris.komica1.org/64/pixmicat.php",
                "小說" to "https://iris.komica1.org/35/pixmicat.php",
                "人外" to "https://komica.dbfoxtw.me/jingai/pixmicat.php",
                "艦隊收藏" to "http://acgspace.wsfun.com/kancolle/pixmicat.php",
                "螢幕攝" to "https://pixmicat.alica.idv.tw/screenshot/index.php/pixmicat.php",
                "生活消費" to "http://gzone-anime.info/UnitedSites/shopping/pixmicat.php",
                "藝術" to "http://gzone-anime.info/UnitedSites/art/pixmicat.php",
                "旅遊" to "https://travel.voidfactory.com/pixmicat.php",
                "圖書" to "http://gzone-anime.info/UnitedSites/books/pixmicat.php",
                "故事接龍" to "https://storysol.boguspix.com/pixmicat.php",
                "獨立遊戲" to "https://komica.dbfoxtw.me/indiegame/pixmicat.php",
                "遊戲設計" to "https://komica.dbfoxtw.me/gameprogramming/pixmicat.php",
                "GTA" to "https://fenrisulfr.org/gta/pixmicat.php",
                "World of Tanks" to "https://fenrisulfr.org/wot/pixmicat.php",
                "戰地風雲" to "https://fenrisulfr.org/battlefield/pixmicat.php",
                "戰爭雷霆" to "https://fenrisulfr.org/war_thunder/pixmicat.php",
                "戰機世界" to "https://fenrisulfr.org/wowp/pixmicat.php",
                "戰艦世界" to "https://fenrisulfr.org/wows/pixmicat.php",
                "Homestuck" to "https://komica.dbfoxtw.me/homestuck/pixmicat.php",
                "KOEI" to "https://www.karlsland.net/koei/pixmicat.php",
                "御姊" to "https://sister.boguspix.com/pixmicat.php",
                "機娘" to "https://msgirls.boguspix.com/pixmicat.php",
                "巫女" to "https://pixmicat.alica.idv.tw/miko/pixmicat.php",
            ),
            SoraBoardCatalog.boards.map { it.name to it.url },
        )
        assertEquals(
            setOf(SoraBoardCatalog.SOURCE_ID),
            SoraBoardCatalog.boards.map { it.sourceId }.toSet(),
        )
    }
}
