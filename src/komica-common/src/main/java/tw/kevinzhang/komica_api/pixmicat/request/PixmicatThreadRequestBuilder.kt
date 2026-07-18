package tw.kevinzhang.komica_api.pixmicat.request

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.pixmicat.addFilename
import tw.kevinzhang.komica_api.pixmicat.isFile

open class PixmicatThreadRequestBuilder : ThreadRequestBuilder {
    private lateinit var builder: HttpUrl.Builder

    override fun setUrl(url: HttpUrl): PixmicatThreadRequestBuilder {
        this.builder = if (!url.isFile("php")) {
            url.newBuilder().addFilename("pixmicat", "php")
        } else {
            url.newBuilder()
        }
        return this
    }

    fun setBoard(board: Board): PixmicatThreadRequestBuilder {
        setUrl(board.url.toHttpUrl())
        return this
    }

    fun setRes(res: String?): PixmicatThreadRequestBuilder {
        return if(res == null) removeQuery("res")
        else addQuery("res", res)
    }

    private fun addQuery(queryName: String, value: String): PixmicatThreadRequestBuilder {
        if (hasQuery(queryName))
            removeQuery(queryName)
        builder = builder.addQueryParameter(queryName, value)
        return this
    }

    private fun hasQuery(queryName: String): Boolean {
        return builder.build().queryParameter(queryName).isNullOrBlank().not()
    }

    private fun removeQuery(queryName: String): PixmicatThreadRequestBuilder {
        if(hasQuery(queryName))
            builder = builder.removeAllQueryParameters(queryName)
        return this
    }

    fun setFragment(reply: String?): PixmicatThreadRequestBuilder {
        return if(reply == null) removeFragment()
        else addFragment(reply)
    }

    private fun addFragment(value: String): PixmicatThreadRequestBuilder {
        if (hasFragment())
            removeFragment()
        builder = builder.fragment(value)
        return this
    }

    private fun hasFragment(): Boolean {
        return builder.build().fragment.isNullOrBlank().not()
    }

    private fun removeFragment(): PixmicatThreadRequestBuilder {
        if(hasFragment())
            builder = builder.fragment(null)
        return this
    }

    override fun build(): Request {
        return Request.Builder()
            .url(builder.build())
            .build()
    }
}
