package tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser

import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.Parser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.ParsedPost
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.ParsedPostBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.PostHeadParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.UrlParser

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.PixmicatThreadRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.PixmicatThreadRequestParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.toResponseBody

internal class PixmicatThreadParser(
    private val postParser: Parser<ParsedPost>,
    private val threadReqParser: PixmicatThreadRequestParser,
    private val threadReqBuilder: PixmicatThreadRequestBuilder,
): Parser<List<ParsedPost>> {
    override fun parse(res: ResponseBody, req: Request): List<ParsedPost> {
        val source = Jsoup.parse(res.string())
        val url = req.url
        return listOf(parseHeadPost(source, url)).plus(parseReplies(source, url))
    }

    private fun parseHeadPost(source: Element, url: HttpUrl): ParsedPost {
        val req = threadReqBuilder.setUrl(url).build()
        val headPost = requireNotNull(source.selectFirst("div.threadpost")) { "Missing div.threadpost" }
        return postParser.parse(headPost.toResponseBody(), req)
    }

    private fun parseReplies(source: Element, url: HttpUrl): List<ParsedPost> {
        val threadsRoot = requireNotNull(source.selectFirst("#threads")) { "Missing #threads" }
        val threads = threadsRoot.installThreadTag().select("div.thread")
        val posts = threads.select("div.reply").map { reply_ele ->
            val fragment = reply_ele.attr("id") // r12345678
            val postId = fragment.substring(1)
            val req = threadReqBuilder.setUrl(url).setFragment("r$postId").build()
            postParser.parse(reply_ele.toResponseBody(), req)
        }
        return withReplyMetadata(posts)
    }

    private fun withReplyMetadata(posts: List<ParsedPost>): List<ParsedPost> {
        val byId = posts.associateBy { it.id }
        return posts.map { post ->
            post.copy(
                replies = posts.replyCountFor(post.id),
                content = post.content.map { paragraph ->
                    if (paragraph is Paragraph.ReplyTo) {
                        val preview = byId[paragraph.targetId]
                            ?.content?.filterIsInstance<Paragraph.Text>()
                            ?.firstOrNull { it.content.isNotBlank() }
                            ?.content?.trim()?.take(10)?.let { "$it..." }
                        paragraph.copy(preview = preview)
                    } else paragraph
                },
            )
        }
    }
}
