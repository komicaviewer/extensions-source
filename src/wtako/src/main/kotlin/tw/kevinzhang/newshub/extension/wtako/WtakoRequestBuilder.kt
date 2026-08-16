package tw.kevinzhang.newshub.extension.wtako

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Builds Pixmicat endpoints without relying on any other extension's URL conventions. */
internal object WtakoRequestBuilder {
    fun boardPage(boardUrl: String, page: Int): Request {
        require(page >= 0) { "page must be non-negative" }
        val board = WtakoUrlPolicy.canonicalize(boardUrl.toHttpUrl())
        val endpoint = boardPageEndpoint(board, page).newBuilder()
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

    private fun boardPageEndpoint(board: HttpUrl, page: Int): HttpUrl {
        // rthost's page-one PHP endpoint currently redirects back to plain HTTP. Request the
        // equivalent static HTTPS page directly so the Host never has to accept a downgrade.
        if (board.host == "rthost.win" && page <= 1) {
            return normalizedBoard(board).resolve("index.htm")!!
        }
        return pixmicatEndpoint(board)
    }

    private fun pixmicatEndpoint(board: HttpUrl): HttpUrl {
        val normalized = normalizedBoard(board)
        return if (normalized.pathSegments.lastOrNull() == "pixmicat.php") normalized
        else normalized.resolve("pixmicat.php")!!
    }

    private fun normalizedBoard(board: HttpUrl): HttpUrl =
        board.newBuilder().apply {
            if (!board.encodedPath.endsWith("/")) addPathSegment("")
        }.build()
}
