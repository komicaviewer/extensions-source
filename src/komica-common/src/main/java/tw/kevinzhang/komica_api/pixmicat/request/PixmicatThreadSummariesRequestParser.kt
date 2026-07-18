package tw.kevinzhang.komica_api.pixmicat.request

import okhttp3.HttpUrl
import okhttp3.Request
import tw.kevinzhang.komica_api.pixmicat.removeFilename

class PixmicatThreadSummariesRequestParser {
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