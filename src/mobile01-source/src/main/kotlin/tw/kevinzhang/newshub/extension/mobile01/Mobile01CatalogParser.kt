package tw.kevinzhang.newshub.extension.mobile01

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import tw.kevinzhang.extension_api.model.Board

internal data class Mobile01CatalogDocument(val boards: List<Board>, val categoryUrls: List<String>)

/** Follow directory links only; never crawl thread links or trading areas. */
internal class Mobile01CatalogParser {
    fun parse(html: String, baseUrl: String): Mobile01CatalogDocument {
        val document = Jsoup.parse(html, baseUrl)
        val boards = linkedMapOf<Int, Board>()
        val categories = linkedSetOf<String>()
        document.select("a[href]").forEach { link ->
            val name = link.text().trim().ifBlank { link.attr("title").trim() }
            if (name.isBlank() || EXCLUDED.containsMatchIn(name)) return@forEach
            val section = link.closest("section, .l-forum, .l-forumList, .c-forum")
            val category = section?.selectFirst("h2, h3, .c-forumTitle")?.text()?.trim().orEmpty()
            if (EXCLUDED.containsMatchIn(category)) return@forEach
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@forEach
            if (!url.isHttps || url.host != "www.mobile01.com" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return@forEach
            when (url.encodedPath) {
                "/topiclist.php" -> {
                    val id = Mobile01UrlPolicy.boardId(url.toString()) ?: return@forEach
                    boards.putIfAbsent(id, Board(Mobile01BoardCatalog.SOURCE_ID, Mobile01UrlPolicy.boardUrl(id), name, category.ifBlank { null }))
                }
                "/forumlist.php" -> if (url.queryParameter("f")?.toIntOrNull()?.let { it > 0 } == true) {
                    categories += url.newBuilder().query(null).addQueryParameter("f", url.queryParameter("f")).build().toString()
                }
            }
        }
        return Mobile01CatalogDocument(boards.values.toList(), categories.toList())
    }
    companion object { private val EXCLUDED = Regex("交易|二手|商城|市集|拍賣|VIP", RegexOption.IGNORE_CASE) }
}
