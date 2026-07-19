package tw.kevinzhang.newshub.extension.twocat.komica.parser

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.twocat.komica.parser.PostHeadParser
import tw.kevinzhang.newshub.extension.twocat.komica.parser.UrlParser
import java.util.regex.Pattern

internal class TwocatPostParser(
    private val urlParser: UrlParser,
    private val postHeadParser: PostHeadParser,
): Parser<ParsedPost> {
    private val builder = ParsedPostBuilder()

    override fun parse(res: ResponseBody, req: Request): ParsedPost {
        val source = Jsoup.parse(res.string())
        val httpUrl = req.url
        setDetail(source, httpUrl)
        setContent(source)
        setPicture(source, httpUrl)
        builder.setUrl(httpUrl.toString())
        builder.setPostId(urlParser.parsePostId(httpUrl)!!)
        return builder.build()
    }

    private fun setDetail(source: Element, url: HttpUrl) {
        builder.setTitle(postHeadParser.parseTitle(source, url) ?: "")
            .setPoster(postHeadParser.parsePoster(source, url) ?: "")
            .setCreatedAt(postHeadParser.parseCreatedAt(source, url) ?: 0L)
    }

    private fun setContent(source: Element) {
        val contents = source.selectFirst(".quote").childNodes()
            .filterIsInstance<TextNode>()
            .flatMap {
                resolveLink(it.text()) { link ->
                    if (link.match(IMAGE_URL_PATTERN)) {
                        Paragraph.ImageInfo(link, link)
                    } else if (link.match(VIDEO_URL_PATTERN)) {
                        Paragraph.VideoInfo(link)
                    } else {
                        Paragraph.Link(link)
                    }
                }
            }
        builder.setContent(contents)
    }

    private fun setPicture(source: Element, url: HttpUrl) {
        source.selectFirst("a.imglink[href=#]")?.let {
            val fileName = source.selectFirst("a.imglink[href=#]").attr("title")
            val boardId = urlParser.parseBoardId(url);
            val newRawLink = "https://cat.2nyan.org/$boardId/src/${fileName}"
            val newThumbLink =
                "https://cat.2nyan.org/$boardId/thumb/${fileName.basename()}s.jpg"
            builder.addContent(
                Paragraph.ImageInfo(newThumbLink, newRawLink)
            )
        }
    }

    /**
     * 解析文章，裡面可能包含連結
     * @param article 文章
     */
    private fun resolveLink(article: String, callback: (String) -> Paragraph = {
        Paragraph.Link(it)
    }): List<Paragraph> {
        val m = WEB_URL_PATTERN.matcher(article)
        val list: MutableList<Paragraph> = ArrayList()
        var index = 0
        while (m.find()) {
            val url = m.group()
            val preParagraph = article.substring(index, m.start())
            list.add(Paragraph.Text(preParagraph))
            list.add(callback(url))
            index = m.end()
        }
        val lastParagraph = article.substring(index)
        list.add(Paragraph.Text(lastParagraph))
        return list
    }

    private fun String.match(p: Pattern): Boolean {
        return p.matcher(this).find()
    }

    companion object {
        private val WEB_URL_PATTERN = Pattern.compile("((http?|https|ftp|file)://)?((W|w){3}.)?[a-zA-Z0-9]+\\.[a-zA-Z]+")
        private val IMAGE_URL_PATTERN = Pattern.compile("(http(s?):/)(/[^/]+)+\\.(?:jpg|gif|png)")
        private val VIDEO_URL_PATTERN = Pattern.compile("(http(s?):/)(/[^/]+)+\\.(?:webm|mp4)")
    }

    private fun String.basename(): String {
        val dotIndex = lastIndexOf('.')
        return if (dotIndex == -1) this else substring(0, dotIndex)
    }
}
