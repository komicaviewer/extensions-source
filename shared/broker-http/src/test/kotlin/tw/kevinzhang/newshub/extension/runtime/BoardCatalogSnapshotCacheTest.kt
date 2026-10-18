package tw.kevinzhang.newshub.extension.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.extension_api.model.*
import java.io.IOException

class BoardCatalogSnapshotCacheTest {
    @Test fun `null epoch health request cannot replace a current account snapshot`() = runBlocking {
        val cache = BoardCatalogSnapshotCache<String> { 100L }
        cache.get("a", false) { CatalogSnapshot("account", "v1", 100, BoardCatalogCoverage.AVAILABLE) }
        cache.get(null, false) { CatalogSnapshot("health", "v2", 100, BoardCatalogCoverage.AVAILABLE) }
        val retained = cache.get("a", false) { throw AssertionError("Must preserve active account snapshot") }
        assertEquals("v1", retained.version)
    }
    @Test fun `deadline cancels an active request and prevents another request`() = runBlocking {
        val budget = CatalogRefreshBudget(timeoutMillis = 10)
        var calls = 0
        assertThrows(IOException::class.java) { runBlocking { budget.fetch { calls++; delay(1000) } } }
        assertThrows(IOException::class.java) { runBlocking { budget.fetch { calls++ } } }
        assertEquals(1, calls)
    }
    @Test fun `refresh runs no more than two requests concurrently`() = runBlocking {
        val budget = CatalogRefreshBudget()
        var active = 0
        var peak = 0
        coroutineScope { (1..6).map { async {
            budget.fetch { active++; peak = maxOf(peak, active); delay(10); active-- }
        } }.awaitAll() }
        assertEquals(2, peak)
    }
    @Test fun `failed manual refresh stays marked stale until a successful refresh`() = runBlocking {
        val cache = BoardCatalogSnapshotCache<String> { 100L }
        cache.get("a", false) { CatalogSnapshot("ok", "v", 100, BoardCatalogCoverage.AVAILABLE) }
        assertTrue(cache.get("a", true) { throw IOException() }.isStale)
        assertTrue(cache.get("a", false) { throw AssertionError("Should reuse snapshot") }.isStale)
    }
    @Test fun `legacy null epoch never reuses or falls back to an account catalog`() = runBlocking {
        val cache = BoardCatalogSnapshotCache<String> { 100L }
        var calls = 0
        repeat(2) { cache.get(null, false) { calls++; CatalogSnapshot("private", "v", 100, BoardCatalogCoverage.AVAILABLE) } }
        assertEquals(2, calls)
        assertThrows(IOException::class.java) { runBlocking { cache.get(null, false) { throw IOException() } } }
        Unit
    }
    @Test fun `catalog expires after 24 hours and stale fallback keeps original time`() = runBlocking {
        var now = 100L
        var calls = 0
        val cache = BoardCatalogSnapshotCache<String> { now }
        suspend fun load(budget: CatalogRefreshBudget): CatalogSnapshot<String> {
            calls++
            return CatalogSnapshot("boards", "v1", now, BoardCatalogCoverage.AVAILABLE)
        }
        cache.get("a", false, ::load)
        now += 86_399_999L
        cache.get("a", false, ::load)
        assertEquals(1, calls)
        now++
        val stale = cache.get("a", false) { throw IOException() }
        assertTrue(stale.isStale)
        assertEquals(100L, stale.updatedAtMillis)
        assertThrows(IOException::class.java) { runBlocking { cache.get("b", false) { throw IOException() } } }
        Unit
    }
    @Test fun `request budget stops the 51st request before execution`() = runBlocking {
        val budget = CatalogRefreshBudget()
        var calls = 0
        repeat(50) { budget.fetch { calls++ } }
        assertThrows(IOException::class.java) { runBlocking { budget.fetch { calls++ } } }
        assertEquals(50, calls)
    }
    @Test fun `simultaneous catalog reads share one loader`() = runBlocking {
        var calls = 0
        val cache = BoardCatalogSnapshotCache<String> { 10L }
        coroutineScope {
            (1..5).map { async { cache.get("a", false) { calls++; delay(10); CatalogSnapshot("ok", "v1", 10, BoardCatalogCoverage.AVAILABLE) } } }.awaitAll()
        }
        assertEquals(1, calls)
    }
}
