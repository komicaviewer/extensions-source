package tw.kevinzhang.newshub.extension.ptt

import okhttp3.Request

internal object PttRequestBuilder {
    fun popularBoards(): Request = request(PttUrlPolicy.popularBoardsUrl())

    fun boardPage(boardName: String, pageIndex: Int?): Request = Request.Builder()
        .url(
            if (pageIndex == null) PttUrlPolicy.boardUrl(boardName)
            else "https://www.ptt.cc/bbs/$boardName/index$pageIndex.html"
        )
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "zh-TW,zh;q=0.9")
        .build()

    fun thread(articleUrl: String): Request = Request.Builder()
        .url(requireNotNull(PttUrlPolicy.articleUrl(articleUrl)) { "Untrusted PTT article URL" })
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "zh-TW,zh;q=0.9")
        .build()

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "zh-TW,zh;q=0.9")
        .build()

    private const val USER_AGENT = "NewsHub PTT extension/1.0"
}
