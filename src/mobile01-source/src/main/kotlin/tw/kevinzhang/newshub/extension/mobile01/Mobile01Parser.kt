package tw.kevinzhang.newshub.extension.mobile01

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class Mobile01ThreadPageResult(
    val posts: List<Post>,
    val nextPageToken: String?,
    val title: String?,
)

/** HTML parser only. It receives fixtures or already-authorized HTML and never performs network work. */
internal class Mobile01Parser {
    fun parseThreadSummaries(
        html: String,
        boardUrl: String,
        sourceIconUrl: String?,
        listingPage: Int,
    ): List<ThreadSummary> {
        val boardId = requireNotNull(Mobile01UrlPolicy.boardId(boardUrl)) { "Untrusted Mobile01 board URL" }
        val document = Jsoup.parse(html, boardUrl)
        val titleLinks = document.select(
            ".c-listTableTd__title a[href], .c-articleItem__title > a[href]",
        )
            .filter { it.closest(".l-jumpList") == null }
        if (titleLinks.isEmpty()) throw Mobile01PageStructureException("listing")
        return titleLinks.mapNotNull { anchor ->
            val row = anchor.closest(".l-listTable__tr, .c-articleItem, tr, li") ?: anchor.parent()
            if (listingPage > 1 && row.isSticky()) return@mapNotNull null
            val thread = Mobile01UrlPolicy.resolveThread(boardUrl, anchor.attr("href")) ?: return@mapNotNull null
            if (thread.boardId != boardId || row.isPromoted()) return@mapNotNull null
            val title = anchor.text().normalized().ifBlank { return@mapNotNull null }
            val preview = emptyList<Paragraph>()
            ThreadSummary(
                sourceId = Mobile01BoardCatalog.SOURCE_ID,
                boardUrl = Mobile01UrlPolicy.boardUrl(boardId),
                // A listing may link to the latest page. The summary must still identify page one.
                id = Mobile01UrlPolicy.threadUrl(thread.boardId, thread.threadId),
                title = title,
                author = value(row, ".u-username", ".c-articleItemRemark__wAuto > span"),
                // Listing order is driven by the last activity, not the original publish time.
                createdAt = lastActivityFrom(row),
                commentCount = countFrom(row),
                rawImage = preview.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.raw,
                thumbnail = preview.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
                previewContent = preview,
                sourceIconUrl = sourceIconUrl,
                replyCount = countFrom(row),
            )
        }.distinctBy(ThreadSummary::id)
    }

    fun parseThreadPage(html: String, threadUrl: String, sourceIconUrl: String?): Mobile01ThreadPageResult {
        val thread = requireNotNull(Mobile01UrlPolicy.thread(threadUrl)) { "Untrusted Mobile01 thread URL" }
        val document = Jsoup.parse(html, thread.url)
        val posts = postElements(document).mapNotNull { element ->
            parsePost(element, thread, sourceIconUrl)
        }.distinctBy(Post::id)
        if (posts.isEmpty()) throw Mobile01PageStructureException("thread")
        return Mobile01ThreadPageResult(
            posts = posts,
            nextPageToken = nextPageToken(document, thread),
            title = document.selectFirst("h1")?.text()?.normalized()?.ifBlank { null },
        )
    }

    private fun postElements(document: org.jsoup.nodes.Document): List<Element> {
        return document.select(".l-articlePage, .l-mainArticle")
            .filter { articleFrom(it) != null }
    }

    private fun parsePost(
        page: Element,
        thread: Mobile01ThreadUrl,
        sourceIconUrl: String?,
    ): Post? {
        val article = articleFrom(page) ?: return null
        val articleId = ARTICLE_ID.matchEntire(article.id())?.groupValues?.get(1)
            ?: page.selectFirst("a[name]")?.attr("name")?.takeIf(DIGITS::matches)
            ?: page.selectFirst("[id^=name_]")?.id()?.let { NAME_ID.matchEntire(it)?.groupValues?.get(1) }
            ?: throw Mobile01PageStructureException("thread post identifier")
        val author = page.selectFirst("#name_$articleId")
            ?.text()
            ?.normalized()
            ?.ifBlank { null }
        val content = paragraphs(article, thread.url)
        return Post(
            id = articleId,
            author = author,
            createdAt = dateFrom(page),
            thumbnail = content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
            content = content,
            comments = emptyList(),
            rawHtml = null,
            sourceIconUrl = sourceIconUrl,
            replyCount = null,
        )
    }

    private fun nextPageToken(document: org.jsoup.nodes.Document, current: Mobile01ThreadUrl): String? = document
        .selectFirst("link[rel=next][href]")
        ?.let { Mobile01UrlPolicy.resolveThread(current.url, it.attr("href")) }
        ?.takeIf { it.boardId == current.boardId && it.threadId == current.threadId && it.page > current.page }
        ?.url

    private fun paragraphs(root: Element, baseUrl: String): List<Paragraph> = buildList {
        root.childNodes().forEach { appendNode(it, baseUrl, this) }
    }.compactText()

    private fun appendNode(node: Node, baseUrl: String, into: MutableList<Paragraph>) {
        when (node) {
            is TextNode -> appendText(node.text(), into)
            is Element -> when (node.tagName().lowercase()) {
                "script", "style", "noscript", "button", "svg" -> Unit
                "br", "p", "div", "li" -> {
                    node.childNodes().forEach { appendNode(it, baseUrl, into) }
                    appendBreak(into)
                }
                "blockquote" -> {
                    val quote = node.text().normalized()
                    if (quote.isNotBlank()) into += Paragraph.Quote(quote)
                }
                "img" -> image(node, baseUrl)?.let(into::add)
                "video", "iframe" -> mediaUrl(node, baseUrl)?.let { into += Paragraph.VideoInfo(it, videoSite(it)) }
                "a" -> appendAnchor(node, baseUrl, into)
                else -> node.childNodes().forEach { appendNode(it, baseUrl, into) }
            }
        }
    }

    private fun appendAnchor(anchor: Element, baseUrl: String, into: MutableList<Paragraph>) {
        val href = absoluteUrl(baseUrl, anchor.attr("href"))
        val image = anchor.selectFirst("img")
        when {
            image != null -> image(image, baseUrl, rawOverride = href)?.let(into::add)
            href != null && isYoutube(href) -> into += Paragraph.VideoInfo(href, Paragraph.VideoInfo.Site.YOUTUBE)
            href != null -> into += Paragraph.Link(href)
            else -> anchor.childNodes().forEach { appendNode(it, baseUrl, into) }
        }
    }

    private fun image(element: Element, baseUrl: String, rawOverride: String? = null): Paragraph.ImageInfo? {
        val raw = rawOverride ?: absoluteUrl(
            baseUrl,
            element.attr("data-original").ifBlank { element.attr("data-src") }.ifBlank { element.attr("src") },
        )
            ?: return null
        val thumb = absoluteUrl(baseUrl, element.attr("data-thumb").ifBlank { element.attr("src") })
        return Paragraph.ImageInfo(thumb = thumb, raw = raw)
    }

    private fun mediaUrl(element: Element, baseUrl: String): String? = absoluteUrl(
        baseUrl,
        element.attr("src").ifBlank { element.attr("data-src") },
    )

    private fun absoluteUrl(baseUrl: String, raw: String): String? {
        if (raw.isBlank() || raw == "#") return null
        return Jsoup.parse("<a href=\"$raw\"></a>", baseUrl).selectFirst("a")?.absUrl("href")
            ?.let(Mobile01UrlPolicy::safeContentUrl)
    }

    private fun appendText(value: String, into: MutableList<Paragraph>) {
        value.splitToSequence('\n').forEach { line ->
            val text = line.normalized()
            if (text.isNotBlank()) into += if (text.startsWith(">")) Paragraph.Quote(text) else Paragraph.Text(text)
        }
    }

    private fun appendBreak(into: MutableList<Paragraph>) {
        if (into.lastOrNull() !is Paragraph.Text || (into.last() as Paragraph.Text).content != "\n") {
            into += Paragraph.Text("\n")
        }
    }

    private fun List<Paragraph>.compactText(): List<Paragraph> = fold(mutableListOf<Paragraph>()) { result, item ->
        val previous = result.lastOrNull()
        if (previous is Paragraph.Text && item is Paragraph.Text) {
            result[result.lastIndex] = Paragraph.Text(previous.content + item.content)
        } else result += item
        result
    }.filterNot { it is Paragraph.Text && it.content.trim().isEmpty() }

    private fun value(root: Element, vararg selectors: String): String? = selectors.firstNotNullOfOrNull { selector ->
        root.selectFirst(selector)?.let { element ->
            if (selector == "[data-author]") element.attr("data-author") else element.text()
        }?.normalized()?.ifBlank { null }
    }

    private fun countFrom(root: Element): Int? {
        val candidate = root.selectFirst(".l-listTable__td--count, .c-articleItemRemark__reply span")
            ?.text()
            ?: return null
        return COUNT.find(candidate)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun dateFrom(root: Element): Long? {
        val raw = root.select(".l-navigation .o-fNotes, .l-toolBar .o-fNotes, .l-articleAuthor__tag .o-fNotes")
            .firstNotNullOfOrNull { DATE.find(it.text())?.value }
            ?: root.select("time[datetime], time, .c-listTableTd__date")
                .filter { it.closest("article") == null }
                .firstNotNullOfOrNull { element ->
                    val candidate = element.attr("datetime").ifBlank { element.text() }
                    DATE.find(candidate)?.value
                }
        return parseDate(raw)
    }

    private fun lastActivityFrom(row: Element): Long? = row
        .select(".l-listTable__td--time .o-fNotes, .c-articleItemRemark__wAuto span")
        .lastOrNull { DATE.containsMatchIn(it.text()) }
        ?.text()
        ?.let(::parseDate)

    private fun articleFrom(page: Element): Element? = page.selectFirst(
        ".l-articlePage__publish article[id^=article_], .l-mainArticle__container article.l-publishArea",
    )

    private fun parseDate(value: String?): Long? {
        val normalized = DATE.find(value.orEmpty())?.value ?: return null
        return DATE_FORMATS.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDateTime.parse(normalized, formatter).atZone(TAIPEI).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    private fun Element.isPromoted(): Boolean {
        val structuralMarkers = classNames().map { it.lowercase() } + attr("data-type").lowercase()
        val hasPromotedStructure = structuralMarkers.any { marker ->
            marker.contains("sponsor") || marker.contains("advert")
        }
        val hasPromotedBadge = select(".c-listTableTd__attach .o-hashtag").any { badge ->
            badge.text().normalized() in PROMOTED_BADGES
        }
        return hasPromotedStructure || hasPromotedBadge
    }

    private fun Element.isSticky(): Boolean {
        val hasStickyStructure = classNames().any { className ->
            className.lowercase() in STICKY_CLASSES
        }
        val hasStickyBadge = select(".c-listTableTd__attach .o-hashtag").any { badge ->
            badge.text().normalized() == "置頂"
        }
        return hasStickyStructure || hasStickyBadge
    }

    private fun videoSite(url: String): Paragraph.VideoInfo.Site =
        if (isYoutube(url)) Paragraph.VideoInfo.Site.YOUTUBE else Paragraph.VideoInfo.Site.OTHER

    private fun isYoutube(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun String.normalized(): String = trim().replace(WHITESPACE, " ")

    private companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
        val ARTICLE_ID = Regex("article_(\\d+)")
        val NAME_ID = Regex("name_(\\d+)")
        val DIGITS = Regex("\\d+")
        val COUNT = Regex("(\\d+)")
        val DATE = Regex("\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?")
        val DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
        )
        val PROMOTED_BADGES = setOf("廣告", "贊助", "跨區精選")
        val STICKY_CLASSES = setOf("sticky", "pinned", "is-sticky", "is-pinned")
        val WHITESPACE = Regex("\\s+")
    }
}
