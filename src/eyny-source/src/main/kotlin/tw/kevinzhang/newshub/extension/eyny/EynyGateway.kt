package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ru.gildor.coroutines.okhttp.await
import java.io.IOException
import tw.kevinzhang.extension_api.EynyChallengeProof
import tw.kevinzhang.extension_api.NamedCookieCapability

internal const val EYNY_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

/** Handles only EYNY's documented browser-work challenge and safe in-site redirects. */
internal class EynyGateway() {
    internal constructor(client: OkHttpClient, namedCookies: NamedCookieCapability) : this() {
        updateRuntime(client, namedCookies)
    }

    private lateinit var client: OkHttpClient
    private lateinit var namedCookies: NamedCookieCapability

    @Volatile var activeHost: String = "eyny.com"
        private set
    private val challengeMutex = Mutex()

    fun updateRuntime(value: OkHttpClient, capability: NamedCookieCapability) {
        client = withoutRedirects(value)
        namedCookies = capability
    }

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
        namedCookies.storeEynyChallengeProof(
            EynyChallengeProof(
                host = host,
                cookiePrefix = challenge.cookiePrefix,
                nonce = nonce.toLong(),
                timestamp = challenge.timestamp,
                challenge = challenge.challenge,
            ),
        )
    }

    private fun advertisedHost(body: String, requestUrl: String): String? = Jsoup.parse(body, requestUrl)
        .selectFirst("base[href]")
        ?.absUrl("href")
        ?.let(EynyUrlPolicy::trusted)
        ?.host

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_CHALLENGES = 2
        const val MAX_EXCHANGES = MAX_REDIRECTS + MAX_CHALLENGES + 1
        fun withoutRedirects(client: OkHttpClient): OkHttpClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
