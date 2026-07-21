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
import tw.kevinzhang.gamer_api.model.GRichText
import tw.kevinzhang.gamer_api.model.GRichTextRun
import tw.kevinzhang.gamer_api.model.GTextColor
import tw.kevinzhang.gamer_api.model.GTextEmphasis
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
        val parent = source.selectFirst("div.c-article__content") ?: return
        val walker = ContentWalker()
        parent.childNodes().forEach { walker.visit(it, InlineStyle()) }
        builder.setContent(walker.build().trim())
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
        val imageUrl = preferredImageUrl() ?: return null
        val photoAnchor = closest("a.photoswipe-image")
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
        return src
            ?.takeUnless { it.startsWith("data:", ignoreCase = true) }
            ?.let { resolveUrl(it, baseUri()) }
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

    /**
     * Walk the article once, in DOM order.  In particular, never call
     * `selectFirst("img")` on a container: that would turn a whole formatted
     * paragraph or table into its first image and discard all siblings.
     */
    private inner class ContentWalker {
        private val paragraphs = mutableListOf<GParagraph>()
        private val runs = mutableListOf<GRichTextRun>()

        fun visit(node: Node, inheritedStyle: InlineStyle) {
            when (node) {
                is TextNode -> appendText(node.text(), inheritedStyle)
                is Element -> visitElement(node, inheritedStyle)
            }
        }

        fun build(): List<GParagraph> {
            flushText()
            return paragraphs
        }

        private fun visitElement(element: Element, inheritedStyle: InlineStyle) {
            val tag = element.normalName()
            if (tag in IGNORED_TAGS) return

            when (tag) {
                "br" -> appendLineBreak()
                "img" -> element.toImageInfo()?.let(::appendImage)
                "iframe" -> appendVideo(element)
                "tr" -> visitTableRow(element, inheritedStyle)
                else -> {
                    val style = inheritedStyle.merge(element)
                    val isBlock = tag in BLOCK_TAGS
                    element.childNodes().forEach { visit(it, style) }
                    if (isBlock) appendLineBreak()
                }
            }
        }

        private fun visitTableRow(row: Element, inheritedStyle: InlineStyle) {
            val cells = row.children().filter { it.normalName() in TABLE_CELL_TAGS }
            if (cells.isEmpty()) {
                row.childNodes().forEach { visit(it, inheritedStyle.merge(row)) }
            } else {
                cells.forEachIndexed { index, cell ->
                    cell.childNodes().forEach { visit(it, inheritedStyle.merge(row).merge(cell)) }
                    if (index < cells.lastIndex) appendText(" | ", inheritedStyle)
                }
            }
            appendLineBreak()
        }

        private fun appendImage(image: GImageInfo) {
            flushText()
            paragraphs += image
        }

        private fun appendVideo(iframe: Element) {
            val embedUrl = iframe.attr("data-src").takeIf(String::isNotBlank)
                ?: iframe.attr("src").takeIf(String::isNotBlank)
                ?: return
            val videoId = extractYouTubeVideoId(embedUrl) ?: return
            flushText()
            paragraphs += GVideoInfo("https://www.youtube.com/watch?v=$videoId", GVideoSite.YOUTUBE)
        }

        private fun appendLineBreak() {
            if (runs.lastOrNull()?.text?.endsWith('\n') == true) return
            appendText("\n", InlineStyle())
        }

        private fun appendText(rawText: String, style: InlineStyle) {
            if (rawText.isBlank() && rawText != "\n") return
            val text = rawText.replace("\r\n", "\n").replace('\r', '\n')
            if (text.isEmpty()) return
            val run = GRichTextRun(
                text = text,
                color = style.color,
                emphasis = style.emphasis,
                linkUrl = style.linkUrl,
            )
            val previous = runs.lastOrNull()
            if (previous != null && previous.color == run.color &&
                previous.emphasis == run.emphasis && previous.linkUrl == run.linkUrl
            ) {
                runs[runs.lastIndex] = previous.copy(text = previous.text + text)
            } else {
                runs += run
            }
        }

        private fun flushText() {
            if (runs.isEmpty()) return
            paragraphs += GRichText(runs.toList())
            runs.clear()
        }
    }

    private data class InlineStyle(
        val color: GTextColor = GTextColor.DEFAULT,
        val emphasis: GTextEmphasis = GTextEmphasis.NORMAL,
        val linkUrl: String? = null,
    )

    private fun InlineStyle.merge(element: Element): InlineStyle {
        val tag = element.normalName()
        val elementColor = element.attr("color").takeIf(String::isNotBlank)
            ?: COLOR_STYLE.find(element.attr("style"))?.groupValues?.get(1)
        val isBold = tag == "b" || tag == "strong" ||
            FONT_WEIGHT_STYLE.find(element.attr("style"))?.groupValues?.get(1)
                ?.let { it == "bold" || it.toIntOrNull()?.let { weight -> weight >= 600 } == true }
                ?: false
        val href = element.attr("href").takeIf { tag == "a" && it.isNotBlank() }
            ?.let { resolveUrl(it, element.baseUri()) }
        return copy(
            color = elementColor?.toSemanticColor() ?: color,
            emphasis = if (isBold) GTextEmphasis.BRIGHT else emphasis,
            linkUrl = href ?: linkUrl,
        )
    }

    private fun String.toSemanticColor(): GTextColor {
        val value = trim().lowercase(Locale.US)
        return when (value) {
            "black" -> GTextColor.BLACK
            "white" -> GTextColor.WHITE
            "red" -> GTextColor.RED
            "green" -> GTextColor.GREEN
            "yellow", "olive" -> GTextColor.YELLOW
            "blue" -> GTextColor.BLUE
            "magenta", "fuchsia", "purple" -> GTextColor.MAGENTA
            "cyan", "aqua", "teal" -> GTextColor.CYAN
            else -> value.toRgb()?.toSemanticColor() ?: GTextColor.DEFAULT
        }
    }

    private fun String.toRgb(): Triple<Int, Int, Int>? {
        val hex = removePrefix("#")
        val expanded = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString(separator = "")
            6 -> hex
            else -> return null
        }
        return expanded.toIntOrNull(16)?.let { value ->
            Triple((value shr 16) and 0xff, (value shr 8) and 0xff, value and 0xff)
        }
    }

    private fun Triple<Int, Int, Int>.toSemanticColor(): GTextColor {
        val (red, green, blue) = this
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        if (max - min < 24) {
            return when {
                max < 96 -> GTextColor.BLACK
                min > 210 -> GTextColor.WHITE
                else -> GTextColor.DEFAULT
            }
        }
        return when {
            red >= green * 3 / 2 && red >= blue * 3 / 2 -> GTextColor.RED
            green >= red * 3 / 2 && green >= blue * 3 / 2 -> GTextColor.GREEN
            blue >= red * 3 / 2 && blue >= green * 3 / 2 -> GTextColor.BLUE
            red >= blue * 3 / 2 && green >= blue * 3 / 2 -> GTextColor.YELLOW
            red >= green * 3 / 2 && blue >= green * 3 / 2 -> GTextColor.MAGENTA
            else -> GTextColor.CYAN
        }
    }

    private companion object {
        val BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "div", "dl", "dt", "dd",
            "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hr", "li", "main", "ol", "p", "pre", "section", "table", "ul",
        )
        val TABLE_CELL_TAGS = setOf("td", "th")
        val IGNORED_TAGS = setOf("script", "style", "noscript", "source", "template")
        val COLOR_STYLE = Regex("""(?:^|;)\s*color\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
        val FONT_WEIGHT_STYLE = Regex("""(?:^|;)\s*font-weight\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
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
