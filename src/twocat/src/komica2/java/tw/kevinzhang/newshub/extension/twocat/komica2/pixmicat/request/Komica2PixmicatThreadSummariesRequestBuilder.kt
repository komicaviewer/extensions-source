package tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request

import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadSummariesRequestBuilder

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.isZeroOrNull

internal class Komica2PixmicatThreadSummariesRequestBuilder : ThreadSummariesRequestBuilder {
    private lateinit var builder: HttpUrl.Builder
    private var boardUrl: HttpUrl? = null

    override fun setUrl(url: HttpUrl): Komica2PixmicatThreadSummariesRequestBuilder {
        builder = url.newBuilder()
        return this
    }

    fun setBoard(board: Board): Komica2PixmicatThreadSummariesRequestBuilder {
        boardUrl = board.url.toHttpUrl()
        return setUrl(boardUrl!!)
    }

    override fun setPage(page: Int?): Komica2PixmicatThreadSummariesRequestBuilder {
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
