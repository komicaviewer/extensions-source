package tw.kevinzhang.newshub.extension.sora.komica

import tw.kevinzhang.extension_api.model.Board

/**
 * Boards served by the Pixmicat/Sora implementation.
 *
 * The order matches the supported subset of the former shared Sora board list.
 * HTTP-only or permanently unavailable boards are deliberately omitted because
 * the Host will never grant plaintext request authority.
 */
internal object SoraBoardCatalog {
    const val SOURCE_ID = "tw.kevinzhang.komica.sora"

    val boards: List<Board> = listOf(
        // 連線板
        board("綜合", "https://gita.komica1.org/00b/pixmicat.php"),
        board("新番捏他", "https://gaia.komica1.org/79/pixmicat.php"),

        // 本地版
        board("四格", "https://gaia.komica1.org/42/pixmicat.php"),
        board("女性角色", "https://gaia.komica1.org/19/pixmicat.php"),
        board("男性角色", "https://gaia.komica1.org/38/pixmicat.php"),

        // 連線板
        board("新番實況", "https://gaia.komica1.org/78/pixmicat.php"),
        board("歡樂惡搞", "https://iris.komica1.org/12/pixmicat.php"),

        // 本地版
        board("GIF", "https://iris.komica1.org/23/pixmicat.php"),

        // 連線板
        board("政治", "https://iris.komica1.org/67/pixmicat.php"),
        board("模型", "https://gaia.komica1.org/09/pixmicat.php"),
        board("蘿蔔", "https://gaia.komica1.org/15/pixmicat.php"),

        // 連線二板
        board("鋼普拉", "https://iris.komica1.org/61/pixmicat.php"),

        // 連線板
        board("軍武", "https://gaia.komica1.org/17/pixmicat.php"),
        board("特攝", "https://gaia.komica1.org/13/pixmicat.php"),

        // 影音
        board("Vtuber", "https://gaia.komica1.org/74/pixmicat.php"),

        // 本地版
        board("奇幻/科幻", "https://gaia.komica1.org/60/pixmicat.php"),

        // 連線板
        board("掛圖", "https://iris.komica1.org/64/pixmicat.php"),

        // 本地版
        board("小說", "https://iris.komica1.org/35/pixmicat.php"),

        // 專題板
        board("人外", "https://komica.dbfoxtw.me/jingai/pixmicat.php"),

        // 連線板
        board("螢幕攝", "https://pixmicat.alica.idv.tw/screenshot/index.php/pixmicat.php"),

        // 連線二板
        board("旅遊", "https://travel.voidfactory.com/pixmicat.php"),
        board("故事接龍", "https://storysol.boguspix.com/pixmicat.php"),

        // 遊戲
        board("獨立遊戲", "https://komica.dbfoxtw.me/indiegame/pixmicat.php"),
        board("遊戲設計", "https://komica.dbfoxtw.me/gameprogramming/pixmicat.php"),

        // 遊戲作品
        board("GTA", "https://fenrisulfr.org/gta/pixmicat.php"),
        board("World of Tanks", "https://fenrisulfr.org/wot/pixmicat.php"),
        board("戰地風雲", "https://fenrisulfr.org/battlefield/pixmicat.php"),
        board("戰爭雷霆", "https://fenrisulfr.org/war_thunder/pixmicat.php"),
        board("戰機世界", "https://fenrisulfr.org/wowp/pixmicat.php"),
        board("戰艦世界", "https://fenrisulfr.org/wows/pixmicat.php"),

        // 動漫作品
        board("Homestuck", "https://komica.dbfoxtw.me/homestuck/pixmicat.php"),

        // 製作公司
        board("KOEI", "https://www.karlsland.net/koei/pixmicat.php"),

        // 專題板
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
