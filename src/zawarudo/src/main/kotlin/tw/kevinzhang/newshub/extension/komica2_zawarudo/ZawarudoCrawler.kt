package tw.kevinzhang.newshub.extension.zawarudo.komica2

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Paragraph
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class ZawarudoCrawler {
    fun boardRequest(boardUrl: String, page: Int): Request {
        val baseUrl = boardUrl.trimEnd('/').toHttpUrl()
        val url = if (page <= FIRST_PAGE) {
            baseUrl.newBuilder().addPathSegment("").build()
        } else {
            baseUrl.newBuilder().addPathSegment("$page.html").build()
        }
        return Request.Builder().url(url).build()
    }

    fun threadRequest(threadUrl: String): Request =
        Request.Builder().url(threadUrl.toHttpUrl()).build()

    fun parseBoard(body: ResponseBody, request: Request): List<ZawarudoParsedPost> {
        val document = Jsoup.parse(body.string(), request.url.toString())
        return document.select("div.thread").mapNotNull { thread ->
            val op = thread.selectFirst("div.post.op") ?: return@mapNotNull null
            val threadUrl = threadUrl(op, request.url)
            parsePost(
                post = op,
                container = thread,
                threadUrl = threadUrl,
                pageUrl = request.url,
            ).copy(replies = parseReplyCount(thread))
        }
    }

    fun parseThread(body: ResponseBody, request: Request): List<ZawarudoParsedPost> {
        val document = Jsoup.parse(body.string(), request.url.toString())
        val thread = document.selectFirst("div.thread")
            ?: throw ParseException("Missing thread container: ${request.url}")
        val posts = thread.select("div.post")
        if (posts.isEmpty()) throw ParseException("Missing posts: ${request.url}")
        val parsed = posts.map { post ->
            parsePost(
                post = post,
                container = thread,
                threadUrl = request.url.toString(),
                pageUrl = request.url,
            )
        }
        val byId = parsed.associateBy { it.id }
        return parsed.mapIndexed { index, post ->
            post.copy(
                replies = if (index == 0) parsed.size - 1 else parsed.count { candidate ->
                    candidate.content.filterIsInstance<Paragraph.ReplyTo>().any { it.targetId == post.id }
                },
                content = post.content.map { paragraph ->
                    if (paragraph is Paragraph.ReplyTo) {
                        paragraph.copy(
                            preview = byId[paragraph.targetId]
                                ?.content
                                ?.filterIsInstance<Paragraph.Text>()
                                ?.firstOrNull { it.content.isNotBlank() }
                                ?.content
                                ?.trim()
                                ?.take(MAX_REPLY_PREVIEW_LENGTH),
                        )
                    } else paragraph
                },
            )
        }
    }

    private fun parsePost(
        post: Element,
        container: Element,
        threadUrl: String,
        pageUrl: HttpUrl,
    ): ZawarudoParsedPost {
        val id = parsePostId(post)
        val content = buildList {
            addAll(parseParagraphs(post.selectFirst("div.body"), pageUrl))
            addAll(parseAttachments(post, container, pageUrl))
        }
        val subject = post.selectFirst("span.subject")?.text()?.trim().orEmpty()
        val fallbackTitle = content.filterIsInstance<Paragraph.Text>()
            .joinToString(" ") { it.content }
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_FALLBACK_TITLE_LENGTH)

        return ZawarudoParsedPost(
            id = id,
            url = "$threadUrl#$id",
            title = subject.ifBlank { fallbackTitle },
            createdAt = parseTimestamp(post.selectFirst("time")?.attr("datetime")),
            poster = post.selectFirst("span.name")?.text()?.trim().orEmpty(),
            replies = 0,
            content = content,
        )
    }

    private fun parseParagraphs(body: Element?, pageUrl: HttpUrl): List<Paragraph> {
        if (body == null) return emptyList()
        return body.childNodes().flatMap { node -> parseNode(node, pageUrl) }
    }

    private fun parseNode(node: Node, pageUrl: HttpUrl): List<Paragraph> = when (node) {
        is TextNode -> parseText(node.text())
        is Element -> when {
            node.tagName() == "br" -> listOf(Paragraph.Text(""))
            node.tagName() == "a" -> parseAnchor(node, pageUrl)
            node.hasClass("quote") -> parseQuote(node.text())
            else -> node.childNodes().flatMap { child -> parseNode(child, pageUrl) }
        }
        else -> emptyList()
    }

    private fun parseAnchor(anchor: Element, pageUrl: HttpUrl): List<Paragraph> {
        val label = anchor.text().trim()
        val replyId = REPLY_PATTERN.matchEntire(label)?.groupValues?.get(1)
        if (replyId != null) return listOf(Paragraph.ReplyTo(replyId))

        val href = anchor.absUrl("href").ifBlank {
            resolve(pageUrl, anchor.attr("href"))
        }
        return when {
            href.isBlank() -> parseText(label)
            href.isVideoUrl() -> listOf(Paragraph.VideoInfo(href))
            else -> listOf(Paragraph.Link(href))
        }
    }

    private fun parseQuote(text: String): List<Paragraph> {
        val trimmed = text.trim()
        val replyId = REPLY_PATTERN.matchEntire(trimmed)?.groupValues?.get(1)
        return if (replyId != null) {
            listOf(Paragraph.ReplyTo(replyId))
        } else if (trimmed.isNotEmpty()) {
            listOf(Paragraph.Quote(trimmed.removePrefix(">").trimStart()))
        } else {
            emptyList()
        }
    }

    private fun parseText(text: String): List<Paragraph> {
        if (text.isBlank()) return emptyList()
        return listOf(Paragraph.Text(text))
    }

    private fun parseAttachments(
        post: Element,
        container: Element,
        pageUrl: HttpUrl,
    ): List<Paragraph> {
        val fileRoots = if (post.hasClass("op")) {
            container.children()
                .filter { it.hasClass("files") }
                .flatMap { it.select("div.file") } + post.select("div.files div.file")
        } else {
            post.select("div.files div.file")
        }

        return fileRoots.distinctBy { it.selectFirst("a[href]")?.attr("href") }.mapNotNull { file ->
            val link = file.selectFirst("a[href]") ?: return@mapNotNull null
            val raw = link.absUrl("href").ifBlank { resolve(pageUrl, link.attr("href")) }
            if (raw.isBlank()) return@mapNotNull null
            if (raw.isVideoUrl()) return@mapNotNull Paragraph.VideoInfo(raw)

            val image = link.selectFirst("img.post-image, img")
            val thumb = image?.absUrl("src")?.ifBlank {
                resolve(pageUrl, image.attr("src"))
            }
            Paragraph.ImageInfo(thumb = thumb?.takeIf { it.isNotBlank() }, raw = raw)
        }
    }

    private fun threadUrl(op: Element, pageUrl: HttpUrl): String {
        val replyLink = op.selectFirst("a[href*=/res/]")
            ?: throw ParseException("Missing reply link for ${op.id()}")
        val resolved = replyLink.absUrl("href").ifBlank {
            resolve(pageUrl, replyLink.attr("href"))
        }
        return resolved.toHttpUrl().newBuilder().fragment(null).build().toString()
    }

    private fun parsePostId(post: Element): String {
        val fromElementId = post.id().substringAfter('_', missingDelimiterValue = "")
        if (fromElementId.isNotBlank()) return fromElementId
        return post.selectFirst("a.post_no[id^=post_no_]")?.id()?.substringAfter("post_no_")
            ?.takeIf { it.isNotBlank() }
            ?: throw ParseException("Missing post id")
    }

    private fun parseReplyCount(thread: Element): Int {
        val visible = thread.select("div.post.reply").size
        val omitted = thread.select("span.omitted")
            .flatMap { OMITTED_COUNT.findAll(it.text()).toList() }
            .sumOf { it.value.toInt() }
        return visible + omitted
    }

    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(value, LOCAL_DATE_TIME_FORMAT)
                    .atZone(ZoneId.of("Asia/Taipei"))
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrDefault(0L)
    }

    private fun resolve(base: HttpUrl, value: String): String {
        if (value.isBlank()) return ""
        return base.resolve(value)?.toString().orEmpty()
    }

    private fun String.isVideoUrl(): Boolean {
        val path = substringBefore('?').substringBefore('#').lowercase()
        return VIDEO_EXTENSIONS.any(path::endsWith)
    }

    private companion object {
        const val FIRST_PAGE = 1
        const val MAX_FALLBACK_TITLE_LENGTH = 80
        const val MAX_REPLY_PREVIEW_LENGTH = 80
        val WHITESPACE = Regex("\\s+")
        val REPLY_PATTERN = Regex(">>?(?:No\\.)?(\\d+)", RegexOption.IGNORE_CASE)
        val OMITTED_COUNT = Regex("\\d+")
        val VIDEO_EXTENSIONS = setOf(".webm", ".mp4", ".m4v")
        val LOCAL_DATE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
