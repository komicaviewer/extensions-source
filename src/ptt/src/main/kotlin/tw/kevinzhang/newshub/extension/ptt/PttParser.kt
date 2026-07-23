package tw.kevinzhang.newshub.extension.ptt

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Comment
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.RichTextColor
import tw.kevinzhang.extension_api.model.RichTextEmphasis
import tw.kevinzhang.extension_api.model.RichTextLayout
import tw.kevinzhang.extension_api.model.RichTextRun
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Internal parsed result for PTT's single-post article page. */
internal data class PttParsedThreadPage(
    val posts: List<Post>,
    val nextPageToken: String?,
)

internal data class PttBoardListing(
    val summaries: List<ThreadSummary>,
    val previousPageIndex: Int?,
)

internal class PttParser(private val clock: Clock = Clock.system(TAIPEI)) {
    fun parseBoardListing(html: String, boardName: String, sourceIconUrl: String?): PttBoardListing {
        val document = Jsoup.parse(html, PttUrlPolicy.boardUrl(boardName))
        val summaries = document.select("div.r-ent").mapNotNull { row ->
            val anchor = row.selectFirst("div.title > a") ?: return@mapNotNull null // deleted articles have no URL.
            val articleUrl = PttUrlPolicy.resolveArticle(document.location(), anchor.attr("href")) ?: return@mapNotNull null
            val title = anchor.text().trim().ifBlank { return@mapNotNull null }
            ThreadSummary(
                sourceId = PttBoardCatalog.SOURCE_ID,
                boardUrl = PttUrlPolicy.boardUrl(boardName),
                id = articleUrl,
                title = title,
                author = row.selectFirst("div.author")?.text()?.trim()?.ifBlank { null },
                createdAt = articleEpochMillis(articleUrl) ?: parsePttDate(row.selectFirst("div.date")?.text()),
                commentCount = pushCount(row.selectFirst("div.nrec")?.text()),
                rawImage = null,
                thumbnail = null,
                previewContent = emptyList(),
                sourceIconUrl = sourceIconUrl,
                replyCount = pushCount(row.selectFirst("div.nrec")?.text()),
            )
        }
        return PttBoardListing(summaries, previousIndex(document))
    }

    fun parseThreadPage(html: String, articleUrl: String, sourceIconUrl: String?): PttParsedThreadPage {
        val safeArticleUrl = requireNotNull(PttUrlPolicy.articleUrl(articleUrl)) { "Untrusted PTT article URL" }
        val document = Jsoup.parse(html, safeArticleUrl)
        val main = document.selectFirst("#main-content") ?: return PttParsedThreadPage(emptyList(), null)
        val articleId = safeArticleUrl.substringAfterLast('/').removeSuffix(".html")
        val comments = main.select("div.push").mapIndexed { index, push ->
            val tag = push.selectFirst("span.push-tag")?.text()?.trim().orEmpty()
            val author = push.selectFirst("span.push-userid")?.text()?.trim()?.ifBlank { null }
            val content = push.selectFirst("span.push-content")?.text().orEmpty().removePrefix(":").trim()
            val display = listOf(tag, content).filter { it.isNotBlank() }.joinToString(" ")
            Comment(
                id = "$articleId:push:$index",
                author = author,
                createdAt = parsePttDate(push.selectFirst("span.push-ipdatetime")?.text()),
                content = display.takeIf { it.isNotBlank() }?.let { listOf(Paragraph.Text(it)) } ?: emptyList(),
            )
        }
        val article = main.clone().apply {
            select("div.article-metaline, div.article-metaline-right, div.push").remove()
            select("span").filter { footer ->
                footer.hasClass("f2") && footer.ownText().trimStart().let { text ->
                    text.startsWith(PTT_FOOTER_STATION) || text.startsWith(PTT_FOOTER_URL)
                }
            }.forEach(Element::remove)
        }
        val content = paragraphs(article)
        val values = main.select("div.article-metaline span.article-meta-value")
        val title = metadataValue(main, "標題")
        val author = metadataValue(main, "作者")
        val createdAt = parseFullDate(metadataValue(main, "時間"))
            ?: values.lastOrNull()?.text()?.let(::parseFullDate)
        return PttParsedThreadPage(
            posts = listOf(
                Post(
                    id = articleId,
                    author = author,
                    createdAt = createdAt,
                    thumbnail = content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                    content = content,
                    comments = comments,
                    rawHtml = main.html(),
                    sourceIconUrl = sourceIconUrl,
                    replyCount = comments.size,
                ),
            ),
            nextPageToken = null,
        )
    }

    private fun paragraphs(root: Element): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        val richRuns = mutableListOf<RichTextRun>()
        root.childNodes().forEach { appendNode(it, TextStyle(), richRuns, paragraphs) }
        flushRichText(richRuns, paragraphs)
        return paragraphs.trimLeadingMetadataWhitespace()
    }

    private fun appendNode(
        node: Node,
        style: TextStyle,
        richRuns: MutableList<RichTextRun>,
        paragraphs: MutableList<Paragraph>,
    ) {
        when (node) {
            is TextNode -> appendRun(node.wholeText, style, richRuns)
            is Element -> when (node.tagName()) {
                "br" -> appendRun("\n", style, richRuns)
                "a" -> appendAnchor(node, style, richRuns, paragraphs)
                "img" -> {
                    val raw = PttUrlPolicy.safeExternalUrl(node.absUrl("src"))
                    if (raw != null && isImage(raw)) {
                        flushRichText(richRuns, paragraphs)
                        paragraphs += Paragraph.ImageInfo(raw, raw)
                    }
                }
                else -> {
                    val childStyle = style.withPttClasses(node.classNames())
                    node.childNodes().forEach { appendNode(it, childStyle, richRuns, paragraphs) }
                }
            }
        }
    }

    private fun appendAnchor(
        anchor: Element,
        style: TextStyle,
        richRuns: MutableList<RichTextRun>,
        paragraphs: MutableList<Paragraph>,
    ) {
        val url = PttUrlPolicy.safeExternalUrl(anchor.absUrl("href"))
        if (url == null) {
            val childStyle = style.withPttClasses(anchor.classNames())
            anchor.childNodes().forEach { appendNode(it, childStyle, richRuns, paragraphs) }
            return
        }
        when {
            isImage(url) -> {
                // PTT often labels direct images as "image". Keep a usable, normalized URL
                // instead, then place the rendered image immediately after its link.
                appendRun(
                    url,
                    style.withPttClasses(anchor.classNames()).copy(linkUrl = url),
                    richRuns,
                )
                flushRichText(richRuns, paragraphs)
                paragraphs += Paragraph.ImageInfo(url, url)
            }
            isYoutube(url) -> {
                flushRichText(richRuns, paragraphs)
                paragraphs += Paragraph.VideoInfo(url, Paragraph.VideoInfo.Site.YOUTUBE)
            }
            else -> {
                val childStyle = style.withPttClasses(anchor.classNames()).copy(linkUrl = url)
                anchor.childNodes().forEach { appendNode(it, childStyle, richRuns, paragraphs) }
            }
        }
    }

    private fun appendRun(value: String, style: TextStyle, into: MutableList<RichTextRun>) {
        if (value.isEmpty()) return
        val next = RichTextRun(
            text = value,
            color = style.color,
            emphasis = style.emphasis,
            linkUrl = style.linkUrl,
        )
        val previous = into.lastOrNull()
        if (
            previous != null &&
            previous.color == next.color &&
            previous.emphasis == next.emphasis &&
            previous.linkUrl == next.linkUrl
        ) {
            into[into.lastIndex] = previous.copy(text = previous.text + value)
        } else {
            into += next
        }
    }

    private fun flushRichText(richRuns: MutableList<RichTextRun>, into: MutableList<Paragraph>) {
        if (richRuns.isEmpty()) return
        into += Paragraph.RichText(
            runs = richRuns.toList(),
            layout = RichTextLayout.PREFORMATTED_WRAP,
        )
        richRuns.clear()
    }

    /** Metadata removal leaves PTT's delimiter newline at the very start of the cloned body. */
    private fun List<Paragraph>.trimLeadingMetadataWhitespace(): List<Paragraph> {
        val first = firstOrNull() as? Paragraph.RichText ?: return this
        val runs = first.runs.toMutableList()
        while (runs.isNotEmpty()) {
            val text = runs.first().text
            val trimmed = text.trimStart('\r', '\n')
            if (trimmed.isEmpty()) {
                runs.removeAt(0)
            } else {
                runs[0] = runs.first().copy(text = trimmed)
                break
            }
        }
        return when {
            runs == first.runs -> this
            runs.isEmpty() -> drop(1)
            else -> listOf(first.copy(runs = runs)) + drop(1)
        }
    }

    private data class TextStyle(
        val color: RichTextColor = RichTextColor.DEFAULT,
        val emphasis: RichTextEmphasis = RichTextEmphasis.NORMAL,
        val linkUrl: String? = null,
    ) {
        fun withPttClasses(classes: Set<String>): TextStyle = copy(
            color = classes.firstNotNullOfOrNull(::pttColor) ?: color,
            emphasis = if ("hl" in classes) RichTextEmphasis.BRIGHT else emphasis,
        )
    }

    private fun metadataValue(main: Element, label: String): String? = main.select("div.article-metaline").firstNotNullOfOrNull { line ->
        line.selectFirst("span.article-meta-tag")?.text()?.trim()?.takeIf { it == label }
            ?.let { line.selectFirst("span.article-meta-value")?.text()?.trim()?.ifBlank { null } }
    }

    private fun previousIndex(document: org.jsoup.nodes.Document): Int? = document
        .selectFirst("div.btn-group-paging a:contains(上頁)")
        ?.attr("href")
        ?.let { INDEX_FILE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun pushCount(value: String?): Int? = when (value?.trim()) {
        null, "" -> 0
        "爆" -> 100
        else -> if (value.trim().startsWith("X")) 0 else value.trim().toIntOrNull()
    }

    private fun articleEpochMillis(articleUrl: String): Long? = ARTICLE_EPOCH
        .find(articleUrl)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.times(1_000L)

    private fun parsePttDate(value: String?): Long? {
        val match = SHORT_DATE.find(value.orEmpty()) ?: return null
        val today = LocalDate.now(clock.withZone(TAIPEI))
        var date = LocalDate.of(today.year, match.groupValues[1].toInt(), match.groupValues[2].toInt())
        // PTT omits the year. A date more than a month in the future belongs to the prior year.
        if (date.isAfter(today.plusDays(31))) date = date.minusYears(1)
        val time = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "00:00"
        return runCatching { LocalDateTime.parse("$date $time", SHORT_DATE_TIME).atZone(TAIPEI).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun parseFullDate(value: String?): Long? = value?.let {
        runCatching { LocalDateTime.parse(it.trim(), FULL_DATE).atZone(TAIPEI).toInstant().toEpochMilli() }.getOrNull()
    }

    private companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
        val INDEX_FILE = Regex("index(\\d+)\\.html")
        val ARTICLE_EPOCH = Regex("/M\\.(\\d+)\\.")
        val SHORT_DATE = Regex("(\\d{1,2})/(\\d{1,2})(?:\\s+(\\d{2}:\\d{2}))?")
        val SHORT_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US)
        val IMAGE = Regex("\\.(?:jpe?g|png|gif|webp|bmp|avif)(?:$|[?#])", RegexOption.IGNORE_CASE)
        const val PTT_FOOTER_STATION = "※ 發信站:"
        const val PTT_FOOTER_URL = "※ 文章網址:"
        fun isImage(url: String) = IMAGE.containsMatchIn(url)
        fun isYoutube(url: String): Boolean {
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
            return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        }

        fun pttColor(className: String): RichTextColor? = when (className) {
            "f0" -> RichTextColor.BLACK
            "f1" -> RichTextColor.RED
            "f2" -> RichTextColor.GREEN
            "f3" -> RichTextColor.YELLOW
            "f4" -> RichTextColor.BLUE
            "f5" -> RichTextColor.MAGENTA
            "f6" -> RichTextColor.CYAN
            "f7" -> RichTextColor.WHITE
            else -> null
        }
    }
}
