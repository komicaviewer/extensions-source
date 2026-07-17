package tw.kevinzhang.gamer_api.parser

import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.gamer_api.model.GImageInfo
import tw.kevinzhang.gamer_api.model.GLink
import tw.kevinzhang.gamer_api.model.GParagraph
import tw.kevinzhang.gamer_api.model.GPost
import tw.kevinzhang.gamer_api.model.GPostBuilder
import tw.kevinzhang.gamer_api.model.GText
import tw.kevinzhang.gamer_api.model.GVideoInfo
import tw.kevinzhang.gamer_api.model.GVideoSite
import tw.kevinzhang.gamer_api.model.trim
import java.net.URI
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class PostParser(
    private val urlParser: UrlParser,
): Parser<GPost> {
    private var builder = GPostBuilder()

    override fun parse(body: ResponseBody, req: Request): GPost {
        val source = Jsoup.parse(body.string(), req.url.toString())
        val postId = urlParser.parseSn(req.url)!!
        val bsn = urlParser.parseBsn(req.url)!!
        setTitle(source)
        setCreatedAt(source)
        setPosterName(source)
        setPosterId(source)
        setLike(source)
        setUnlike(source)
        setComments(source, postId)
        setCommentsUrl(bsn, postId)
        setContent(source)
        setRawHtml(source)
        builder.setUrl(req.url.toString())
        builder.setPostId(postId)
        builder.setPage(urlParser.parsePage(req.url))
        val post = builder.build()
        builder = GPostBuilder()
        return post
    }

    private fun setTitle(source: Element) {
        val text = source.selectFirst("div.c-post__header h1.c-post__header__title")?.text()
        if (text.isNullOrBlank().not())
            builder.setTitle(text!!)
    }

    private fun setCreatedAt(source: Element) {
        val element = source.selectFirst("div.c-post__header__info a.edittime.tippy-post-info")
        val string = element.attr("data-mtime")
        if (string.isNullOrBlank().not())
            builder.setCreatedAt(string.toTimestamp())
    }

    private fun setPosterName(source: Element) {
        val text = source.selectFirst("a.username").text()
        if (text.isNullOrBlank().not())
            builder.setPosterName(text)
    }

    private fun setPosterId(source: Element) {
        val text = source.selectFirst("a.userid").text()
        if (text.isNullOrBlank().not())
            builder.setPosterId(text)
    }

    private fun setContent(source: Element) {
        val list: MutableList<GParagraph> = ArrayList<GParagraph>()
        val parent = source.selectFirst("div.c-article__content")
        for (child in parent.childNodes().flatDiv()) {
            if (child is TextNode) {
                val content = child.text()
                if (content.trim().isEmpty()) {
                    continue
                }
                list.add(GText(content))
            }
            if (child is Element) {
                val imageInfo = child.toImageInfo()
                if (imageInfo != null) {
                    list.add(imageInfo)
                } else if (child.`is`("a[href^=\"http://\"], a[href^=\"https://\"]")) {
                    list.add(GLink(child.ownText()))
                } else if (child.tagName() == "br") {
                    list.add(GText(""))
                } else if (child.tagName() == "iframe") {
                    val dataSrc = child.attr("data-src")
                    val videoId = extractYouTubeVideoId(dataSrc)
                    if (videoId != null) {
                        list.add(GVideoInfo("https://www.youtube.com/watch?v=$videoId", GVideoSite.YOUTUBE))
                    }
                }
            }
        }
        builder.setContent(list.trim())
    }

    private fun setRawHtml(source: Element) {
        val content = source.selectFirst("div.c-article__content") ?: return
        // Bahamut uses LazyLoad.js. The host renders this fragment with JavaScript disabled,
        // so copy lazy-load attributes to native image attributes and make URLs absolute.
        content.select("img").forEach { img ->
            val dataSrcset = img.attr("data-srcset").takeIf(String::isNotBlank)
            val srcset = dataSrcset ?: img.attr("srcset").takeIf(String::isNotBlank)
            if (srcset != null) {
                img.attr("srcset", normalizeSrcset(srcset, img.baseUri()))
            }

            val src = img.attr("data-src").takeIf(String::isNotBlank)
                ?: srcset?.bestSrcsetUrl()
                ?: img.attr("src").takeIf(String::isNotBlank)
            if (src != null) {
                img.attr("src", resolveUrl(src, img.baseUri()))
            }
        }
        content.select("source[srcset], source[data-srcset]").forEach { sourceElement ->
            val srcset = sourceElement.attr("data-srcset").takeIf(String::isNotBlank)
                ?: sourceElement.attr("srcset")
            sourceElement.attr("srcset", normalizeSrcset(srcset, sourceElement.baseUri()))
        }
        content.select("a[href]").forEach { anchor ->
            anchor.attr("href", resolveUrl(anchor.attr("href"), anchor.baseUri()))
        }
        builder.setRawHtml(content.html())
    }

    private fun Element.toImageInfo(): GImageInfo? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        val imageUrl = image.preferredImageUrl() ?: return null
        val photoAnchor = image.closest("a.photoswipe-image")
        val rawUrl = photoAnchor
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?.let { resolveUrl(it, photoAnchor.baseUri()) }
            ?: imageUrl
        return GImageInfo(thumb = imageUrl, raw = rawUrl)
    }

    private fun Element.preferredImageUrl(): String? {
        val srcset = attr("data-srcset").takeIf(String::isNotBlank)
            ?: attr("srcset").takeIf(String::isNotBlank)
        val src = attr("data-src").takeIf(String::isNotBlank)
            ?: srcset?.bestSrcsetUrl()
            ?: attr("src").takeIf(String::isNotBlank)
        return src?.let { resolveUrl(it, baseUri()) }
    }

    private fun String.bestSrcsetUrl(): String? =
        split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.substringBefore(' ') }
            .lastOrNull()

    private fun normalizeSrcset(srcset: String, baseUri: String): String =
        srcset.split(',').joinToString(", ") { candidate ->
            val trimmed = candidate.trim()
            val url = trimmed.substringBefore(' ')
            val descriptor = trimmed.substringAfter(' ', missingDelimiterValue = "")
            buildString {
                append(resolveUrl(url, baseUri))
                if (descriptor.isNotBlank()) {
                    append(' ')
                    append(descriptor)
                }
            }
        }

    private fun resolveUrl(url: String, baseUri: String): String =
        try {
            URI(baseUri).resolve(url).toString()
        } catch (_: IllegalArgumentException) {
            url
        }

    private fun setLike(source: Element) {
        val string = source.selectFirst("div.gp a.count.tippy-gpbp-list").ownText()
        builder.setUnlike(string.toIntOrNull() ?: 0)
    }

    private fun setUnlike(source: Element) {
        val string = source.selectFirst("div.bp a.count.tippy-gpbp-list").ownText()
        builder.setUnlike(string.toIntOrNull() ?: 0)
    }

    private fun setComments(source: Element, postId: String) {
        val string = source.selectFirst("a#showoldCommend_$postId")?.text()?.filter { it.isDigit() }
        builder.setComments(string?.toIntOrNull() ?: 0)
    }

    private fun setCommentsUrl(bsn: String, postId: String) {
        builder.setCommentsUrl("https://forum.gamer.com.tw/ajax/moreCommend.php?bsn=$bsn&snB=$postId")
    }

    private fun extractYouTubeVideoId(embedUrl: String): String? =
        Regex("""/embed/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1)

    fun List<Node>.flatDiv(): List<Node> {
        return this.flatMap {
            if (it is Element && it.`is`("div")){
                it.childNodes().flatDiv()
            } else {
                listOf(it)
            }
        }
    }

    private fun String.toTimestamp(): Long {
        return try {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
            formatter.parse(this).time
        } catch (ignored: ParseException) {
            0L
        }
    }
}
