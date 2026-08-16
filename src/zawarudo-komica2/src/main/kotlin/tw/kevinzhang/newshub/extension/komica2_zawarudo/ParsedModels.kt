package tw.kevinzhang.newshub.extension.zawarudo.komica2

import tw.kevinzhang.extension_api.model.Paragraph

/** Site-local HTML metadata; rendered content uses extension-api Paragraph directly. */
internal data class ZawarudoParsedPost(
    val id: String,
    val url: String,
    val title: String,
    val createdAt: Long,
    val poster: String,
    val replies: Int,
    val content: List<Paragraph>,
)
