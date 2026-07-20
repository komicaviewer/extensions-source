package tw.kevinzhang.newshub.extension.wtako

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Keeps Wtako URLs on HTTPS when a legacy URL or redirect points back to HTTP. */
internal object WtakoUrlPolicy {
    private val httpsHosts = setOf("rthost.win")

    fun canonicalize(url: String): String = canonicalize(url.toHttpUrl()).toString()

    fun canonicalize(url: HttpUrl): HttpUrl =
        if (url.scheme == "http" && url.host in httpsHosts) {
            url.newBuilder().scheme("https").build()
        } else {
            url
        }

    fun canonicalizeRedirect(requestUrl: HttpUrl, location: String): String? =
        requestUrl.resolve(location)?.let(::canonicalize)?.toString()
}

/** Rewrites only verified Wtako hosts whose HTTPS endpoints redirect back to HTTP. */
internal class WtakoHttpsRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val location = response.header("Location") ?: return response
        val canonical = WtakoUrlPolicy.canonicalizeRedirect(response.request.url, location)
            ?: return response
        if (canonical == location) return response

        return response.newBuilder()
            .header("Location", canonical)
            .build()
    }
}
