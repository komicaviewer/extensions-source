package tw.kevinzhang.newshub.extension.wtako

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.komica_api.model.KImageInfo
import tw.kevinzhang.komica_api.model.KLink
import tw.kevinzhang.komica_api.model.KParagraph
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.model.KPostBuilder
import tw.kevinzhang.komica_api.model.KQuote
import tw.kevinzhang.komica_api.model.KReplyTo
import tw.kevinzhang.komica_api.model.KText
import tw.kevinzhang.komica_api.model.KVideoInfo
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parser local to the Wtako extension.  All target boards run Pixmicat, but their
 * markup differs enough (grid cards, reply wrappers and URL forms) to keep this
 * implementation independent from the other extension modules.
 */
internal class WtakoParser {
    fun parseSummaries(html: String, boardUrl: String): List<KPost> {
        val document = Jsoup.parse(html, boardUrl)
        return document.select("div.threadpost[id^=r]").map { post ->
            parsePost(post, boardUrl, threadUrl(boardUrl, postId(post, null))).copy(
                replies = replyCount(post),
            )
        }
    }

    fun parseThread(html: String, threadUrl: String): List<KPost> {
        val document = Jsoup.parse(html, threadUrl)
        val original = document.selectFirst("div.threadpost[id^=r]") ?: return emptyList()
        val posts = buildList {
            add(parsePost(original, threadUrl, threadUrl))
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
                    candidate.content.filterIsInstance<KReplyTo>().any { it.targetId == post.id }
                }
            }
        }
        addReplyPreviews(posts)
        return posts
    }

    private fun parsePost(element: Element, baseUrl: String, canonicalUrl: String): KPost {
        val id = postId(element, canonicalUrl)
        val body = element.selectFirst("div.quote")
        val content = buildList {
            if (body != null) addAll(paragraphs(body, baseUrl))
            addAll(media(element, baseUrl))
        }
        return KPostBuilder()
            .setPostId(id)
            .setUrl(canonicalUrl)
            .setTitle(element.selectFirst("span.title")?.text()?.trim().orEmpty())
            .setPoster(element.selectFirst("span.name")?.text()?.trim().orEmpty())
            .setCreatedAt(parseDate(element.text()))
            .setContent(content)
            .build()
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

    private fun paragraphs(root: Element, baseUrl: String): List<KParagraph> = buildList {
        root.childNodes().forEach { appendNode(it, baseUrl, this) }
    }

    private fun appendNode(node: Node, baseUrl: String, into: MutableList<KParagraph>) {
        when (node) {
            is TextNode -> {
                val text = node.text()
                if (text.isNotBlank()) into += if (text.trimStart().startsWith(">")) KQuote(text.trim()) else KText(text)
            }
            is Element -> when {
                node.tagName() == "br" -> into += KText("\n")
                node.hasClass("resquote") || node.hasClass("qlink") -> {
                    val link = if (node.tagName() == "a") node else node.selectFirst("a.qlink")
                    val target = link?.let { postIdFromText(it.text()) }
                    if (target != null) into += KReplyTo(target)
                    else node.childNodes().forEach { appendNode(it, baseUrl, into) }
                }
                node.tagName() == "a" -> {
                    val href = absoluteUrl(baseUrl, node.attr("href"))
                    val target = postIdFromText(node.text())
                    if (node.hasClass("qlink") && target != null) into += KReplyTo(target)
                    else if (href != null) into += KLink(href)
                    else if (node.text().isNotBlank()) into += KText(node.text())
                }
                else -> node.childNodes().forEach { appendNode(it, baseUrl, into) }
            }
        }
    }

    private fun media(post: Element, baseUrl: String): List<KParagraph> = buildList {
        val seen = mutableSetOf<String>()
        post.select("a[href]").forEach { anchor ->
            val raw = absoluteUrl(baseUrl, anchor.attr("href")) ?: return@forEach
            val image = anchor.selectFirst("img.img, img[src], img[data-original]")
            when {
                isVideo(raw) && seen.add(raw) -> add(KVideoInfo(raw))
                image != null && isImage(raw) && seen.add(raw) -> {
                    val thumb = absoluteUrl(baseUrl, image.attr("data-original").ifBlank { image.attr("src") })
                    add(KImageInfo(thumb, raw))
                }
            }
        }
    }

    private fun addReplyPreviews(posts: List<KPost>) {
        val byId = posts.associateBy { it.id }
        posts.forEach { post ->
            post.content.filterIsInstance<KReplyTo>().forEach { reference ->
                reference.preview = byId[reference.targetId]?.content
                    ?.filterIsInstance<KText>()
                    ?.firstOrNull { it.content.isNotBlank() }
                    ?.content?.trim()?.take(80)
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
        return directoryBase.resolve(candidate)?.toString()
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
