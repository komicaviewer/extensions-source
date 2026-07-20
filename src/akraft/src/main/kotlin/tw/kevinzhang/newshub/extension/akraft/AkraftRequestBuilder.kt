package tw.kevinzhang.newshub.extension.akraft

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Akraft has a conventional server-rendered board endpoint: `?page=N`. */
internal object AkraftRequestBuilder {
    private const val USER_AGENT = "NewsHub Akraft extension/1.0"

    fun board(boardUrl: String, page: Int): Request {
        require(page >= 1) { "Akraft page must be at least 1" }
        val url = boardUrl.toHttpUrl().newBuilder()
            .removeAllQueryParameters("page")
            .addQueryParameter("page", page.toString())
            .build()
        return request(url)
    }

    fun thread(threadUrl: String): Request = request(threadUrl.toHttpUrl())

    private fun request(url: okhttp3.HttpUrl): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml")
        .build()
}
