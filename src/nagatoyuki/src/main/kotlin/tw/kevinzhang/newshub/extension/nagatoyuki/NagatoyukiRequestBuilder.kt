package tw.kevinzhang.newshub.extension.nagatoyuki

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** URL rules shared by the Vichan-derived boards in this one extension. */
internal object NagatoyukiRequestBuilder {
    fun summaries(boardUrl: String, page: Int): Request = Request.Builder()
        .url(summaryUrl(boardUrl.toHttpUrl(), page))
        .build()

    fun thread(threadUrl: String): Request = Request.Builder().url(threadUrl).build()

    fun summaryUrl(boardUrl: HttpUrl, page: Int): HttpUrl {
        require(page >= 1) { "page must be at least one" }
        val base = boardUrl.toString().trimEnd('/')
        return if (page == 1) base.toHttpUrl() else "$base/$page.html".toHttpUrl()
    }

    fun threadUrl(boardUrl: String, postId: String): String =
        "${boardUrl.trimEnd('/')}/res/$postId.html"
}
