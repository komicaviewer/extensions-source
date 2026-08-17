package tw.kevinzhang.gamer_api.interactor

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class GetBoardPageTest {
    @Test
    fun `board directory uses category and one based page`() = runBlocking {
        var requestedUrl = ""
        val subject = GetBoardPage(jsonClient(BOARD_LIST_JSON) { requestedUrl = it })

        val boards = subject.invoke(categoryCode = 94, page = 2)

        assertEquals(
            "https://api.gamer.com.tw/mobile_app/forum/v3/board_list.php?c=94&page=2",
            requestedUrl,
        )
        assertEquals("新楓之谷", boards.single().name)
        assertEquals("https://forum.gamer.com.tw/B.php?bsn=7650", boards.single().url)
        assertEquals("角色扮演", boards.single().category)
    }

    @Test
    fun `search sends one character query and only keeps forum results`() = runBlocking {
        var requestedUrl = ""
        val subject = GetBoardPage(jsonClient(SEARCH_JSON) { requestedUrl = it })

        val boards = subject.search(query = "楓", page = 1)

        assertEquals(
            "https://api.gamer.com.tw/community/v1/search.php?q=%E6%A5%93&page=1&area=forum",
            requestedUrl,
        )
        assertEquals(listOf("新楓之谷"), boards.map { it.name })
    }

    @Test
    fun `board directory accepts boards directly in data after response shape drift`() = runBlocking {
        val subject = GetBoardPage(jsonClient(DIRECT_DATA_BOARD_LIST_JSON) {})

        val boards = subject.invoke(categoryCode = 21, page = 1)

        assertEquals(listOf("新楓之谷"), boards.map { it.name })
        assertEquals("https://forum.gamer.com.tw/B.php?bsn=7650", boards.single().url)
        assertEquals("角色扮演", boards.single().category)
    }

    private fun jsonClient(json: String, recordUrl: (String) -> Unit): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                recordUrl(chain.request().url.toString())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody())
                    .build()
            })
            .build()

    private companion object {
        val BOARD_LIST_JSON = """
            {"data":{"list":[{"bsn":7650,"title":"新楓之谷 ","category":"角色扮演"}]}}
        """.trimIndent()

        val SEARCH_JSON = """
            {"data":{"list":[
              {"type":"forum","bsn":7650,"title":"新楓之谷 ","category":"角色扮演"},
              {"type":"acg","bsn":999,"title":"不是哈啦板","category":"作品"}
            ]}}
        """.trimIndent()

        val DIRECT_DATA_BOARD_LIST_JSON = """
            {"data":[{"bsn":7650,"title":"新楓之谷 ","category":"角色扮演"}]}
        """.trimIndent()
    }
}
