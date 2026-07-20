package tw.kevinzhang.newshub.extension.twocat.komica2.model

import tw.kevinzhang.extension_api.model.Board

internal object Komica2TwocatBoards {
    const val SOURCE_ID = "tw.kevinzhang.komica2.twocat"

    val all: List<Board> = listOf(
        Board(SOURCE_ID, "https://2cat.org/touhoux/pixmicat.php", "東方裡"),
    )
}
