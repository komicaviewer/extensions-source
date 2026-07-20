package tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser

import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.Parser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPost
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPostBuilder
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.PostHeadParser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.UrlParser

import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.request.PixmicatThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.request.PixmicatThreadSummariesRequestParser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.toResponseBody

internal class PixmicatThreadSummariesParser(
    private val postParser: Parser<ParsedPost>,
    private val summariesReqParser: PixmicatThreadSummariesRequestParser,
    private val threadReqBuilder: PixmicatThreadRequestBuilder,
): Parser<List<ParsedPost>> {
    override fun parse(res: ResponseBody, req: Request): List<ParsedPost> {
        val source = Jsoup.parse(res.string())
        val summariesUrl = summariesReqParser.req(req).baseUrl()
        val threadsRoot = requireNotNull(source.selectFirst("#threads")) { "Missing #threads" }
        val threads = threadsRoot.installThreadTag().select("div.thread")
        return threads.map { thread ->
            val threadpost = requireNotNull(thread.selectFirst("div.threadpost")) { "Missing div.threadpost" }
            val postId = threadpost.attr("id").substring(1)
            val post = postParser.parse(
                threadpost.toResponseBody(),
                threadReqBuilder.setUrl(summariesUrl).setRes(postId).build(),
            )
            post.copy(replies = parseReplyCount(thread))
        }
    }

    companion object {
        fun parseReplyCount(thread: Element): Int {
            var replyCount = thread.selectFirst("span.warn_txt2")
                ?.text()
                ?.replace("\\D".toRegex(), "")
                ?.toIntOrNull()
                ?: 0
            replyCount += thread.getElementsByClass("reply").size
            return replyCount
        }
    }
}
