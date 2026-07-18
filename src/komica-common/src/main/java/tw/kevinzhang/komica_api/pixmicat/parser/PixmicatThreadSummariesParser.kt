package tw.kevinzhang.komica_api.pixmicat.parser

import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.pixmicat.request.PixmicatThreadRequestBuilder
import tw.kevinzhang.komica_api.pixmicat.request.PixmicatThreadSummariesRequestParser
import tw.kevinzhang.komica_api.pixmicat.toResponseBody

class PixmicatThreadSummariesParser(
    private val postParser: Parser<KPost>,
    private val summariesReqParser: PixmicatThreadSummariesRequestParser,
    private val threadReqBuilder: PixmicatThreadRequestBuilder,
): Parser<List<KPost>> {
    override fun parse(res: ResponseBody, req: Request): List<KPost> {
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
