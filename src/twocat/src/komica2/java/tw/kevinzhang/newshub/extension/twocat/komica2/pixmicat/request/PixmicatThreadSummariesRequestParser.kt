package tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request

import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadSummariesRequestBuilder

import okhttp3.HttpUrl
import okhttp3.Request
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.removeFilename

internal class PixmicatThreadSummariesRequestParser {
    private lateinit var req: Request

    fun req(req: Request): PixmicatThreadSummariesRequestParser {
        this.req = req
        return this
    }

    fun baseUrl(): HttpUrl {
        return req.url.newBuilder()
            .removeFilename()
            .removeAllQueryParameters("page_num")
            .build()
    }
}