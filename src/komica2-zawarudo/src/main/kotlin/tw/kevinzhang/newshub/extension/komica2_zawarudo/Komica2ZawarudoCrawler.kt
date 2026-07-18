package tw.kevinzhang.newshub.extension.komica2_zawarudo

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.komica_api.ParseException
import tw.kevinzhang.komica_api.model.KImageInfo
import tw.kevinzhang.komica_api.model.KLink
import tw.kevinzhang.komica_api.model.KParagraph
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.model.KQuote
import tw.kevinzhang.komica_api.model.KReplyTo
import tw.kevinzhang.komica_api.model.KText
import tw.kevinzhang.komica_api.model.KVideoInfo
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class Komica2ZawarudoCrawler {
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

    fun parseBoard(body: ResponseBody, request: Request): List<KPost> {
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

    fun parseThread(body: ResponseBody, request: Request): List<KPost> {
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
        parsed.forEachIndexed { index, post ->
            post.replies = if (index == 0) {
                parsed.size - 1
            } else {
                parsed.count { candidate ->
                    candidate.content.filterIsInstance<KReplyTo>().any { it.targetId == post.id }
                }
            }
            post.content.filterIsInstance<KReplyTo>().forEach { reply ->
                reply.preview = byId[reply.targetId]
                    ?.content
                    ?.filterIsInstance<KText>()
                    ?.firstOrNull { it.content.isNotBlank() }
                    ?.content
                    ?.trim()
                    ?.take(MAX_REPLY_PREVIEW_LENGTH)
            }
        }
        return parsed
    }

    private fun parsePost(
        post: Element,
        container: Element,
        threadUrl: String,
        pageUrl: HttpUrl,
    ): KPost {
        val id = parsePostId(post)
        val content = buildList {
            addAll(parseParagraphs(post.selectFirst("div.body"), pageUrl))
            addAll(parseAttachments(post, container, pageUrl))
        }
        val subject = post.selectFirst("span.subject")?.text()?.trim().orEmpty()
        val fallbackTitle = content.filterIsInstance<KText>()
            .joinToString(" ") { it.content }
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_FALLBACK_TITLE_LENGTH)

        return KPost(
            id = id,
            url = "$threadUrl#$id",
            title = subject.ifBlank { fallbackTitle },
            createdAt = parseTimestamp(post.selectFirst("time")?.attr("datetime")),
            poster = post.selectFirst("span.name")?.text()?.trim().orEmpty(),
            visits = 0,
            replies = 0,
            readAt = 0,
            content = content,
        )
    }

    private fun parseParagraphs(body: Element?, pageUrl: HttpUrl): List<KParagraph> {
        if (body == null) return emptyList()
        return body.childNodes().flatMap { node -> parseNode(node, pageUrl) }
    }

    private fun parseNode(node: Node, pageUrl: HttpUrl): List<KParagraph> = when (node) {
        is TextNode -> parseText(node.text())
        is Element -> when {
            node.tagName() == "br" -> listOf(KText(""))
            node.tagName() == "a" -> parseAnchor(node, pageUrl)
            node.hasClass("quote") -> parseQuote(node.text())
            else -> node.childNodes().flatMap { child -> parseNode(child, pageUrl) }
        }
        else -> emptyList()
    }

    private fun parseAnchor(anchor: Element, pageUrl: HttpUrl): List<KParagraph> {
        val label = anchor.text().trim()
        val replyId = REPLY_PATTERN.matchEntire(label)?.groupValues?.get(1)
        if (replyId != null) return listOf(KReplyTo(replyId))

        val href = anchor.absUrl("href").ifBlank {
            resolve(pageUrl, anchor.attr("href"))
        }
        return when {
            href.isBlank() -> parseText(label)
            href.isVideoUrl() -> listOf(KVideoInfo(href))
            else -> listOf(KLink(href))
        }
    }

    private fun parseQuote(text: String): List<KParagraph> {
        val trimmed = text.trim()
        val replyId = REPLY_PATTERN.matchEntire(trimmed)?.groupValues?.get(1)
        return if (replyId != null) {
            listOf(KReplyTo(replyId))
        } else if (trimmed.isNotEmpty()) {
            listOf(KQuote(trimmed.removePrefix(">").trimStart()))
        } else {
            emptyList()
        }
    }

    private fun parseText(text: String): List<KParagraph> {
        if (text.isBlank()) return emptyList()
        return listOf(KText(text))
    }

    private fun parseAttachments(
        post: Element,
        container: Element,
        pageUrl: HttpUrl,
    ): List<KParagraph> {
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
            if (raw.isVideoUrl()) return@mapNotNull KVideoInfo(raw)

            val image = link.selectFirst("img.post-image, img")
            val thumb = image?.absUrl("src")?.ifBlank {
                resolve(pageUrl, image.attr("src"))
            }
            KImageInfo(thumb = thumb?.takeIf { it.isNotBlank() }, raw = raw)
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
