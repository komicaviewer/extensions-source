package tw.kevinzhang.newshub.extension.hackernews

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import java.io.IOException

internal class HackerNewsApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val gson: Gson = Gson(),
) {
    private val requestSlots = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val cacheMutex = Mutex()
    private val itemCache = LinkedHashMap<Long, CacheEntry>(CACHE_SIZE, 0.75f, true)
    private val inFlight = mutableMapOf<Long, CompletableDeferred<HackerNewsItem?>>()

    suspend fun getFeed(feed: HackerNewsFeed): List<Long> {
        val body = execute(feed.endpoint) ?: throw IOException("Empty Hacker News feed: ${feed.endpoint}")
        return try {
            gson.fromJson<List<Long>?>(body, FEED_TYPE).orEmpty()
        } catch (error: JsonSyntaxException) {
            throw IOException("Invalid Hacker News feed JSON: ${feed.endpoint}", error)
        }
    }

    suspend fun getItems(ids: List<Long>): List<HackerNewsItem> = coroutineScope {
        ids.distinct().map { id -> async { getItem(id) } }.awaitAll().filterNotNull()
    }

    suspend fun getItem(id: Long): HackerNewsItem? {
        require(id > 0) { "Hacker News item id must be positive: $id" }
        val now = System.currentTimeMillis()
        val pending = cacheMutex.withLock {
            itemCache[id]?.takeIf { it.expiresAt > now }?.let { return it.item }
            itemCache.remove(id)
            inFlight[id]?.let { return@withLock PendingFetch(it, false) }
            val deferred = CompletableDeferred<HackerNewsItem?>()
            inFlight[id] = deferred
            PendingFetch(deferred, true)
        }
        if (!pending.owner) return pending.deferred.await()

        return try {
            val item = requestSlots.withPermit { fetchItem(id) }
            cacheMutex.withLock {
                if (item != null) {
                    itemCache[id] = CacheEntry(item, System.currentTimeMillis() + CACHE_TTL_MILLIS)
                    trimCache()
                }
                inFlight.remove(id)
                pending.deferred.complete(item)
            }
            item
        } catch (error: Throwable) {
            cacheMutex.withLock {
                inFlight.remove(id)
                pending.deferred.completeExceptionally(error)
            }
            throw error
        }
    }

    private suspend fun fetchItem(id: Long): HackerNewsItem? {
        val body = execute("item/$id.json") ?: return null
        if (body.trim() == "null") return null
        return try {
            gson.fromJson(body, HackerNewsItem::class.java)
        } catch (error: JsonSyntaxException) {
            throw IOException("Invalid Hacker News item JSON: $id", error)
        }
    }

    private suspend fun execute(relativePath: String): String? {
        val url = baseUrl.resolve(relativePath)
            ?: throw IOException("Invalid Hacker News API path: $relativePath")
        require(url.host == baseUrl.host && url.scheme == baseUrl.scheme) {
            "Hacker News API request escaped configured host"
        }
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
                response.body?.string()?.takeIf(String::isNotBlank)
            }
        }
    }

    private fun trimCache() {
        while (itemCache.size > CACHE_SIZE) {
            val eldest = itemCache.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    private data class CacheEntry(val item: HackerNewsItem, val expiresAt: Long)
    private data class PendingFetch(
        val deferred: CompletableDeferred<HackerNewsItem?>,
        val owner: Boolean,
    )

    private companion object {
        val DEFAULT_BASE_URL = "https://hacker-news.firebaseio.com/v0/".toHttpUrl()
        val FEED_TYPE = object : TypeToken<List<Long>>() {}.type
        const val MAX_CONCURRENT_REQUESTS = 8
        const val CACHE_SIZE = 512
        const val CACHE_TTL_MILLIS = 60_000L
    }
}
