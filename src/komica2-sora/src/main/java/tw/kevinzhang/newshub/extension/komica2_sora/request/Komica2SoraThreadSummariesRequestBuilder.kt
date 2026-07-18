package tw.kevinzhang.newshub.extension.komica2_sora.request

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.isZeroOrNull

class Komica2SoraThreadSummariesRequestBuilder : ThreadSummariesRequestBuilder {
    private lateinit var builder: HttpUrl.Builder
    private var boardUrl: HttpUrl? = null

    override fun setUrl(url: HttpUrl): Komica2SoraThreadSummariesRequestBuilder {
        builder = url.newBuilder()
        return this
    }

    fun setBoard(board: Board): Komica2SoraThreadSummariesRequestBuilder {
        boardUrl = board.url.toHttpUrl()
        return setUrl(boardUrl!!)
    }

    override fun setPage(page: Int?): Komica2SoraThreadSummariesRequestBuilder {
        val board = boardUrl
        if (board?.host == "2cat.org") {
            val requestUrl = board.newBuilder().apply {
                if (page == null || page <= 1) {
                    removeAllQueryParameters("page_num")
                } else {
                    setQueryParameter("page_num", (page - 1).toString())
                }
            }.build()
            return setUrl(requestUrl)
        }

        builder = builder.apply {
            if (page.isZeroOrNull() || page == 1) {
                removeAllQueryParameters("page_num")
            } else {
                setQueryParameter("page_num", page.toString())
            }
        }
        return this
    }

    override fun build(): Request {
        val req = Request.Builder().url(builder.build()).build()
        return if (req.url.host == "2cat.org") {
            req.newBuilder()
                .addHeader("Referer", "https://komica2.cc/mainmenu2022.html")
                .build()
        } else req
    }
}
