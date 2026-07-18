package tw.kevinzhang.newshub.extension.komica2_sora.request

import okhttp3.Request
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadRequestBuilder


class Komica2SoraThreadRequestBuilder : SoraThreadRequestBuilder() {
    override fun build(): Request {
        val req = super.build()
        return if (req.url.host == "2cat.org") {
            req.newBuilder()
                .addHeader("Referer", "https://komica2.cc/mainmenu2022.html")
                .build()
        } else req
    }
}
