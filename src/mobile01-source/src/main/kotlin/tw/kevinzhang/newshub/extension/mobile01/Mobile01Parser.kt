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
        val rows = listingRows(document)
        if (rows.isEmpty()) throw Mobile01PageStructureException("listing")
        return rows.mapNotNull { row ->
            if (listingPage > 1 && row.isSticky()) return@mapNotNull null
            val link = row.select("a[href]").firstNotNullOfOrNull { anchor ->
                Mobile01UrlPolicy.resolveThread(boardUrl, anchor.attr("href"))?.let { anchor to it }
            } ?: return@mapNotNull null
            val (anchor, thread) = link
            if (thread.boardId != boardId || row.isPromoted()) return@mapNotNull null
            val title = anchor.text().normalized().ifBlank { return@mapNotNull null }
            val preview = row.selectFirst(".l-listTable__td--content, .c-listTable__content, .topic-content, .description")
                ?.let { paragraphs(it, boardUrl) }
                ?.take(4)
                ?: emptyList()
            ThreadSummary(
                sourceId = Mobile01BoardCatalog.SOURCE_ID,
                boardUrl = Mobile01UrlPolicy.boardUrl(boardId),
                // A listing may link to the latest page. The summary must still identify page one.
                id = Mobile01UrlPolicy.threadUrl(thread.boardId, thread.threadId),
                title = title,
                author = value(row, "[data-author]", ".author", ".user", ".name"),
                // Listing order is driven by the last activity, not the original publish time.
                createdAt = dateFrom(row),
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
        return Mobile01ThreadPageResult(posts, nextPageToken(document, thread))
    }

    private fun listingRows(document: org.jsoup.nodes.Document): List<Element> {
        val preferred = document.select(".l-listTable__tr, .c-listTable__row, .topic-list-item, tr")
            .filter { it.select("a[href*=topicdetail.php]").isNotEmpty() }
        return if (preferred.isNotEmpty()) preferred else {
            document.select("a[href*=topicdetail.php]").mapNotNull { anchor ->
                anchor.closest(".l-listTable__tr, .c-listTable__row, .topic-list-item, li, tr")
            }
        }
    }

    private fun postElements(document: org.jsoup.nodes.Document): List<Element> {
        val selector = "[data-post-id], [data-article-id], [data-floor], .l-articlePage__publish, .single-post, article"
        val found = document.select(selector).filter { candidate ->
            candidate.selectFirst(".l-articlePage__publish__content, .single-post-content, .article-content, .c-article__content, [data-post-content]") != null ||
                candidate.hasAttr("data-post-id") || candidate.hasAttr("data-article-id") || candidate.hasAttr("data-floor")
        }
        return found.filterNot { parent -> found.any { it !== parent && it.parents().contains(parent) } }
    }

    private fun parsePost(
        element: Element,
        thread: Mobile01ThreadUrl,
        sourceIconUrl: String?,
    ): Post? {
        if (element.isPromoted()) return null
        val contentRoot = element.selectFirst(
            ".l-articlePage__publish__content, .single-post-content, .article-content, .c-article__content, [data-post-content]",
        ) ?: return null
        val articleId = element.attr("data-post-id").numericId()
            ?: element.attr("data-article-id").numericId()
            ?: ARTICLE_ID.find(element.id())?.groupValues?.get(1)
            ?: ARTICLE_NUMBER.find(element.text())?.groupValues?.get(1)
            ?: throw Mobile01PageStructureException("thread post identifier")
        val content = paragraphs(contentRoot, thread.url)
        return Post(
            id = articleId,
            author = element.attr("data-author").trim().ifBlank { "" }.takeIf { it.isNotBlank() }
                ?: value(element, ".l-articlePage__publish__author", ".author", ".user", ".name", "a[href*=profile]"),
            createdAt = dateFrom(element),
            thumbnail = content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb,
            content = content,
            comments = emptyList(),
            rawHtml = null,
            sourceIconUrl = sourceIconUrl,
            replyCount = null,
        )
    }

    private fun nextPageToken(document: org.jsoup.nodes.Document, current: Mobile01ThreadUrl): String? = document
        .select("a[href]")
        .firstNotNullOfOrNull { anchor ->
            val isNext = anchor.attr("rel").equals("next", true) ||
                NEXT_TEXT.containsMatchIn(anchor.text().normalized()) ||
                anchor.className().lowercase().contains("next")
            if (!isNext) return@firstNotNullOfOrNull null
            Mobile01UrlPolicy.resolveThread(current.url, anchor.attr("href"))
                ?.takeIf { it.boardId == current.boardId && it.threadId == current.threadId && it.page > current.page }
                ?.url
        }

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
                else -> {
                    replyTarget(node)?.let { into += Paragraph.ReplyTo(it) }
                        ?: node.childNodes().forEach { appendNode(it, baseUrl, into) }
                }
            }
        }
    }

    private fun appendAnchor(anchor: Element, baseUrl: String, into: MutableList<Paragraph>) {
        replyTarget(anchor)?.let { into += Paragraph.ReplyTo(it); return }
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

    private fun replyTarget(element: Element): String? {
        val value = element.attr("data-reply-to").ifBlank { element.attr("data-quote-id") }
        return value.numericId()
    }

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
        val candidate = root.selectFirst("[data-reply-count], .reply-count, .comment-count, .count, .l-listTable__td--count")
            ?.let { it.attr("data-reply-count").ifBlank { it.text() } }
            ?: return null
        return COUNT.find(candidate)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun dateFrom(root: Element): Long? {
        val raw = root.selectFirst("time[datetime]")?.attr("datetime")
            ?: root.selectFirst("time, [data-time], .date, .time, .l-listTable__td--date")
                ?.let { it.attr("data-time").ifBlank { it.text() } }
            ?: DATE.find(root.text())?.value
        return parseDate(raw)
    }

    private fun parseDate(value: String?): Long? {
        val normalized = DATE.find(value.orEmpty())?.value ?: return null
        return DATE_FORMATS.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDateTime.parse(normalized, formatter).atZone(TAIPEI).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    private fun Element.isPromoted(): Boolean {
        val marker = "${className()} ${attr("data-type")} ${text()}".lowercase()
        return listOf("sponsor", "advert", "廣告", "贊助", "跨區精選").any(marker::contains)
    }

    private fun Element.isSticky(): Boolean {
        val marker = "${className()} ${text()}".lowercase()
        return listOf("sticky", "pin", "置頂").any(marker::contains)
    }

    private fun videoSite(url: String): Paragraph.VideoInfo.Site =
        if (isYoutube(url)) Paragraph.VideoInfo.Site.YOUTUBE else Paragraph.VideoInfo.Site.OTHER

    private fun isYoutube(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun String.normalized(): String = trim().replace(WHITESPACE, " ")

    private fun String.numericId(): String? = trim().takeIf { it.matches(NUMERIC_ID) }

    private companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
        val ARTICLE_ID = Regex("(?:article|post)[_-]?(\\d+)", RegexOption.IGNORE_CASE)
        val ARTICLE_NUMBER = Regex("文章編號\\s*[：:]\\s*(\\d+)")
        val NUMERIC_ID = Regex("\\d+")
        val COUNT = Regex("(\\d+)")
        val DATE = Regex("\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?")
        val DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        )
        val NEXT_TEXT = Regex("(?:下一頁|下頁|next)", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
    }
}
