package tw.kevinzhang.newshub.extension.site2cat

import tw.kevinzhang.extension_api.model.Board

/** Boards served by the Site2cat parser, in the existing Komica catalog order. */
internal object Site2catBoardCatalog {
    const val SOURCE_ID = "tw.kevinzhang.site2cat"

    val boards: List<Board> = listOf(
        Board(SOURCE_ID, "https://2cat.org/granblue", "碧藍幻想"),
        Board(SOURCE_ID, "https://2cat.org/handheld", "手機遊戲"),
        Board(SOURCE_ID, "https://2cat.org/webgame2", "網頁遊戲"),
        Board(SOURCE_ID, "https://2cat.org/career", "職業相談"),
        Board(SOURCE_ID, "https://cat.2nyan.org/finance", "理財"),
        Board(SOURCE_ID, "https://cat.2nyan.org/law", "法律"),
        Board(SOURCE_ID, "https://2cat.org/artist", "繪師版"),
        Board(SOURCE_ID, "https://cat.2nyan.org/quiz", "猜謎"),
        Board(SOURCE_ID, "https://2cat.org/astronomy", "天文"),
        Board(SOURCE_ID, "https://cat.2nyan.org/supernature", "超常現象"),
        Board(SOURCE_ID, "https://2cat.org/dressing", "服飾穿搭"),
        Board(SOURCE_ID, "https://2cat.org/cellphone", "手機/平板遊戲"),
        Board(SOURCE_ID, "https://2cat.org/motion", "體感遊戲"),
        Board(SOURCE_ID, "https://2cat.org/boylove", "女性向遊戲"),
        Board(SOURCE_ID, "https://2cat.org/boardgame", "桌上遊戲"),
        Board(SOURCE_ID, "https://2cat.org/azurlane", "Azur Lane"),
        Board(SOURCE_ID, "https://2cat.org/~touhou", "東方"),
        Board(SOURCE_ID, "https://2cat.org/07expansion", "龍騎士07"),
        Board(SOURCE_ID, "https://eclair.nagatoyuki.org/szmy", "涼宮"),
        Board(SOURCE_ID, "https://2cat.org/coffee", "咖啡-茶飲"),
        Board(SOURCE_ID, "https://2cat.org/cooking", "烹飪版"),
        Board(SOURCE_ID, "https://2cat.org/animal", "動物寵物"),
        Board(SOURCE_ID, "https://2cat.org/bird", "鳥"),
        Board(SOURCE_ID, "https://2cat.org/wallpaper", "二次壁"),
        Board(SOURCE_ID, "https://www.gomiga.org/cs", "程設交流"),
        Board(SOURCE_ID, "https://www.gomiga.org/ai", "人工智慧"),
    )

    fun findByUrl(url: String): Board =
        boards.first { it.url == url }
}
