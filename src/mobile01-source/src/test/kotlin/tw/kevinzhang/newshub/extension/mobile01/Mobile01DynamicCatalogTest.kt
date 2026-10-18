package tw.kevinzhang.newshub.extension.mobile01

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.extension_api.model.*
import tw.kevinzhang.newshub.extension.runtime.asTestSourceRuntime

class Mobile01DynamicCatalogTest {
    @Test fun `refreshed recommendation continuation remains valid without repeating refresh`() = runBlocking {
        val source = Mobile01Source().apply { onAttach(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""<section><a href="topiclist.php?f=350">電腦螢幕</a><a href="topiclist.php?f=383">iPhone</a></section>""".toResponseBody()).build()
        }.build().asTestSourceRuntime()) }
        val request = BoardPageRequest(pageSize = 1, refresh = true, contextEpoch = "public")
        val first = source.getBoardPage(request)
        val next = source.getBoardPage(request.copy(refresh = false, pageToken = first.nextPageToken))
        assertNotEquals(first.boards.single().url, next.boards.single().url)
    }
    @Test fun `recommendations remain usable before a remote catalog is available`() = runBlocking {
        val page = Mobile01Source().getBoardPage(BoardPageRequest(contextEpoch = "public"))
        assertEquals(16, page.boards.size)
        assertEquals(BoardCatalogCoverage.UNKNOWN, page.catalogInfo?.coverage)
        assertNull(page.catalogInfo?.updatedAtMillis)
    }
    @Test fun `directory discovers non curated boards and excludes external and trading links`() {
        val parsed = Mobile01CatalogParser().parse("""
            <section><h2>數位科技</h2>
            <a href="topiclist.php?f=9001">示例看板A</a><a href="/topiclist.php?f=9002">示例看板B</a>
            <a href="https://evil.test/topiclist.php?f=9">外站</a>
            <a href="topiclist.php?f=9003">二手交易</a></section>
            <a href="forumlist.php?f=10">其他分類</a>
        """.trimIndent(), "https://www.mobile01.com/forumlist.php")
        assertEquals(listOf("示例看板A", "示例看板B"), parsed.boards.map { it.name })
        assertEquals(listOf("https://www.mobile01.com/forumlist.php?f=10"), parsed.categoryUrls)
        assertEquals(9001, Mobile01BoardCatalog.validate(parsed.boards.first()))
    }
    @Test fun `source searches a dynamic directory and pagination tokens bind the query`() = runBlocking {
        var catalogRequests = 0
        val source = Mobile01Source().apply { onAttach(OkHttpClient.Builder().addInterceptor { chain ->
            catalogRequests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""<section><h2>測試分類</h2><a href="topiclist.php?f=9001">示例A</a><a href="topiclist.php?f=9002">示例B</a></section>""".toResponseBody()).build()
        }.build().asTestSourceRuntime()) }
        val request = BoardPageRequest(BoardQuery("示例"), pageSize = 1, contextEpoch = "a")
        val first = source.getBoardPage(request)
        assertEquals("示例A", first.boards.single().name)
        val second = source.getBoardPage(request.copy(pageToken = first.nextPageToken))
        assertEquals("示例B", second.boards.single().name)
        assertEquals(1, catalogRequests)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            source.getBoardPage(request.copy(query = BoardQuery("別的"), pageToken = first.nextPageToken))
        } }
        source.getBoardPage(request.copy(refresh = true))
        assertEquals(2, catalogRequests)
        Unit
    }
}
