package tw.kevinzhang.newshub.extension.mobile01

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Network requests are intentionally restricted to Mobile01's canonical HTTPS origin. */
internal object Mobile01UrlPolicy {
    private const val HOST = "www.mobile01.com"

    fun boardUrl(boardId: Int, page: Int? = null): String {
        require(boardId > 0) { "Invalid Mobile01 board id" }
        require(page == null || page > 0) { "Mobile01 listing pages are 1-based" }
        return buildUrl("topiclist.php", mapOf("f" to boardId.toString()) +
            (page?.let { mapOf("p" to it.toString()) } ?: emptyMap()))
    }

    fun threadUrl(boardId: Int, threadId: String, page: Int? = null): String {
        require(boardId > 0 && threadId.toLongOrNull()?.let { it > 0 } == true) { "Invalid Mobile01 thread" }
        require(page == null || page > 0) { "Mobile01 thread pages are 1-based" }
        return buildUrl("topicdetail.php", mapOf("f" to boardId.toString(), "t" to threadId) +
            (page?.let { mapOf("p" to it.toString()) } ?: emptyMap()))
    }

    fun boardId(value: String): Int? = trusted(value)
        ?.takeIf { it.encodedPath == "/topiclist.php" }
        ?.queryParameter("f")?.toIntOrNull()?.takeIf { it > 0 }

    fun thread(value: String): Mobile01ThreadUrl? {
        val url = trusted(value)?.takeIf { it.encodedPath == "/topicdetail.php" } ?: return null
        val boardId = url.queryParameter("f")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val threadId = url.queryParameter("t")?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return null
        val page = url.queryParameter("p")?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        return Mobile01ThreadUrl(boardId, threadId, page, canonicalThreadUrl(boardId, threadId, page))
    }

    fun pageToken(value: String): String? = thread(value)?.takeIf { it.page > 1 }?.url

    fun resolveThread(baseUrl: String, href: String): Mobile01ThreadUrl? =
        trusted(baseUrl)?.resolve(href)?.toString()?.let(::thread)

    fun safeContentUrl(value: String): String? = value.toHttpUrlOrNull()
        ?.takeIf { it.scheme == "https" }
        ?.toString()

    fun trusted(value: String): HttpUrl? = value.toHttpUrlOrNull()?.takeIf {
        it.scheme == "https" && it.host == HOST && it.port == 443 &&
            (it.encodedPath == "/topiclist.php" || it.encodedPath == "/topicdetail.php")
    }

    private fun canonicalThreadUrl(boardId: Int, threadId: String, page: Int): String =
        threadUrl(boardId, threadId, page.takeIf { it > 1 })

    private fun buildUrl(path: String, parameters: Map<String, String>): String = HttpUrl.Builder()
        .scheme("https")
        .host(HOST)
        .addPathSegment(path)
        .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
        .build()
        .toString()
}

internal data class Mobile01ThreadUrl(
    val boardId: Int,
    val threadId: String,
    val page: Int,
    val url: String,
)
