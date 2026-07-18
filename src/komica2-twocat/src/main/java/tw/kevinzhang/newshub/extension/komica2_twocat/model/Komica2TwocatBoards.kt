package tw.kevinzhang.newshub.extension.komica2_twocat.model

import tw.kevinzhang.extension_api.model.Board

internal object Komica2TwocatBoards {
    const val SOURCE_ID = "tw.kevinzhang.komica2_twocat"

    val all: List<Board> = listOf(
        Board(SOURCE_ID, "https://2cat.org/~gifura/pixmicat.php", "GIF裡"),
        Board(SOURCE_ID, "https://2cat.org/~hanime/pixmicat.php", "動畫裡"),
        Board(SOURCE_ID, "https://2cat.org/~toy/pixmicat.php", "成人玩具"),
        Board(SOURCE_ID, "https://2cat.org/~know/pixmicat.php", "知識裡"),
        Board(SOURCE_ID, "https://2cat.org/~futa/pixmicat.php", "偽娘裡"),
        Board(SOURCE_ID, "https://2cat.org/touhoux/pixmicat.php", "東方裡"),
    )
}
