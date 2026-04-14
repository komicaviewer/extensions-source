package tw.kevinzhang.newshub.extension.sora.request

import okhttp3.HttpUrl
import okhttp3.Request
import tw.kevinzhang.newshub.extension.sora.removeFilename

class SoraThreadSummariesRequestParser {
    private lateinit var req: Request

    fun req(req: Request): SoraThreadSummariesRequestParser {
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