package tw.kevinzhang.newshub.extension.runtime

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tw.kevinzhang.extension_api.*
import tw.kevinzhang.extension_api.model.*

data class CatalogSnapshot<T>(
    val value: T,
    val version: String = UUID.randomUUID().toString(),
    val updatedAtMillis: Long,
    val coverage: BoardCatalogCoverage,
    val isStale: Boolean = false,
) {
    fun info() = BoardCatalogInfo(BoardCatalogMode.CATALOG, updatedAtMillis, isStale, coverage)
}

class CatalogRefreshBudget(private val timeoutMillis: Long = 60_000L) {
    init { require(timeoutMillis in 1..60_000L) }
    private val count = AtomicInteger()
    private val permits = Semaphore(2)
    private val startedNanos = System.nanoTime()
    suspend fun <T> fetch(block: suspend () -> T): T = permits.withPermit {
        val remaining = timeoutMillis - (System.nanoTime() - startedNanos) / 1_000_000L
        if (remaining <= 0 || count.incrementAndGet() > 50) throw IOException("Catalog refresh budget exceeded")
        try { withTimeout(remaining) { block() } }
        catch (error: TimeoutCancellationException) { throw IOException("Catalog refresh deadline exceeded", error) }
    }
}

/** Process-local full catalog. Never stores credentials or mixes authentication epochs. */
class BoardCatalogSnapshotCache<T>(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var epoch: String? = null
    @Volatile private var snapshot: CatalogSnapshot<T>? = null
    suspend fun get(contextEpoch: String?, refresh: Boolean, loader: suspend (CatalogRefreshBudget) -> CatalogSnapshot<T>): CatalogSnapshot<T> {
        val before = snapshot
        return mutex.withLock {
            // Legacy Hosts cannot signal credential changes. Never reuse their account catalogs.
            // A health request also must not replace another consumer's active snapshot.
            if (contextEpoch == null) return@withLock loadFresh(loader)
            if (epoch != contextEpoch) { snapshot = null; epoch = contextEpoch }
            val cached = snapshot
            if (cached != null && nowMillis() - cached.updatedAtMillis in 0 until 86_400_000L &&
                (!refresh || cached !== before)) return@withLock cached
            try {
                val loaded = loadFresh(loader)
                if (loaded.coverage == BoardCatalogCoverage.PARTIAL && cached?.coverage == BoardCatalogCoverage.AVAILABLE) {
                    cached.copy(isStale = true).also { snapshot = it }
                } else loaded.also { snapshot = it }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val failure = (error as? SourceFailureException)?.failure?.code
                if (error is AuthenticationRequiredException || failure in setOf(SourceFailureCode.AUTH_REQUIRED, SourceFailureCode.AUTH_EXPIRED, SourceFailureCode.ACCESS_CHALLENGE, SourceFailureCode.ACCESS_DENIED, SourceFailureCode.RATE_LIMITED)) {
                    snapshot = null
                    throw error
                }
                cached?.copy(isStale = true)?.also { snapshot = it } ?: throw error
            }
        }
    }
    private suspend fun loadFresh(loader: suspend (CatalogRefreshBudget) -> CatalogSnapshot<T>): CatalogSnapshot<T> =
        try { withTimeout(60_000L) { loader(CatalogRefreshBudget()) } }
        catch (error: TimeoutCancellationException) { throw IOException("Catalog refresh deadline exceeded", error) }
    suspend fun invalidate() = mutex.withLock { snapshot = null }
}
