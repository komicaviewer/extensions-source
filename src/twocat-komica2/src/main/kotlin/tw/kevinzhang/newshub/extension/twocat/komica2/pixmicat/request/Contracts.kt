package tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request

import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadSummariesRequestBuilder

import okhttp3.HttpUrl
import okhttp3.Request

internal interface ThreadRequestBuilder { fun setUrl(url: HttpUrl): ThreadRequestBuilder; fun build(): Request }
internal interface ThreadSummariesRequestBuilder { fun setUrl(url: HttpUrl): ThreadSummariesRequestBuilder; fun setPage(page: Int?): ThreadSummariesRequestBuilder; fun build(): Request }
