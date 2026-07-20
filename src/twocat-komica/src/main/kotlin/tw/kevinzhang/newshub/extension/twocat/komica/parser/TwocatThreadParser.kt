package tw.kevinzhang.newshub.extension.twocat.komica.parser

import org.jsoup.nodes.Element
import tw.kevinzhang.newshub.extension.twocat.komica.request.TwocatRequestBuilder
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import tw.kevinzhang.newshub.extension.twocat.komica.toResponseBody

internal class TwocatThreadParser(
    private val postParser: Parser<ParsedPost>,
    private val postRequestBuilder: TwocatRequestBuilder,
): Parser<List<ParsedPost>> {
    override fun parse(res: ResponseBody, req: Request): List<ParsedPost> {
        val source = Jsoup.parse(res.string())
        val url = req.url
        return listOf(parseHead(source, url)).plus(parseReplies(source, url))
    }

    private fun parseHead(source: Element, url: HttpUrl): ParsedPost {
        val req = postRequestBuilder.setUrl(url).build()
        return postParser.parse(
            source.selectFirst("div.threadpost").toResponseBody(),
            req,
        )
    }

    private fun parseReplies(source: Element, url: HttpUrl): List<ParsedPost> {
        val posts = source.select("div[class=\"reply\"][id^='r']").map { reply_ele ->
            val replyId = reply_ele.id().substring(1) // r123456
            val req = postRequestBuilder.setUrl(url).setFragment("r$replyId").build()
            postParser.parse(reply_ele.toResponseBody(), req)
        }
        return posts
    }
}
