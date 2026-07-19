package tw.kevinzhang.newshub.extension.sora.komica.request

import okhttp3.HttpUrl
import okhttp3.Request
import tw.kevinzhang.newshub.extension.sora.komica.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica.isZeroOrNull

internal open class SoraThreadSummariesRequestBuilder : ThreadSummariesRequestBuilder {
    private lateinit var builder: HttpUrl.Builder

    override fun setUrl(url: HttpUrl): SoraThreadSummariesRequestBuilder {
        this.builder = url.newBuilder()
        return this
    }

    // 只有 sora board 才有 page，sora thread 沒有
    // page 從 1 開始
    override fun setPage(page: Int?): SoraThreadSummariesRequestBuilder {
        builder = builder
            .apply {
                if (page.isZeroOrNull() || page == 1) {
                    removeAllQueryParameters("page_num")
                } else {
                    setQueryParameter("page_num", page.toString())
                }
            }
        return this
    }

    override fun build(): Request {
        return Request.Builder()
            .url(builder.build())
            .build()
    }
}
