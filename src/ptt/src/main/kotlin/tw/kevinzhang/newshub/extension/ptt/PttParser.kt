package tw.kevinzhang.newshub.extension.ptt

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Comment
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
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
            select("div.article-metaline, div.article-metaline-right, div.push, span.f2").remove()
        }
        val content = paragraphs(article, safeArticleUrl)
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

    private fun paragraphs(root: Element, articleUrl: String): List<Paragraph> = buildList {
        root.childNodes().forEach { appendNode(it, articleUrl, this) }
    }.compactText()

    private fun appendNode(node: Node, articleUrl: String, into: MutableList<Paragraph>) {
        when (node) {
            is TextNode -> appendText(node.text(), into)
            is Element -> when (node.tagName()) {
                "br" -> appendText("\n", into)
                "a" -> appendAnchor(node, articleUrl, into)
                "img" -> {
                    val raw = PttUrlPolicy.safeExternalUrl(node.absUrl("src"))
                    if (raw != null && isImage(raw)) into += Paragraph.ImageInfo(raw, raw)
                }
                else -> node.childNodes().forEach { appendNode(it, articleUrl, into) }
            }
        }
    }

    private fun appendAnchor(anchor: Element, articleUrl: String, into: MutableList<Paragraph>) {
        val url = PttUrlPolicy.safeExternalUrl(anchor.absUrl("href"))
        if (url == null) {
            anchor.childNodes().forEach { appendNode(it, articleUrl, into) }
            return
        }
        when {
            isImage(url) -> into += Paragraph.ImageInfo(url, url)
            isYoutube(url) -> into += Paragraph.VideoInfo(url, Paragraph.VideoInfo.Site.YOUTUBE)
            else -> into += Paragraph.Link(url)
        }
    }

    private fun appendText(value: String, into: MutableList<Paragraph>) {
        value.splitToSequence('\n').forEachIndexed { index, line ->
            val normalized = line.trimEnd()
            if (normalized.isNotBlank()) {
                val trimmed = normalized.trimStart()
                into += if (trimmed.startsWith(">")) Paragraph.Quote(trimmed) else Paragraph.Text(normalized)
            }
            if (index < value.count { it == '\n' }) into += Paragraph.Text("\n")
        }
    }

    private fun List<Paragraph>.compactText(): List<Paragraph> = fold(mutableListOf<Paragraph>()) { result, item ->
        val previous = result.lastOrNull()
        if (previous is Paragraph.Text && item is Paragraph.Text) {
            result[result.lastIndex] = Paragraph.Text(previous.content + item.content)
        } else result += item
        result
    }.filterNot { it is Paragraph.Text && it.content.isEmpty() }

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
        fun isImage(url: String) = IMAGE.containsMatchIn(url)
        fun isYoutube(url: String): Boolean {
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
            return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        }
    }
}
