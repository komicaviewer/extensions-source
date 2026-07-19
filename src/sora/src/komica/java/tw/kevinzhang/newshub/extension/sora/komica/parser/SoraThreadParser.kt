package tw.kevinzhang.newshub.extension.sora.komica.parser

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadRequestParser
import tw.kevinzhang.newshub.extension.sora.komica.toResponseBody

internal class SoraThreadParser(
    private val postParser: Parser<ParsedPost>,
    private val threadReqParser: SoraThreadRequestParser,
    private val threadReqBuilder: SoraThreadRequestBuilder,
): Parser<List<ParsedPost>> {
    override fun parse(res: ResponseBody, req: Request): List<ParsedPost> {
        val source = Jsoup.parse(res.string())
        val url = req.url
        return listOf(parseHeadPost(source, url)).plus(parseReplies(source, url))
    }

    private fun parseHeadPost(source: Element, url: HttpUrl): ParsedPost {
        val req = threadReqBuilder.setUrl(url).build()
        return postParser.parse(source.selectFirst("div.threadpost").toResponseBody(), req)
    }

    private fun parseReplies(source: Element, url: HttpUrl): List<ParsedPost> {
        val threads = source.selectFirst("#threads").installThreadTag().select("div.thread")
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
                            ?.content
                            ?.filterIsInstance<Paragraph.Text>()
                            ?.firstOrNull { it.content.isNotBlank() }
                            ?.content
                            ?.trim()
                            ?.take(10)
                            ?.let { "$it..." }
                        paragraph.copy(preview = preview)
                    } else paragraph
                },
            )
        }
    }
}
