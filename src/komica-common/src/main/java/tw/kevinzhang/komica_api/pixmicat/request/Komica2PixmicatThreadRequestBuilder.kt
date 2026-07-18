package tw.kevinzhang.komica_api.pixmicat.request

import okhttp3.Request


class Komica2PixmicatThreadRequestBuilder : PixmicatThreadRequestBuilder() {
    override fun build(): Request {
        val req = super.build()
        return if (req.url.host == "2cat.org") {
            req.newBuilder()
                .addHeader("Referer", "https://komica2.cc/mainmenu2022.html")
                .build()
        } else req
    }
}
