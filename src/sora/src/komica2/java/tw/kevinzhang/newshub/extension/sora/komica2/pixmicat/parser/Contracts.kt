package tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser

import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.Parser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPost
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPostBuilder
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.PostHeadParser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.UrlParser

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.nodes.Element
import tw.kevinzhang.extension_api.model.Paragraph

internal fun interface Parser<T> { fun parse(res: ResponseBody, req: Request): T }
internal interface UrlParser {
    fun parseBoardId(url: HttpUrl): String?
    fun parsePostId(url: HttpUrl): String?
    fun parseHeadPostId(url: HttpUrl): String?
    fun parseRePostId(url: HttpUrl): String?
    fun parsePage(url: HttpUrl): Int?
    fun hasBoardId(url: HttpUrl): Boolean
    fun hasPostId(url: HttpUrl): Boolean
    fun hasHeadPostId(url: HttpUrl): Boolean
    fun hasRePostId(url: HttpUrl): Boolean
    fun hasPage(url: HttpUrl): Boolean
}
internal interface PostHeadParser {
    fun parseTitle(source: Element, url: HttpUrl): String?
    fun parseCreatedAt(source: Element, url: HttpUrl): Long?
    fun parsePoster(source: Element, url: HttpUrl): String?
}
internal data class ParsedPost(
    val id: String, val url: String, val title: String, val createdAt: Long,
    val poster: String, val replies: Int = 0, val content: List<Paragraph>,
)
internal class ParsedPostBuilder {
    private var id = ""; private var url = ""; private var title = ""; private var createdAt = 0L
    private var poster = ""; private var content: List<Paragraph> = emptyList()
    fun setTitle(value: String) = apply { title = value }; fun setPoster(value: String) = apply { poster = value }
    fun setCreatedAt(value: Long) = apply { createdAt = value }; fun setContent(value: List<Paragraph>) = apply { content = value }
    fun addContent(value: Paragraph) = apply { content = content + value }; fun setUrl(value: String) = apply { url = value }
    fun setPostId(value: String) = apply { id = value }
    fun build() = ParsedPost(id, url, title, createdAt, poster, content = content)
}
internal fun List<ParsedPost>.replyCountFor(postId: String) = count { post ->
    post.content.filterIsInstance<Paragraph.ReplyTo>().any { it.targetId == postId }
}
