package tw.kevinzhang.newshub.extension.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.extension_api.model.*
import java.io.IOException

class BoardCatalogSnapshotCacheTest {
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
