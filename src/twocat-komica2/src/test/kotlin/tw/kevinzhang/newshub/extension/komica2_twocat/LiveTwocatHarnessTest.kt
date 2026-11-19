package tw.kevinzhang.newshub.extension.twocat.komica2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tw.kevinzhang.extension_api.AuthState
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceNetwork
import tw.kevinzhang.extension_api.SourceNetworkRequest
import tw.kevinzhang.extension_api.SourceNetworkResponse
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live check against 2cat. Runs only with LIVE_2CAT=1. The network double mirrors the Host broker:
 * per-request authorization from the reviewed contract, cookies only for credentialed rules,
 * same-origin redirects followed in the "Host", cross-origin redirects handed back, Set-Cookie hidden.
 */
class LiveTwocatHarnessTest {
    private data class Rule(val hosts: Set<String>, val prefixes: List<String>, val credentialed: Boolean)

    private val rules: List<Rule> by lazy {
        val contract = JsonParser.parseString(File("../../source-host-contracts.json").readText()).asJsonObject
        val source = contract.getAsJsonArray("sources").map { it.asJsonObject }
            .single { it["id"].asString == Komica2TwocatBoards.SOURCE_ID }
        source.getAsJsonObject("surfaces").getAsJsonObject("request").getAsJsonArray("rules").map {
            val rule = it as JsonObject
            Rule(
                rule.getAsJsonArray("exactHttpsHosts").map { h -> h.asString }.toSet(),
                rule.getAsJsonArray("pathPrefixes").map { p -> p.asString },
                rule["credentialed"].asBoolean,
            )
        }
    }

    private val jarCookies = mutableListOf<Cookie>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(jarCookies) {
            jarCookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain } }
            jarCookies += cookies.filter { it.expiresAt > System.currentTimeMillis() }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(jarCookies) {
            jarCookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        }
    }
    val log = mutableListOf<String>()

    private fun authorize(url: HttpUrl): Rule {
        require(url.isHttps) { "HOST_POLICY scheme" }
        val matches = rules.filter { r -> url.host in r.hosts && r.prefixes.any { url.encodedPath.startsWith(it) } }
        require(matches.size == 1) { "HOST_POLICY ${url.host}${url.encodedPath}" }
        return matches.single()
    }

    private val broker = SourceNetwork { request ->
        require(request.method == "GET")
        require(request.headers.keys.none { it.lowercase() in setOf("cookie", "authorization", "host") })
        val initial = request.url.toHttpUrl()
        val rule = authorize(initial)
        val transport = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .cookieJar(if (rule.credentialed) jar else CookieJar.NO_COOKIES)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        var current = initial
        repeat(6) {
            val response = transport.newCall(
                Request.Builder().url(current).apply { request.headers.forEach { (k, v) -> header(k, v) } }.build(),
            ).execute()
            response.use {
                val code = it.code
                val location = it.header("Location")
                synchronized(log) { log += "$code ${current.host}${current.encodedPath}${current.query?.let { q -> "?$q" } ?: ""}" }
                val headers = it.headers.names().filterNot { n -> n.equals("set-cookie", true) }
                    .associateWith { n -> it.header(n).orEmpty() }
                val body = it.body?.bytes() ?: ByteArray(0)
                if (code !in setOf(301, 302, 303, 307, 308) || location == null) {
                    return@SourceNetwork SourceNetworkResponse(code, headers, body)
                }
                val next = current.resolve(location) ?: error("bad redirect")
                if (next.scheme != current.scheme || next.host != current.host || next.port != current.port) {
                    return@SourceNetwork SourceNetworkResponse(code, headers, body)
                }
                require(authorize(next).credentialed == rule.credentialed) { "HOST_POLICY credential boundary" }
                current = next
            }
        }
        error("too many redirects")
    }

    private val runtime = object : SourceRuntime {
        override val network = broker
        override val authentication = object : AuthenticationSession {
            override val state = MutableStateFlow(AuthState.SignedOut)
            override fun markExpired() = Unit
        }
    }

    @Test
    fun liveFlow() = runBlocking {
        assumeTrue(System.getenv("LIVE_2CAT") == "1")
        val source = Komica2TwocatSource().apply { onAttach(runtime) }
        val boards = source.getBoardPage(BoardPageRequest(pageSize = 50)).boards
        assertEquals(7, boards.size)

        try { runFlow(source, boards) } finally {
            println("LIVE cookies=" + synchronized(jarCookies) { jarCookies.map { "${it.domain}:${it.name}" } })
            synchronized(log) { log.forEach { println("LIVE req $it") } }
        }
    }

    private suspend fun runFlow(source: Komica2TwocatSource, boards: List<tw.kevinzhang.extension_api.model.Board>) {
        val order = listOf("~hanime", "~toy", "~hanime", "~futa", "~know", "~gifura", "touhoux", "hiso")
        for (key in order) {
            val board = boards.single { key in it.url }
            val page1 = source.getThreadSummaries(board, 1)
            val page2 = source.getThreadSummaries(board, 2)
            assertTrue("${board.name} page1 empty", page1.isNotEmpty())
            assertTrue("${board.name} page2 same as page1", page1.first().id != page2.firstOrNull()?.id)
            val thread = source.getThread(page1.first())
            assertTrue("${board.name} thread empty", thread.posts.isNotEmpty())
            assertEquals("${board.name} head post", page1.first().id.substringAfter("res="), thread.posts.first().id)
            println(
                "LIVE ${board.name}: p1=${page1.size} p2=${page2.size} id=${page1.first().id} " +
                    "posts=${thread.posts.size} replies=${page1.first().commentCount} web=${source.getWebUrl(page1.first())}",
            )
        }
    }
}
