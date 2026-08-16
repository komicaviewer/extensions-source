package tw.kevinzhang.newshub.extension.wtako

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Paragraph
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parser local to the Wtako extension.  All target boards run Pixmicat, but their
 * markup differs enough (grid cards, reply wrappers and URL forms) to keep this
 * implementation independent from the other extension modules.
 */
internal data class WtakoParsedPost(
    val id: String,
    val url: String,
    val title: String,
    val createdAt: Long,
    val author: String,
    var replies: Int = 0,
    var content: List<Paragraph>,
)

internal class WtakoParser {
    fun parseSummaries(html: String, boardUrl: String): List<WtakoParsedPost> {
        val document = Jsoup.parse(html, boardUrl)
        return document.select("div.threadpost[id^=r]").map { post ->
            parsePost(post, boardUrl, threadUrl(boardUrl, postId(post, null))).copy(
                replies = replyCount(post),
            )
        }
    }

    fun parseThread(html: String, threadUrl: String): List<WtakoParsedPost> {
        val document = Jsoup.parse(html, threadUrl)
        val original = document.selectFirst("div.threadpost[id^=r]") ?: return emptyList()
        val posts = buildList {
            add(
                parsePost(
                    element = original,
                    baseUrl = threadUrl,
                    canonicalUrl = threadUrl,
                    extraContentRoots = boundedOriginalContentSiblings(original),
                ),
            )
            document.select("div.reply[id^=r]").forEach { reply ->
                val id = postId(reply, null)
                add(parsePost(reply, threadUrl, "$threadUrl#r$id"))
            }
        }
        posts.forEachIndexed { index, post ->
            post.replies = if (index == 0) {
                posts.size - 1
            } else {
                posts.count { candidate ->
                    candidate.content.filterIsInstance<Paragraph.ReplyTo>().any { it.targetId == post.id }
                }
            }
        }
        addReplyPreviews(posts)
        return posts
    }

    private fun parsePost(
        element: Element,
        baseUrl: String,
        canonicalUrl: String,
        extraContentRoots: List<Element> = emptyList(),
    ): WtakoParsedPost {
        val id = postId(element, canonicalUrl)
        val body = element.selectFirst("div.quote")
        val content = buildList {
            if (body != null) addAll(paragraphs(body, baseUrl))
            addAll(media(element, baseUrl))
            extraContentRoots.forEach { root ->
                root.selectFirst("div.quote")?.let { addAll(paragraphs(it, baseUrl)) }
                addAll(media(root, baseUrl))
            }
        }
        return WtakoParsedPost(
            id = id,
            url = canonicalUrl,
            title = element.selectFirst("span.title")?.text()?.trim().orEmpty(),
            author = element.selectFirst("span.name")?.text()?.trim().orEmpty(),
            createdAt = parseDate(element.text()),
            content = content,
        )
    }

    /**
     * Older Pixmicat themes close the OP header before rendering its media and quote. Keep the
     * recovery strictly inside the gap before the first reply or next thread so reply content can
     * never be attributed to the OP.
     */
    private fun boundedOriginalContentSiblings(original: Element): List<Element> = buildList {
        var sibling = original.nextElementSibling()
        while (sibling != null && !sibling.hasClass("reply") && !sibling.hasClass("threadpost")) {
            add(sibling)
            sibling = sibling.nextElementSibling()
        }
    }

    private fun replyCount(original: Element): Int {
        val wrapper = original.parent()
        val omitted = if (wrapper?.hasClass("grid") == true) {
            wrapper.selectFirst("span.warn_txt2")?.text()
        } else {
            var sibling = original.nextElementSibling()
            var warning: String? = null
            while (sibling != null && !sibling.hasClass("threadpost")) {
                warning = sibling.selectFirst("span.warn_txt2")?.text() ?: warning
                sibling = sibling.nextElementSibling()
            }
            warning
        } ?: original.selectFirst("span.warn_txt2")?.text()
        val omittedCount = omitted?.let { REPLY_COUNT.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        var rendered = 0
        var sibling = original.nextElementSibling()
        while (sibling != null && !sibling.hasClass("threadpost")) {
            if (sibling.hasClass("reply")) rendered++
            sibling = sibling.nextElementSibling()
        }
        return omittedCount + rendered
    }

    private fun paragraphs(root: Element, baseUrl: String): List<Paragraph> = buildList {
        root.childNodes().forEach { appendNode(it, baseUrl, this) }
    }

    private fun appendNode(node: Node, baseUrl: String, into: MutableList<Paragraph>) {
        when (node) {
            is TextNode -> {
                val text = node.text()
                if (text.isNotBlank()) into += if (text.trimStart().startsWith(">")) Paragraph.Quote(text.trim()) else Paragraph.Text(text)
            }
            is Element -> when {
                node.tagName() == "br" -> into += Paragraph.Text("\n")
                node.hasClass("resquote") || node.hasClass("qlink") -> {
                    val link = if (node.tagName() == "a") node else node.selectFirst("a.qlink")
                    val target = link?.let { postIdFromText(it.text()) }
                    if (target != null) into += Paragraph.ReplyTo(target)
                    else node.childNodes().forEach { appendNode(it, baseUrl, into) }
                }
                node.tagName() == "a" -> {
                    val href = absoluteUrl(baseUrl, node.attr("href"))
                    val target = postIdFromText(node.text())
                    if (node.hasClass("qlink") && target != null) into += Paragraph.ReplyTo(target)
                    else if (href != null) into += Paragraph.Link(href)
                    else if (node.text().isNotBlank()) into += Paragraph.Text(node.text())
                }
                else -> node.childNodes().forEach { appendNode(it, baseUrl, into) }
            }
        }
    }

    private fun media(post: Element, baseUrl: String): List<Paragraph> = buildList {
        val seen = mutableSetOf<String>()
        post.select("a[href]").forEach { anchor ->
            val raw = absoluteUrl(baseUrl, anchor.attr("href")) ?: return@forEach
            val image = anchor.selectFirst("img.img, img[src], img[data-original]")
            when {
                isVideo(raw) && seen.add(raw) -> add(Paragraph.VideoInfo(raw))
                image != null && isImage(raw) && seen.add(raw) -> {
                    val thumb = absoluteUrl(baseUrl, image.attr("data-original").ifBlank { image.attr("src") })
                    add(Paragraph.ImageInfo(thumb, raw))
                }
            }
        }
    }

    private fun addReplyPreviews(posts: List<WtakoParsedPost>) {
        val byId = posts.associateBy { it.id }
        posts.forEach { post ->
            post.content = post.content.map { paragraph ->
                if (paragraph !is Paragraph.ReplyTo) return@map paragraph
                paragraph.copy(
                    preview = byId[paragraph.targetId]?.content
                        ?.filterIsInstance<Paragraph.Text>()
                        ?.firstOrNull { it.content.isNotBlank() }
                        ?.content?.trim()?.take(80),
                )
            }
        }
    }

    private fun postId(element: Element, fallbackUrl: String?): String =
        element.id().removePrefix("r").ifBlank {
            fallbackUrl?.toHttpUrl()?.fragment?.removePrefix("r")
                ?: fallbackUrl?.toHttpUrl()?.queryParameter("res")
                ?: ""
        }

    private fun threadUrl(boardUrl: String, id: String): String = WtakoRequestBuilder.threadUrl(boardUrl, id)

    private fun parseDate(text: String): Long {
        val match = DATE.find(text) ?: return 0L
        val year = 2000 + match.groupValues[1].toInt()
        return runCatching {
            LocalDateTime.parse(
                "$year/${match.groupValues[2]}/${match.groupValues[3]} ${match.groupValues[4]}:${match.groupValues[5]}",
                DATE_FORMAT,
            ).atZone(TAIPEI).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun postIdFromText(text: String): String? = NO.find(text)?.groupValues?.get(1)

    private fun absoluteUrl(baseUrl: String, candidate: String): String? {
        if (candidate.isBlank() || candidate == "#") return null
        val base = baseUrl.toHttpUrl()
        val directoryBase = if (base.pathSegments.lastOrNull() == "pixmicat.php" || base.encodedPath.endsWith("/")) {
            base
        } else {
            base.newBuilder().addPathSegment("").build()
        }
        return directoryBase.resolve(candidate)?.let(WtakoUrlPolicy::canonicalize)?.toString()
    }

    private fun isImage(url: String) = IMAGE_EXTENSION.containsMatchIn(url.substringBefore('?'))
    private fun isVideo(url: String) = VIDEO_EXTENSION.containsMatchIn(url.substringBefore('?'))

    private companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        val DATE = Regex("\\[(\\d{2})/(\\d{2})/(\\d{2})\\([^)]*\\)(\\d{2}):(\\d{2})")
        val NO = Regex("(?:No\\.)?(\\d+)")
        val REPLY_COUNT = Regex("(?:回應|repl(?:y|ies))\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val IMAGE_EXTENSION = Regex("\\.(?:jpe?g|png|gif|webp|bmp|avif)$", RegexOption.IGNORE_CASE)
        val VIDEO_EXTENSION = Regex("\\.(?:webm|mp4|mov)$", RegexOption.IGNORE_CASE)
    }
}
