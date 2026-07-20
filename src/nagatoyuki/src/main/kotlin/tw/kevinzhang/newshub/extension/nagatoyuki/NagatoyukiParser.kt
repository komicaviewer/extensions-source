package tw.kevinzhang.newshub.extension.nagatoyuki

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements
import tw.kevinzhang.extension_api.model.Paragraph
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Parser deliberately kept inside this APK: the supported sites can change independently. */
internal data class NagatoyukiParsedPost(
    val id: String,
    val url: String,
    val title: String,
    val createdAt: Long,
    val author: String,
    var replies: Int,
    var content: List<Paragraph>,
)

internal class NagatoyukiParser {
    fun parseSummaries(html: String, boardUrl: String): List<NagatoyukiParsedPost> =
        Jsoup.parse(html, boardUrl).select("div.thread").mapNotNull { thread ->
            thread.selectFirst(".post.op")?.let { op ->
                parsePost(op, NagatoyukiRequestBuilder.threadUrl(boardUrl, postId(op)))
                    .copy(replies = replyCount(thread))
            }
        }

    fun parseThread(html: String, threadUrl: String): List<NagatoyukiParsedPost> {
        val document = Jsoup.parse(html, threadUrl)
        val thread = document.selectFirst("div.thread") ?: return emptyList()
        val posts = thread.select(".post.op, .post.reply").map { post ->
            parsePost(post, "$threadUrl#${postId(post)}")
        }
        val byId = posts.associateBy { it.id }
        posts.forEachIndexed { index, post ->
            post.replies = if (index == 0) {
                posts.size - 1
            } else {
                posts.count { candidate ->
                    candidate.content.filterIsInstance<Paragraph.ReplyTo>().any { it.targetId == post.id }
                }
            }
            post.content = post.content.map { paragraph ->
                if (paragraph !is Paragraph.ReplyTo) return@map paragraph
                paragraph.copy(
                    preview = byId[paragraph.targetId]
                        ?.content
                        ?.filterIsInstance<Paragraph.Text>()
                        ?.firstOrNull { it.content.isNotBlank() }
                        ?.content
                        ?.trim()
                        ?.take(80),
                )
            }
        }
        return posts
    }

    private fun parsePost(post: Element, url: String): NagatoyukiParsedPost {
        val intro = post.selectFirst(".intro")
        val postId = postId(post)
        val content = mutableListOf<Paragraph>()
        post.selectFirst(".body")?.childNodes()?.forEach { appendBodyNode(it, content) }
        appendAttachments(post, content)
        val subject = intro?.selectFirst(".subject")?.text().orEmpty()
        return NagatoyukiParsedPost(
            id = postId,
            url = url,
            title = subject.ifBlank {
                    content.filterIsInstance<Paragraph.Text>()
                    .joinToString(" ") { it.content }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(80)
            },
            createdAt = intro?.selectFirst("time")?.let(::parseTime) ?: 0L,
            author = intro?.selectFirst(".name")?.text().orEmpty(),
            replies = 0,
            content = content,
        )
    }

    private fun appendBodyNode(node: Node, output: MutableList<Paragraph>) {
        when (node) {
            is TextNode -> node.text().takeIf { it.isNotEmpty() }?.let { output += Paragraph.Text(it) }
            !is Element -> Unit
            else -> when {
                node.tagName() == "br" -> output += Paragraph.Text("")
                node.tagName() == "a" -> appendLink(node, output)
                node.hasClass("quote") || node.hasClass("resquote") -> {
                    val link = node.selectFirst("a")
                    val target = REPLY_TEXT.matchEntire(node.text().trim())?.groupValues?.get(1)
                    when {
                        link != null -> appendLink(link, output)
                        target != null -> output += Paragraph.ReplyTo(target)
                        else -> output += Paragraph.Quote(node.text().removePrefix(">").trimStart())
                    }
                }
                else -> node.childNodes().forEach { appendBodyNode(it, output) }
            }
        }
    }

    private fun appendLink(link: Element, output: MutableList<Paragraph>) {
        val href = resolve(link, link.attr("href"))
        val target = link.attr("href").substringAfterLast('#', "")
            .removePrefix("q")
            .takeIf { it.all(Char::isDigit) && it.isNotBlank() }
        when {
            target != null -> output += Paragraph.ReplyTo(target)
            href.isNotBlank() -> output += Paragraph.Link(href)
            else -> output += Paragraph.Text(link.text())
        }
    }

    private fun appendAttachments(post: Element, output: MutableList<Paragraph>) {
        val ownFiles = post.select(".files .file").ifEmpty {
            post.previousElementSibling()
                ?.takeIf { it.hasClass("files") }
                ?.select(".file")
                ?: Elements()
        }
        ownFiles.forEach { file ->
            val source = file.selectFirst(".fileinfo a[href]") ?: file.selectFirst("a[href]") ?: return@forEach
            val raw = resolve(source, source.attr("href"))
            val image = file.selectFirst("img.post-image")
            when {
                isVideo(raw) -> output += Paragraph.VideoInfo(raw)
                isImage(raw) -> output += Paragraph.ImageInfo(image?.let { resolve(it, it.attr("data-original").ifBlank { it.attr("src") }) }, raw)
            }
        }
    }

    private fun replyCount(thread: Element): Int {
        val visible = thread.select(".post.reply").size
        val omitted = thread.select(".omitted").sumOf { omittedText ->
            OMITTED_COUNT.findAll(omittedText.text()).sumOf { it.value.toInt() }
        }
        return visible + omitted
    }

    private fun postId(post: Element): String = post.id()
        .substringAfter('_', post.id())
        .ifBlank { error("Post is missing an id: ${post.outerHtml().take(120)}") }

    private fun parseTime(time: Element): Long {
        val value = time.attr("datetime").ifBlank { time.text() }
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(
                    value.take(19),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                ).atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
            }
            .getOrDefault(0L)
    }

    private fun resolve(element: Element, value: String): String =
        element.baseUri().toHttpUrl().resolve(value)?.toString() ?: value

    private fun isImage(url: String) = url.substringBefore('?').lowercase().let {
        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(".bmp")
    }

    private fun isVideo(url: String) = url.substringBefore('?').lowercase().let {
        it.endsWith(".webm") || it.endsWith(".mp4") || it.endsWith(".mov")
    }

    private companion object {
        val REPLY_TEXT = Regex(">>?(?:No\\.)?(\\d+)", RegexOption.IGNORE_CASE)
        val OMITTED_COUNT = Regex("\\d+")
    }
}
