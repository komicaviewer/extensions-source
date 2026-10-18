package tw.kevinzhang.newshub.extension.gamer

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.extension_api.model.*
import tw.kevinzhang.newshub.extension.runtime.asTestSourceRuntime

class GamerBoardSearchTest {
    @Test fun `interleaved query and health requests do not invalidate another consumer token`() = runBlocking {
        val pager = GamerBoardPager("s")
        val rows = (1..20).map { tw.kevinzhang.gamer_api.model.GBoard("看板$it", "https://forum.gamer.com.tw/B.php?bsn=$it", null) }
        val load: suspend (Int) -> tw.kevinzhang.gamer_api.interactor.GBoardDirectoryPage = {
            tw.kevinzhang.gamer_api.interactor.GBoardDirectoryPage(rows, 20)
        }
        val a = BoardPageRequest(BoardQuery("a"), pageSize = 1, contextEpoch = "account")
        val first = pager.page(a, 20, load)
        pager.page(a.copy(query = BoardQuery("b")), 20, load)
        pager.page(a.copy(contextEpoch = null), 20, load)
        assertEquals("看板2", pager.page(a.copy(pageToken = first.nextPageToken), 20, load).boards.single().name)
        pager.page(a.copy(refresh = true), 20, load)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            pager.page(a.copy(pageToken = first.nextPageToken), 20, load)
        } }
        Unit
    }
    @Test fun `remote pages can be traversed with 1 30 or 100 items without dropping any boards`() = runBlocking {
        for (size in listOf(1, 30, 100)) {
            var calls = 0
            val source = GamerSource().apply { onAttach(OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val page = chain.request().url.queryParameter("page")!!.toInt()
                val start = (page - 1) * 20 + 1
                val rows = (start..minOf(start + 19, 43)).joinToString(",") { """{"type":"forum","bsn":$it,"title":"看板$it"}""" }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"data":{"list":[$rows]}}""".toResponseBody()).build()
            }.build().asTestSourceRuntime()) }
            var token: String? = null
            val ids = mutableListOf<String>()
            var iterations = 0
            do {
                val page = source.getBoardPage(BoardPageRequest(BoardQuery("看板"), token, size, contextEpoch = "a"))
                ids += page.boards.map { it.name }
                token = page.nextPageToken
                check(++iterations <= 50)
            } while (token != null)
            assertEquals((1..43).map { "看板$it" }, ids)
            assertEquals(3, calls)
        }
    }
    @Test fun `search does not silently ignore a category filter`() {
        assertThrows(UnsupportedOperationException::class.java) { runBlocking {
            GamerSource().getBoardPage(BoardPageRequest(BoardQuery("手機", "pc")))
        } }
    }
}
