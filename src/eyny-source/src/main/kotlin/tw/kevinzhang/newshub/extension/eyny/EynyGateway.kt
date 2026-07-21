package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ru.gildor.coroutines.okhttp.await
import java.io.IOException

internal const val EYNY_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

/** Handles only EYNY's documented browser-work challenge and safe in-site redirects. */
internal class EynyGateway(client: OkHttpClient) {
    private var client = withoutRedirects(client)

    @Volatile var activeHost: String = "eyny.com"
        private set
    private val challengeMutex = Mutex()

    fun updateClient(value: OkHttpClient) { client = withoutRedirects(value) }

    suspend fun get(canonicalUrl: String): String {
        var requestUrl = EynyUrlPolicy.requestUrl(canonicalUrl, activeHost)
        var solved = 0
        var redirects = 0
        repeat(MAX_EXCHANGES) {
            val response = client.newCall(Request.Builder().url(requestUrl).header("User-Agent", EYNY_USER_AGENT).build()).await()
            response.use {
                val body = it.body?.string().orEmpty()
                val location = it.header("Location")
                if (it.isRedirect && location != null) {
                    redirects++
                    if (redirects > MAX_REDIRECTS) throw IOException("Too many EYNY redirects")
                    val target = EynyUrlPolicy.resolve(requestUrl, location) ?: throw IOException("Unsafe EYNY redirect")
                    activeHost = requireNotNull(EynyUrlPolicy.trusted(target)).host
                    requestUrl = target
                    return@repeat
                }
                if (!it.isSuccessful) throw IOException("EYNY HTTP ${it.code}")
                val challenge = try {
                    EynyChallengeSolver.parse(body)
                } catch (error: IllegalArgumentException) {
                    throw IOException("EYNY challenge exceeds local safety bounds", error)
                }
                if (challenge != null) {
                    if (solved >= MAX_CHALLENGES) throw IOException("Too many EYNY challenges")
                    solveChallenge(requireNotNull(EynyUrlPolicy.trusted(requestUrl)).host, challenge)
                    solved++
                    requestUrl = EynyUrlPolicy.requestUrl(canonicalUrl, activeHost)
                    return@repeat
                }
                activeHost = advertisedHost(body, requestUrl)
                    ?: requireNotNull(EynyUrlPolicy.trusted(requestUrl)).host
                return body
            }
        }
        throw IOException("Too many EYNY redirects")
    }

    private suspend fun solveChallenge(host: String, challenge: EynyChallenge) = challengeMutex.withLock {
        // A concurrent request may already have completed the exact challenge.
        val nonce = EynyChallengeSolver.solve(challenge) ?: throw IOException("EYNY challenge exceeds local safety bounds")
        val expires = System.currentTimeMillis() + 86_400_000L
        val values = listOf(
            "${challenge.cookiePrefix}_n" to nonce.toString(),
            "${challenge.cookiePrefix}_ts" to challenge.timestamp,
            "${challenge.cookiePrefix}_ch" to challenge.challenge,
        )
        client.cookieJar.saveFromResponse(
            "https://$host/".toHttpUrl(),
            values.flatMap { (name, value) ->
                listOf(
                    hostCookie(name, value, host, expires),
                    domainCookie(name, value, expires),
                )
            },
        )
    }

    /**
     * WebView's CookieManager omits Domain metadata when exporting cookies. The host app must
     * consequently import them as host-only cookies. Refresh both identities so an old host-only
     * proof can never precede a newer shared-domain proof with a different value.
     */
    private fun hostCookie(name: String, value: String, host: String, expires: Long): Cookie =
        cookieBuilder(name, value, expires).hostOnlyDomain(host).build()

    private fun domainCookie(name: String, value: String, expires: Long): Cookie =
        cookieBuilder(name, value, expires).domain(COOKIE_DOMAIN).build()

    private fun cookieBuilder(name: String, value: String, expires: Long): Cookie.Builder =
        Cookie.Builder().name(name).value(value).path("/").expiresAt(expires)

    private fun advertisedHost(body: String, requestUrl: String): String? = Jsoup.parse(body, requestUrl)
        .selectFirst("base[href]")
        ?.absUrl("href")
        ?.let(EynyUrlPolicy::trusted)
        ?.host

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_CHALLENGES = 2
        const val MAX_EXCHANGES = MAX_REDIRECTS + MAX_CHALLENGES + 1
        const val COOKIE_DOMAIN = "eyny.com"

        fun withoutRedirects(client: OkHttpClient): OkHttpClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
