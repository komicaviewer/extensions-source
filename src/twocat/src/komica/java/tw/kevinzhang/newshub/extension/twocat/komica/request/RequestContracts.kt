package tw.kevinzhang.newshub.extension.twocat.komica.request

import okhttp3.HttpUrl
import okhttp3.Request

internal interface ThreadRequestBuilder {
    fun setUrl(url: HttpUrl): ThreadRequestBuilder
    fun build(): Request
}

internal interface ThreadSummariesRequestBuilder {
    fun setUrl(url: HttpUrl): ThreadSummariesRequestBuilder
    fun setPage(page: Int?): ThreadSummariesRequestBuilder
    fun build(): Request
}
