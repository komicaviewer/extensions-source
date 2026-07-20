package tw.kevinzhang.newshub.extension.sora.komica.parser

import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadSummariesRequestParser
import tw.kevinzhang.newshub.extension.sora.komica.toResponseBody

internal class SoraThreadSummariesParser(
    private val postParser: Parser<ParsedPost>,
    private val summariesReqParser: SoraThreadSummariesRequestParser,
    private val threadReqBuilder: SoraThreadRequestBuilder,
): Parser<List<ParsedPost>> {
    override fun parse(res: ResponseBody, req: Request): List<ParsedPost> {
        val source = Jsoup.parse(res.string())
        val summariesUrl = summariesReqParser.req(req).baseUrl()
        val threads = source.selectFirst("#threads").installThreadTag().select("div.thread")
        return threads.map { thread ->
            val threadpost = thread.selectFirst("div.threadpost")
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
            var replyCount = 0
            try {
                replyCount = thread.selectFirst("span.warn_txt2").text()
                    .replace("\\D".toRegex(), "")
                    .toInt()
            } catch (ignored: NullPointerException) { }
            replyCount += thread.getElementsByClass("reply").size
            return replyCount
        }
    }
}
