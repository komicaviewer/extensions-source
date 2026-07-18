package tw.kevinzhang.newshub.extension.twocat.request

import okhttp3.HttpUrl
import okhttp3.Request
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.isZeroOrNull

class TwocatRequestBuilder(
    private val baseBoardUrl: HttpUrl? = null,
) : ThreadSummariesRequestBuilder, ThreadRequestBuilder {
    private lateinit var builder: HttpUrl.Builder

    override fun setUrl(url: HttpUrl): TwocatRequestBuilder {
        this.builder = url.newBuilder()
        return this
    }

    fun setRes(res: String?): TwocatRequestBuilder {
        return if (res == null) removeQuery("res")
        else addQuery("res", res)
    }

    private fun addQuery(queryName: String, value: String): TwocatRequestBuilder {
        if (hasQuery(queryName))
            removeQuery(queryName)
        builder = builder.addQueryParameter(queryName, value)
        return this
    }

    private fun hasQuery(queryName: String): Boolean {
        return builder.build().queryParameter(queryName).isNullOrBlank().not()
    }

    private fun removeQuery(queryName: String): TwocatRequestBuilder {
        if (hasQuery(queryName))
            builder = builder.removeAllQueryParameters(queryName)
        return this
    }

    fun setFragment(reply: String?): TwocatRequestBuilder {
        return if (reply == null) removeFragment()
        else addFragment(reply)
    }

    private fun addFragment(value: String): TwocatRequestBuilder {
        if (hasFragment())
            removeFragment()
        builder = builder.fragment(value)
        return this
    }

    private fun hasFragment(): Boolean {
        return builder.build().fragment.isNullOrBlank().not()
    }

    private fun removeFragment(): TwocatRequestBuilder {
        if (hasFragment())
            builder = builder.fragment(null)
        return this
    }

    override fun setPage(page: Int?): TwocatRequestBuilder {
        builder = builder
            .apply {
                if (page.isZeroOrNull()) {
                    removeQuery("page")
                } else {
                    val currentUrl = builder.build()
                    val boardUrl = checkNotNull(baseBoardUrl) {
                        "A base board URL is required when setting a non-zero page"
                    }
                    val extra = currentUrl.pathSegments - boardUrl.pathSegments
                    if (extra.isEmpty()) {
                        addQuery("page", "$page")
                    } else {
                        setQueryParameter("page", "$page")
                    }
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
