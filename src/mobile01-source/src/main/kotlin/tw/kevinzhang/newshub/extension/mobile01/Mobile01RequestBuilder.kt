package tw.kevinzhang.newshub.extension.mobile01

import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Deliberately modest headers: this extension never impersonates a browser or solves challenges. */
internal object Mobile01RequestBuilder {
    fun catalog(url: String = "https://www.mobile01.com/forumlist.php"): Request {
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps && parsed.host == "www.mobile01.com" && parsed.port == 443 &&
            parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.encodedPath == "/forumlist.php")
        return request(parsed.toString())
    }
    fun board(boardId: Int, page: Int): Request = request(Mobile01UrlPolicy.boardUrl(boardId, page.takeIf { it > 1 }))

    fun thread(url: String): Request = request(
        requireNotNull(Mobile01UrlPolicy.thread(url)) { "Untrusted Mobile01 thread URL" }.url,
    )

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", "NewsHub Mobile01 extension/0.1.1")
        .header("Accept-Language", "zh-TW,zh;q=0.9")
        .header("Accept", "text/html,application/xhtml+xml")
        .build()
}
