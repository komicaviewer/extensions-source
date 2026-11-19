package tw.kevinzhang.newshub.extension.twocat.komica2.model

import tw.kevinzhang.extension_api.model.Board

/** A board reachable only through a 2cat.uk session bound to [slug]; [expectedTitle] proves the binding. */
internal data class TwocatGatedBoard(val slug: String, val expectedTitle: String)

internal object Komica2TwocatBoards {
    const val SOURCE_ID = "tw.kevinzhang.komica2.twocat"

    private val gatedByUrl = linkedMapOf(
        "https://2cat.org/~hanime/" to TwocatGatedBoard("hanime", "動畫裏"),
        "https://2cat.org/~toy/" to TwocatGatedBoard("toy", "玩具裏"),
        "https://2cat.org/~futa/" to TwocatGatedBoard("futa", "偽娘裏"),
        "https://2cat.org/~know/" to TwocatGatedBoard("know", "裏知識"),
        "https://2cat.org/~gifura/" to TwocatGatedBoard("gifura", "短片裏"),
    )

    val all: List<Board> = listOf(
        Board(SOURCE_ID, "https://2cat.org/touhoux/pixmicat.php", "東方裡"),
        Board(SOURCE_ID, "https://2cat.org/~hanime/", "動畫裡"),
        Board(SOURCE_ID, "https://2cat.org/~toy/", "成人玩具"),
        Board(SOURCE_ID, "https://2cat.org/~futa/", "偽娘裡"),
        Board(SOURCE_ID, "https://2cat.org/~know/", "知識裡"),
        Board(SOURCE_ID, "https://2cat.org/hiso/pixmicat.php", "高解析裡"),
        Board(SOURCE_ID, "https://2cat.org/~gifura/", "GIF裡"),
    )

    fun gated(boardUrl: String): TwocatGatedBoard? = gatedByUrl[boardUrl]
}
