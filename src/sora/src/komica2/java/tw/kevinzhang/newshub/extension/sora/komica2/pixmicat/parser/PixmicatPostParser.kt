package tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser

import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.Parser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPost
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPostBuilder
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.PostHeadParser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.UrlParser

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Paragraph

internal class PixmicatPostParser(
    private val urlParser: UrlParser,
    private val postHeadParser: PostHeadParser,
): Parser<ParsedPost> {
    private val builder = ParsedPostBuilder()

    override fun parse(res: ResponseBody, req: Request): ParsedPost {
        val source = Jsoup.parse(res.string())
        val httpUrl = req.url
        setDetail(source, httpUrl)
        setContent(source)
        setPicture(source, httpUrl.host)
        builder.setUrl(httpUrl.toString())
        builder.setPostId(urlParser.parsePostId(httpUrl)!!)
        return builder.build()
    }

    private fun setDetail(source: Element, httpUrl: HttpUrl) {
        builder.setTitle(postHeadParser.parseTitle(source, httpUrl) ?: "")
            .setPoster(postHeadParser.parsePoster(source, httpUrl) ?: "")
            .setCreatedAt(postHeadParser.parseCreatedAt(source, httpUrl) ?: 0L)
    }

    private fun setContent(source: Element) {
        val list: MutableList<Paragraph> = ArrayList<Paragraph>()
        val parent = requireNotNull(source.selectFirst(".quote")) { "Missing .quote content" }
        for (child in parent.childNodes()) {
            if (child is TextNode) {
                val content = child.text()
                if (content.trim().isEmpty()) {
                    continue
                }
                list.add(Paragraph.Text(content))
            }
            if (child is Element) {
                if (child.tagName() == "br") {
                    list.add(Paragraph.Text(""))
                }
                if (child.`is`("span.resquote")) {
                    val qlink = child.selectFirst("a.qlink")
                    if (qlink != null) {
                        val replyTo = qlink.text()
                            .replace(">".toRegex(), "") // for sora.komica.org
                            .replace("No.", "") // for 2cat.komica.org
                        list.add(Paragraph.ReplyTo(replyTo))
                    } else {
                        val quote = child.ownText().replace(">".toRegex(), "")
                        list.add(Paragraph.Quote(quote))
                    }
                }
                if (child.`is`("a[href^=\"http://\"], a[href^=\"https://\"]")) {
                    list.add(Paragraph.Link(child.ownText()))
                }
            }
        }
        builder.setContent(list)
    }

    private fun setPicture(source: Element, host: String) {
        source.select("a").forEach { link ->
            val href = link.attr("href")
            val img = link.selectFirst("img.img")

            if (img != null && href.isNotEmpty()) {
                if (href.isImageUrl()) {
                    val thumbnailUrl = img.attr("data-original").ifEmpty {
                        img.attr("src")
                    }

                    if (thumbnailUrl.isNotEmpty()) {
                        builder.addContent(
                            Paragraph.ImageInfo(
                                thumbnailUrl.normalizeUrl(),
                                href.normalizeUrl(),
                            )
                        )
                    } else {
                        builder.addContent(Paragraph.ImageInfo(null, href.normalizeUrl()))
                    }
                } else if (href.isVideoUrl()) {
                    builder.addContent(
                        Paragraph.VideoInfo(
                            href.normalizeUrl(),
                        )
                    )
                }
            }

        }
    }
}


/**
 * 如果找不到thread標籤，就是2cat.komica.org，要用 [installThreadTag] 改成標準綜合版樣式
 */
fun Element.installThreadTag(): Element {
    if (this.selectFirst("div.thread") != null) return this

    // 先建立快照，避免在移動節點時改變正在迭代的 live collection。
    var thread: Element? = null
    for (child in children().toList()) {
        when {
            child.hasClass("threadpost") -> {
                thread = appendElement("div").addClass("thread")
                thread.appendChild(child)
            }
            child.tagName() == "hr" -> thread = null
            thread != null -> thread.appendChild(child)
        }
    }
    return this
}

fun String.normalizeUrl(): String {
    return when {
        startsWith("//") -> "https:$this"
        startsWith("http://") || startsWith("https://") -> this
        else -> this
    }
}

fun String.isImageUrl(): Boolean {
    val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
    return imageExtensions.any { lowercase().contains(it) }
}

fun String.isVideoUrl(): Boolean {
    val videoExtensions = listOf(".webm")
    return videoExtensions.any { lowercase().contains(it) }
}
