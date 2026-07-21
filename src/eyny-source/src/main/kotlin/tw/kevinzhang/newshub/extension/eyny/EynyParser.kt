package tw.kevinzhang.newshub.extension.eyny

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class EynyCatalog(val categories: List<BoardCategory>, val boards: List<EynyCatalogBoard>)
internal data class EynyCatalogBoard(val categoryId: String, val board: Board)
internal data class EynyThreadPageResult(val posts: List<Post>, val nextToken: String?)

internal class EynyParser {
    fun parseCatalog(html: String): EynyCatalog {
        val document = Jsoup.parse(html, "https://eyny.com/")
        val gids = document.select("a[href*=forum.php?gid]").mapNotNull { link ->
            val id = Regex("(?:[?&]gid=)(\\d+)").find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val name = link.attr("title").clean()
                .ifBlank { link.text().clean() }
                .ifBlank { link.selectFirst("img[alt]")?.attr("alt").orEmpty().clean() }
                .ifBlank { CATEGORY_NAMES[id].orEmpty() }
            id to name.ifBlank { id }
        }.toMap()
        val categoryIds = document.select("td[id^=category_]")
            .map { it.attr("id").removePrefix("category_") }
            .filter(String::isNotBlank)
            .distinct()
        val groups = document.select("td[id^=category_]").flatMap { container ->
            val categoryId = container.attr("id").removePrefix("category_")
            container.select("a[href]").mapNotNull { a ->
                val board = EynyUrlPolicy.resolve("https://eyny.com/", a.attr("href"))?.let(EynyUrlPolicy::board) ?: return@mapNotNull null
                EynyCatalogBoard(categoryId.ifBlank { "all" }, Board(EynySource.SOURCE_ID, EynyUrlPolicy.canonicalBoard(board.fid), a.text().clean(), a.parent()?.text()?.clean()?.takeIf { it != a.text().clean() }))
            }
        }.filter { it.board.name.isNotBlank() }.distinctBy { it.board.url }
        val categories = categoryIds.map { id -> BoardCategory(id, gids[id].orEmpty().ifBlank { CATEGORY_NAMES[id].orEmpty() }.ifBlank { id }) }
        return EynyCatalog(categories, groups)
    }

    fun parseCategoryBoards(html: String, category: BoardCategory): List<EynyCatalogBoard> {
        val document = Jsoup.parse(html, EynyUrlPolicy.canonicalCategory(category.id))
        return document.select("#ct .fl_tb a[href]").mapNotNull { link ->
            val name = link.text().clean().ifBlank { return@mapNotNull null }
            val board = EynyUrlPolicy.resolve(document.location(), link.attr("href"))?.let(EynyUrlPolicy::board)
                ?: return@mapNotNull null
            EynyCatalogBoard(
                categoryId = category.id,
                board = Board(
                    sourceId = EynySource.SOURCE_ID,
                    url = EynyUrlPolicy.canonicalBoard(board.fid),
                    name = name,
                    description = link.parent()?.parent()?.text()?.clean()?.takeIf { it != name },
                ),
            )
        }.distinctBy { it.board.url }
    }

    fun parseSummaries(html: String, board: Board, icon: String?): List<ThreadSummary> {
        val document = Jsoup.parse(html, board.url)
        if (document.selectFirst("#threadlist") == null && document.select("tbody[id^=normalthread], tbody[id^=stickthread]").isEmpty()) {
            throw EynyUnavailableException("EYNY board page is unavailable for this session")
        }
        return document.select("tbody[id^=normalthread]").mapNotNull { row ->
            if (row.className().contains("stick", true)) return@mapNotNull null
            val a = row.selectFirst("a.xst[href]") ?: return@mapNotNull null
            val resolved = EynyUrlPolicy.resolveThread(document.location(), a.attr("href")) ?: return@mapNotNull null
            val url = resolved
            val title = a.text().clean().ifBlank { return@mapNotNull null }
            val preview = row.select(".p_pre_td img").mapNotNull { image(it, document.location()) }
            ThreadSummary(EynySource.SOURCE_ID, EynyUrlPolicy.canonicalBoard(requireNotNull(EynyUrlPolicy.board(board.url)).fid), EynyUrlPolicy.canonicalThread(url.tid, extra = url.extra), title,
                row.selectFirst(".by cite, .by a, .author")?.text()?.clean(), date(row.text()), count(row.selectFirst(".num a, .num")?.text()), preview.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.raw, preview.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb, preview, icon, count(row.selectFirst(".num a, .num")?.text()))
        }.distinctBy { it.id }
    }

    fun parseThreadPage(html: String, threadUrl: String, icon: String?): EynyThreadPageResult {
        val safe = requireNotNull(EynyUrlPolicy.thread(threadUrl))
        val document = Jsoup.parse(html, safe.url)
        if (requiresAccountSecurity(document)) {
            throw EynyLockedException("EYNY requires account security setup before viewing threads")
        }
        if (requiresLogin(document)) throw EynyLockedException()
        val posts = document.select("table[id^=pid]").mapNotNull { table ->
            val id = table.id().removePrefix("pid").filter(Char::isDigit).takeIf(String::isNotBlank) ?: return@mapNotNull null
            val contentRoot = table.selectFirst("td[id^=postmessage_], .pcb .t_f") ?: return@mapNotNull null
            val content = paragraphs(contentRoot, document.location())
            val timestamp = table.selectFirst("em[id^=authorposton] span[title]")?.attr("title")
                ?: table.selectFirst("em[id^=authorposton]")?.text()
            Post(id, table.selectFirst(".authi a.xw1, .pls .xw1, .authi")?.text()?.clean(), timestamp?.let(::date), content.filterIsInstance<Paragraph.ImageInfo>().firstOrNull()?.thumb, content, emptyList(), null, icon, null)
        }.distinctBy { it.id }
        if (posts.isEmpty()) throw EynyUnavailableException("EYNY thread page contains no posts")
        val next = document.select("a[href]").firstNotNullOfOrNull { a ->
            val nextish = a.text().clean() in setOf("下一頁", "下頁", "Next") || a.attr("rel").equals("next", true)
            EynyUrlPolicy.resolveThread(safe.url, a.attr("href"))?.takeIf { nextish && it.tid == safe.tid && it.page > safe.page }?.url
        }
        return EynyThreadPageResult(posts, next)
    }

    fun signedIn(html: String): Boolean = Regex("discuz_uid\\s*=\\s*['\\\"]?(?!0(?:['\\\"]|\\s*[,;]))(\\d+)").containsMatchIn(html)
    private fun requiresAccountSecurity(document: org.jsoup.nodes.Document): Boolean {
        if (!document.body().classNames().contains("pg_spacecp")) return false
        val title = document.title()
        val body = document.body().text()
        return "密碼安全" in title || "密碼安全" in body || "加強密碼" in body
    }
    private fun requiresLogin(document: org.jsoup.nodes.Document): Boolean {
        if (document.select("td[id^=postmessage_] .locked, td[id^=postmessage_].locked").isNotEmpty()) return true
        val body = document.body().text()
        return listOf("您沒有權限訪問", "登入後才能瀏覽完整內容", "登錄後才能瀏覽完整內容").any(body::contains) && document.select("table[id^=pid]").isEmpty()
    }
    private fun paragraphs(root: Element, base: String): List<Paragraph> = buildList { root.childNodes().forEach { node(it, base, this) } }.compact()
    private fun node(value: Node, base: String, into: MutableList<Paragraph>) {
        when (value) {
        is TextNode -> { value.text().clean().takeIf(String::isNotBlank)?.let { into += Paragraph.Text(it) } }
        is Element -> when (value.tagName().lowercase()) {
            "script", "style", "button", "form" -> Unit
            "blockquote" -> value.text().clean().takeIf(String::isNotBlank)?.let { into += Paragraph.Quote(it) }
            "br", "p", "div", "li" -> { value.childNodes().forEach { child -> node(child, base, into) }; into += Paragraph.Text("\n") }
            "img" -> image(value, base)?.let(into::add)
            "a" -> {
                val href = EynyUrlPolicy.safeContent(value.absUrl("href"))
                val reply = Regex("(?:pid|postid)=(\\d+)").find(value.attr("href"))?.groupValues?.get(1)
                when {
                    reply != null -> {
                        into += Paragraph.ReplyTo(reply)
                        value.selectFirst("img")?.let { image(it, base)?.let(into::add) }
                    }
                    href != null && youtube(href) -> into += Paragraph.VideoInfo(href, Paragraph.VideoInfo.Site.YOUTUBE)
                    href != null -> into += Paragraph.Link(href)
                    else -> value.childNodes().forEach { child -> node(child, base, into) }
                }
            }
            "iframe", "video" -> EynyUrlPolicy.safeContent(value.absUrl("src"))?.let { into += Paragraph.VideoInfo(it) }
            else -> value.childNodes().forEach { child -> node(child, base, into) }
        }
        else -> Unit
        }
    }
    private fun image(e: Element, base: String): Paragraph.ImageInfo? { val raw = EynyUrlPolicy.safeContent(e.absUrl("zoomfile").ifBlank { e.absUrl("file") }.ifBlank { e.absUrl("data-original") }.ifBlank { e.absUrl("src") }) ?: return null; return Paragraph.ImageInfo(EynyUrlPolicy.safeContent(e.absUrl("src")), raw) }
    private fun List<Paragraph>.compact() = fold(mutableListOf<Paragraph>()) { out, item -> if (item is Paragraph.Text && item.content == "\n" && out.lastOrNull() is Paragraph.Text && (out.last() as Paragraph.Text).content == "\n") out else { out += item; out } }.filterNot { it is Paragraph.Text && it.content.trim().isEmpty() }
    private fun String.clean() = trim().replace(Regex("\\s+"), " ")
    private fun count(value: String?): Int? = Regex("\\d+").find(value.orEmpty())?.value?.toIntOrNull()
    private fun date(value: String): Long? = Regex("(\\d{4}-\\d{1,2}-\\d{1,2})\\s+(?:(上午|下午|AM|PM)\\s*)?(\\d{1,2}:\\d{2})(?:\\s*(上午|下午|AM|PM))?", RegexOption.IGNORE_CASE).find(value)?.let { match ->
        val raw = "${match.groupValues[1]} ${match.groupValues[3]}"
        val marker = match.groupValues[2].ifBlank { match.groupValues[4] }.uppercase()
        listOf("yyyy-MM-dd HH:mm", "yyyy-M-d HH:mm").firstNotNullOfOrNull { pattern ->
            runCatching {
                var parsed = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(pattern))
                if ((marker == "下午" || marker == "PM") && parsed.hour < 12) parsed = parsed.plusHours(12)
                if ((marker == "上午" || marker == "AM") && parsed.hour == 12) parsed = parsed.minusHours(12)
                parsed.atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
    private fun youtube(url: String) = url.contains("youtube.com") || url.contains("youtu.be")

    private companion object {
        val CATEGORY_NAMES = mapOf(
            "1722" to "BL/GL",
            "333" to "成人話題",
            "334" to "博彩娛樂",
        )
    }
}

internal class EynyLockedException(message: String = "EYNY requires login to view this content") : java.io.IOException(message)
internal class EynyUnavailableException(message: String) : java.io.IOException(message)
