package tw.kevinzhang.newshub.extension.komica2.model

import tw.kevinzhang.extension_api.model.Board

/** Boards whose Sora-compatible pages are supported by this extension. */
internal object Komica2Boards {
    const val SOURCE_ID = "tw.kevinzhang.komica2"

    val all: List<Board> = listOf(
        // Komica2（Sora/Pixmicat 格式）
        Board(SOURCE_ID, "https://2cat.uk/~chatura/pixmicat.php", "二次裡A避難版"),
        Board(SOURCE_ID, "https://2cat.uk/~realura/pixmicat.php", "三次裡避難版"),
        Board(SOURCE_ID, "https://2cat.org/hiso/pixmicat.php", "高解析裡"),
    )
}
