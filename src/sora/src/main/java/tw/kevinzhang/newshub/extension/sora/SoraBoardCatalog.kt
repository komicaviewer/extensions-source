package tw.kevinzhang.newshub.extension.sora

import tw.kevinzhang.extension_api.model.Board

/**
 * Boards served by the Pixmicat/Sora implementation.
 *
 * The order intentionally matches the former shared Sora board list so
 * existing users see the same board order after the catalog moved out of
 * komica-common.
 */
internal object SoraBoardCatalog {
    const val SOURCE_ID = "tw.kevinzhang.komica-sora"

    val boards: List<Board> = listOf(
        board("綜合", "https://gita.komica1.org/00b/pixmicat.php"),
        board("新番捏他", "https://gaia.komica1.org/79/pixmicat.php"),
        board("四格", "https://gaia.komica1.org/42/pixmicat.php"),
        board("女性角色", "https://gaia.komica1.org/19/pixmicat.php"),
        board("男性角色", "https://gaia.komica1.org/38/pixmicat.php"),
        board("新番實況", "https://gaia.komica1.org/78/pixmicat.php"),
        board("歡樂惡搞", "https://iris.komica1.org/12/pixmicat.php"),
        board("GIF", "https://iris.komica1.org/23/pixmicat.php"),
        board("政治", "https://iris.komica1.org/67/pixmicat.php"),
        board("模型", "https://gaia.komica1.org/09/pixmicat.php"),
        board("蘿蔔", "https://gaia.komica1.org/15/pixmicat.php"),
        board("鋼普拉", "https://iris.komica1.org/61/pixmicat.php"),
        board("軍武", "https://gaia.komica1.org/17/pixmicat.php"),
        board("特攝", "https://gaia.komica1.org/13/pixmicat.php"),
        board("TYPE-MOON", "http://gzone-anime.info/UnitedSites/TypeMoon/pixmicat.php"),
        board("Vtuber", "https://gaia.komica1.org/74/pixmicat.php"),
        board("奇幻/科幻", "https://gaia.komica1.org/60/pixmicat.php"),
        board("掛圖", "https://iris.komica1.org/64/pixmicat.php"),
        board("小說", "https://iris.komica1.org/35/pixmicat.php"),
        board("人外", "https://komica.dbfoxtw.me/jingai/pixmicat.php"),
        board("艦隊收藏", "http://acgspace.wsfun.com/kancolle/pixmicat.php"),
        board("螢幕攝", "https://pixmicat.alica.idv.tw/screenshot/index.php/pixmicat.php"),
        board("生活消費", "http://gzone-anime.info/UnitedSites/shopping/pixmicat.php"),
        board("藝術", "http://gzone-anime.info/UnitedSites/art/pixmicat.php"),
        board("旅遊", "https://travel.voidfactory.com/pixmicat.php"),
        board("圖書", "http://gzone-anime.info/UnitedSites/books/pixmicat.php"),
        board("故事接龍", "https://storysol.boguspix.com/pixmicat.php"),
        board("獨立遊戲", "https://komica.dbfoxtw.me/indiegame/pixmicat.php"),
        board("遊戲設計", "https://komica.dbfoxtw.me/gameprogramming/pixmicat.php"),
        board("GTA", "https://fenrisulfr.org/gta/pixmicat.php"),
        board("World of Tanks", "https://fenrisulfr.org/wot/pixmicat.php"),
        board("戰地風雲", "https://fenrisulfr.org/battlefield/pixmicat.php"),
        board("戰爭雷霆", "https://fenrisulfr.org/war_thunder/pixmicat.php"),
        board("戰機世界", "https://fenrisulfr.org/wowp/pixmicat.php"),
        board("戰艦世界", "https://fenrisulfr.org/wows/pixmicat.php"),
        board("Homestuck", "https://komica.dbfoxtw.me/homestuck/pixmicat.php"),
        board("KOEI", "https://www.karlsland.net/koei/pixmicat.php"),
        board("御姊", "https://sister.boguspix.com/pixmicat.php"),
        board("機娘", "https://msgirls.boguspix.com/pixmicat.php"),
        board("巫女", "https://pixmicat.alica.idv.tw/miko/pixmicat.php"),
    )

    private fun board(name: String, url: String) = Board(
        sourceId = SOURCE_ID,
        url = url,
        name = name,
    )

    fun findByUrl(url: String): Board = boards.first { it.url == url }
}
