package tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.request

import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.request.ThreadSummariesRequestBuilder

import okhttp3.Request


internal class Komica2PixmicatThreadRequestBuilder : PixmicatThreadRequestBuilder() {
    override fun build(): Request {
        val req = super.build()
        return if (req.url.host == "2cat.org") {
            req.newBuilder()
                .addHeader("Referer", "https://komica2.cc/mainmenu2022.html")
                .build()
        } else req
    }
}
