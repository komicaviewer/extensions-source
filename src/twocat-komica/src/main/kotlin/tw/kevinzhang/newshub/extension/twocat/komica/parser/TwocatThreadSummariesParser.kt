package tw.kevinzhang.newshub.extension.twocat.komica.parser

import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tw.kevinzhang.newshub.extension.twocat.komica.request.TwocatRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica.toResponseBody

internal class TwocatThreadSummariesParser(
    private val postParser: Parser<ParsedPost>,
    private val threadRequestBuilder: TwocatRequestBuilder,
): Parser<List<ParsedPost>> {
    override fun parse(res: ResponseBody, req: Request): List<ParsedPost> {
        val source = Jsoup.parse(res.string())
        val url = req.url
        val threads = source.select("div.threadpost")
        return threads.map { thread ->
            val threadpost = thread.selectFirst("div.threadpost")
            val postId = threadpost.attr("id").substring(1)
            val post = postParser.parse(
                threadpost.toResponseBody(),
                threadRequestBuilder.setUrl(url).setRes(postId).build(),
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
