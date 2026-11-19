package tw.kevinzhang.newshub.extension.twocat.komica2

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Test double for 2cat's single-board session: the last activated board owns every decoy path,
 * and an expired session serves an unrelated public board.
 */
internal class FakeTwocat(
    private val titles: Map<String, String> = mapOf(
        "hanime" to "動畫裏",
        "toy" to "玩具裏",
        "futa" to "偽娘裏",
        "know" to "裏知識",
        "gifura" to "短片裏",
    ),
) : Interceptor {
    var decoy = "/granblue/"
    var shellIframeSrc: String? = null
    var shellStatus = 200
    var alwaysMismatch = false
    var pages: (slug: String, path: String) -> String? = { _, _ -> null }

    val entryReferers = mutableListOf<String?>()
    val requests = mutableListOf<String>()
    var entryCount = 0
        private set
    private var current: String? = null

    fun expire() = synchronized(this) { current = null }

    val client: OkHttpClient get() = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response = synchronized(this) {
        val request = chain.request()
        val url = request.url
        requests += url.toString()
        val path = url.encodedPath
        val slug = Regex("^/~([a-z]+)/$").find(path)?.groupValues?.get(1)
        return when {
            url.host == "2cat.uk" && slug != null -> {
                entryCount++
                entryReferers += request.header("Referer")
                if (shellStatus != 200) return respond(request, shellStatus, "")
                current = slug
                val src = shellIframeSrc ?: decoy
                respond(request, 200, "<html><body><iframe allowTransparency='1' src='$src' frameborder='0' id='main'></iframe></body></html>")
            }
            url.host == "2cat.uk" -> {
                val active = current
                if (alwaysMismatch || active == null || !path.startsWith(decoy)) {
                    respond(request, 200, "<html><head><title>Granblue Fantasy</title></head></html>")
                } else {
                    val body = pages(active, url.toString())
                        ?: "<html><head><title>${titles.getValue(active)} Pixmicat!</title></head></html>"
                    respond(request, 200, body)
                }
            }
            else -> respond(request, 404, "")
        }
    }

    private fun respond(
        request: okhttp3.Request,
        code: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .body(body.toResponseBody())
        .build()
}
