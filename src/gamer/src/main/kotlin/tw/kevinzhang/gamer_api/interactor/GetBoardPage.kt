package tw.kevinzhang.gamer_api.interactor

import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.gamer_api.model.GBoard

/** Loads one 30-item page from the board directory used by the official Bahamut app. */
class GetBoardPage(
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
) {
    suspend fun invoke(categoryCode: Int, page: Int): List<GBoard> = withContext(Dispatchers.IO) {
        require(page > 0) { "page must be positive" }
        val url = BOARD_LIST_URL.toHttpUrl().newBuilder()
            .addQueryParameter("c", categoryCode.toString())
            .addQueryParameter("page", page.toString())
            .build()
        execute(url.toString())
    }

    suspend fun search(query: String, page: Int): List<GBoard> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "query must not be blank" }
        require(page > 0) { "page must be positive" }
        val url = BOARD_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("area", "forum")
            .build()
        execute(url.toString())
    }

    private suspend fun execute(url: String): List<GBoard> {
        val response = client.newCall(Request.Builder().url(url).get().build()).await()
        response.use {
            check(it.isSuccessful) { "Bahamut board directory failed with HTTP ${it.code}" }
            val body = it.body?.charStream()
                ?: error("Bahamut board directory returned an empty response")
            val data = gson.fromJson(body, BoardListResponse::class.java).data
            val list = if (data.isJsonArray) {
                data
            } else {
                check(data.isJsonObject && data.asJsonObject.has("list")) {
                    "Bahamut board directory returned an unsupported response"
                }
                data.asJsonObject.get("list")
            }
            return gson.fromJson(list, Array<BoardListItem>::class.java).asList()
                .filter { item -> item.type == null || item.type == "forum" }
                .map { item ->
                    GBoard(
                        name = item.title.trim(),
                        url = "https://forum.gamer.com.tw/B.php?bsn=${item.bsn}",
                        category = item.category,
                    )
                }
        }
    }

    private data class BoardListResponse(val data: JsonElement)
    private data class BoardListItem(
        val bsn: Int,
        val title: String,
        val category: String?,
        val type: String?,
    )

    private companion object {
        const val BOARD_LIST_URL =
            "https://api.gamer.com.tw/mobile_app/forum/v3/board_list.php"
        const val BOARD_SEARCH_URL =
            "https://api.gamer.com.tw/community/v1/search.php"
    }
}
