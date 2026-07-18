package tw.kevinzhang.newshub.extension.akraft

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tw.kevinzhang.komica_api.model.KImageInfo
import tw.kevinzhang.komica_api.model.KLink
import tw.kevinzhang.komica_api.model.KParagraph
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.model.KQuote
import tw.kevinzhang.komica_api.model.KReplyTo
import tw.kevinzhang.komica_api.model.KText
import tw.kevinzhang.komica_api.model.KVideoInfo
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parser for Akraft's own SSR markup.  It intentionally lives in this APK:
 * Akraft does not use the Pixmicat/Tinyboard markup used by other extensions.
 */
internal class AkraftParser {
    fun parseSummaries(html: String, boardUrl: String): List<KPost> {
        val document = Jsoup.parse(html, boardUrl)
        return document.select(THREAD_CARD_SELECTOR).mapNotNull { card ->
            val replyCount = card.children()
                .firstOrNull { it.hasClass("items-center") && it.hasClass("pt-4") }
                ?.select("div.scroll-mt-20[id]")
                ?.size
                ?: 0
            parsePost(
                card,
                boardUrl,
                replies = replyCount,
                isThreadRoot = true,
            )
        }
    }

    fun parseThread(html: String, threadUrl: String): List<KPost> {
        val document = Jsoup.parse(html, threadUrl)
        val root = document.selectFirst(THREAD_CARD_SELECTOR) ?: return emptyList()
        val replies = document.select("div.scroll-mt-20[id]")
            .filter { it !== root }
            .mapNotNull { parsePost(it, threadUrl, replies = 0, isThreadRoot = false) }

        val original = parsePost(root, threadUrl, replies.size, isThreadRoot = true)
        return listOfNotNull(original) + replies
    }

    private fun parsePost(
        container: Element,
        pageUrl: String,
        replies: Int,
        isThreadRoot: Boolean,
    ): KPost? {
        val id = container.id().takeIf { it.isNotBlank() } ?: return null
        val title = if (isThreadRoot) {
            container.selectFirst("h3")?.text()?.trim().orEmpty()
        } else {
            ""
        }
        val metadata = container.selectFirst(".flex.flex-wrap.items-center.gap-2.text-sm.text-gray-500")
        val author = metadata?.selectFirst("span.font-semibold")?.text()?.trim().orEmpty()
        val createdAt = metadata?.text()?.let(::parseTimestamp) ?: 0L
        val postUrl = if (isThreadRoot) {
            container.selectFirst("h3 a[href]")?.let { absoluteUrl(pageUrl, it.attr("href")) } ?: pageUrl
        } else {
            "$pageUrl#$id"
        }

        return KPost(
            id = id,
            url = postUrl,
            title = title,
            createdAt = createdAt,
            poster = author,
            visits = 0,
            replies = replies,
            readAt = 0,
            content = parseContent(container, pageUrl),
        )
    }

    private fun parseContent(container: Element, pageUrl: String): List<KParagraph> {
        val result = mutableListOf<KParagraph>()
        val contentRoot = if (container.hasClass("rounded-lg")) {
            container.children().firstOrNull { it.hasClass("p-6") && it.hasClass("pt-3") }
        } else {
            container.children().firstOrNull { it.hasClass("mt-2") }
                ?: container.children()
                    .flatMap { it.children() }
                    .firstOrNull { it.hasClass("mt-2") }
        } ?: return emptyList()
        // Akraft renders post content into prose blocks.  Restricting to these
        // blocks excludes hidden reply/report forms embedded in the card markup.
        contentRoot.select(".prose").forEach { prose ->
            prose.children().forEach { child -> addContentElement(child, pageUrl, result) }
        }
        // Uploaded images and YouTube embeds are siblings of the prose block.
        contentRoot.select("img").forEach { image ->
            val raw = image.closest("a[href]")?.attr("href")?.let { absoluteUrl(pageUrl, it) }
                ?: absoluteUrl(pageUrl, image.attr("src"))
            if (raw.isNotBlank()) {
                val thumb = absoluteUrl(pageUrl, image.attr("src")).takeIf { it != raw }
                result += KImageInfo(thumb = thumb, raw = raw)
            }
        }
        contentRoot.select("iframe[src]").forEach { frame ->
            absoluteUrl(pageUrl, frame.attr("src")).takeIf { it.isNotBlank() }?.let { result += KVideoInfo(it) }
        }
        return result.distinct()
    }

    private fun addContentElement(element: Element, pageUrl: String, result: MutableList<KParagraph>) {
        when (element.tagName()) {
            "blockquote" -> element.text().trim().takeIf { it.isNotBlank() }?.let { result += KQuote(it) }
            "img" -> {
                val raw = element.closest("a[href]")?.attr("href")?.let { absoluteUrl(pageUrl, it) }
                    ?: absoluteUrl(pageUrl, element.attr("src"))
                if (raw.isNotBlank()) result += KImageInfo(raw = raw)
            }
            "iframe" -> absoluteUrl(pageUrl, element.attr("src")).takeIf { it.isNotBlank() }
                ?.let { result += KVideoInfo(it) }
            else -> {
                val text = element.text().trim()
                val replyId = REPLY_PATTERN.matchEntire(text)?.groupValues?.get(1)
                when {
                    replyId != null -> result += KReplyTo(replyId)
                    text.startsWith(">") -> result += KQuote(text.removePrefix("> ").trim())
                    text.isNotBlank() -> result += KText(text)
                }
                element.select("a[href]").forEach { link ->
                    absoluteUrl(pageUrl, link.attr("href")).takeIf { it.isNotBlank() }?.let { result += KLink(it) }
                }
            }
        }
    }

    private fun absoluteUrl(baseUrl: String, value: String): String {
        if (value.isBlank()) return ""
        return baseUrl.toHttpUrl().resolve(value)?.toString() ?: value
    }

    private fun parseTimestamp(metadata: String): Long {
        val match = TIMESTAMP_PATTERN.find(metadata) ?: return 0L
        return runCatching {
            LocalDateTime.parse(match.value, DATE_FORMAT)
                .atZone(TAIPEI)
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    private companion object {
        const val THREAD_CARD_SELECTOR =
            "div.rounded-lg.border.bg-card.text-card-foreground.shadow-sm.mb-6.overflow-hidden.scroll-mt-20[id]"
        val REPLY_PATTERN = Regex("^>>\\s*([A-Za-z0-9_-]+)$")
        val TIMESTAMP_PATTERN = Regex("\\d{4}/\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
