package tw.kevinzhang.komica_api.model

import tw.kevinzhang.extension_api.model.Paragraph as ExtParagraph

interface KParagraph

data class KVideoInfo(
    val url: String,
): KParagraph

data class KImageInfo(
    val thumb: String? = null,
    val raw: String,
): KParagraph

data class KText(
    val content: String,
): KParagraph

data class KQuote(
    val content: String,
): KParagraph

data class KReplyTo(
    val targetId: String,
    var preview: String? = null,
): KParagraph

data class KLink(
    val content: String,
): KParagraph

fun KParagraph.toExtParagraph(): ExtParagraph = when (this) {
    is KQuote   -> ExtParagraph.Quote(content)
    is KReplyTo -> ExtParagraph.ReplyTo(targetId = targetId, preview = preview)
    is KText    -> ExtParagraph.Text(content)
    is KImageInfo -> ExtParagraph.ImageInfo(thumb, raw)
    is KVideoInfo -> ExtParagraph.VideoInfo(url)
    is KLink    -> ExtParagraph.Link(content)
    else        -> throw IllegalArgumentException("Unknown KParagraph: $this")
}
