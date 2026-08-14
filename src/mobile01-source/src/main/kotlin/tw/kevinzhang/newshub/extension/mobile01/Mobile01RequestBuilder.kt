package tw.kevinzhang.newshub.extension.mobile01

import okhttp3.Request

/** Public document-navigation headers only; no cookies, credentials, or challenge solving. */
internal object Mobile01RequestBuilder {
    fun board(boardId: Int, page: Int): Request = request(Mobile01UrlPolicy.boardUrl(boardId, page.takeIf { it > 1 }))

    fun thread(url: String): Request = request(
        requireNotNull(Mobile01UrlPolicy.thread(url)) { "Untrusted Mobile01 thread URL" }.url,
    )

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36 NewsHub-Mobile01/0.1.3",
        )
        .header("Accept-Language", "zh-TW,zh;q=0.9")
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "none")
        .build()
}
