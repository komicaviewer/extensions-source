package tw.kevinzhang.newshub.extension.wtako

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Builds Pixmicat endpoints without relying on any other extension's URL conventions. */
internal object WtakoRequestBuilder {
    fun boardPage(boardUrl: String, page: Int): Request {
        require(page >= 0) { "page must be non-negative" }
        val endpoint = pixmicatEndpoint(WtakoUrlPolicy.canonicalize(boardUrl.toHttpUrl())).newBuilder()
            .removeAllQueryParameters("page_num")
            .apply { if (page > 1) addQueryParameter("page_num", page.toString()) }
            .build()
        return Request.Builder().url(endpoint).build()
    }

    fun thread(threadUrl: String): Request = Request.Builder()
        .url(WtakoUrlPolicy.canonicalize(threadUrl.toHttpUrl()))
        .build()

    fun thread(boardUrl: String, postId: String): Request = Request.Builder()
        .url(
            pixmicatEndpoint(WtakoUrlPolicy.canonicalize(boardUrl.toHttpUrl())).newBuilder()
                .addQueryParameter("res", postId)
                .build(),
        )
        .build()

    fun threadUrl(boardUrl: String, postId: String): String = thread(boardUrl, postId).url.toString()

    private fun pixmicatEndpoint(board: HttpUrl): HttpUrl {
        val normalized = board.newBuilder().apply {
            if (!board.encodedPath.endsWith("/")) addPathSegment("")
        }.build()
        return if (normalized.pathSegments.lastOrNull() == "pixmicat.php") normalized
        else normalized.resolve("pixmicat.php")!!
    }
}
