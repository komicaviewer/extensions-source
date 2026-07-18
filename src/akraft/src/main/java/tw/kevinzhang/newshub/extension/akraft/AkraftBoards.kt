package tw.kevinzhang.newshub.extension.akraft

import tw.kevinzhang.extension_api.model.Board

internal object AkraftBoards {
    const val SOURCE_ID = "tw.kevinzhang.akraft"

    val all = listOf(
        Board(SOURCE_ID, "https://www.akraft.net/service/66a6eca2bfccee3f04a52bc4", "影視"),
        Board(SOURCE_ID, "https://www.akraft.net/service/61bc09b0e27a80b99d12c095", "Dota2"),
    )

    fun require(url: String): Board = all.firstOrNull { it.url == url }
        ?: throw IllegalArgumentException("Unsupported Akraft board: $url")
}
