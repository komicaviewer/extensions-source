package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.extension_api.model.*
import tw.kevinzhang.newshub.extension.runtime.asTestSourceRuntime

class EynyBoardCatalogTest {
    @Test fun `partial directory is reported and manual refresh can recover missing categories`() = runBlocking {
        var fail = true
        var calls = 0
        val source = EynySource().apply { onAttach(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val category = chain.request().url.queryParameter("gid")
            val body = if (category != null) """<div id="ct"><table class="fl_tb"><tr><td><a href="forum-999-1.html">討論B</a></td></tr></table></div>"""
                else """<table><tr><td id="category_1"><a href="forum-27-1.html">討論A</a></td><td id="category_2"></td></tr></table>"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (fail && category != null) 503 else 200).message("response").body(body.toResponseBody()).build()
        }.build().asTestSourceRuntime()) }
        val request = BoardPageRequest(BoardQuery("討論"), contextEpoch = "a")
        val partial = source.getBoardPage(request)
        assertEquals(BoardCatalogCoverage.PARTIAL, partial.catalogInfo?.coverage)
        assertEquals(listOf("討論A"), partial.boards.map { it.name })
        source.getBoardPage(request)
        assertEquals(2, calls)
        fail = false
        val complete = source.getBoardPage(request.copy(refresh = true))
        assertEquals(BoardCatalogCoverage.AVAILABLE, complete.catalogInfo?.coverage)
        assertEquals(setOf("討論A", "討論B"), complete.boards.map { it.name }.toSet())
        source.getBoardPage(request.copy(contextEpoch = "b"))
        assertEquals(6, calls)
    }
}
