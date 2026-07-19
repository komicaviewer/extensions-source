package tw.kevinzhang.gamer_api

import okhttp3.OkHttpClient
import okhttp3.Request
import tw.kevinzhang.gamer_api.interactor.GetAllComment
import tw.kevinzhang.gamer_api.interactor.GetBoardPage
import tw.kevinzhang.gamer_api.interactor.GetRequestBuilder
import tw.kevinzhang.gamer_api.interactor.GetThread
import tw.kevinzhang.gamer_api.interactor.GetThreadSummaries
import tw.kevinzhang.gamer_api.model.GComment
import tw.kevinzhang.gamer_api.model.GPost
import tw.kevinzhang.gamer_api.model.GThreadSummary

class GamerApi (
    private val client: OkHttpClient,
) {
    fun getRequestBuilder() =
        GetRequestBuilder().invoke()

    suspend fun getBoardPage(categoryCode: Int, page: Int) =
        GetBoardPage(client).invoke(categoryCode, page)

    suspend fun searchBoards(query: String, page: Int) =
        GetBoardPage(client).search(query, page)

    suspend fun getThreadSummaries(req: Request): List<GThreadSummary> {
        return GetThreadSummaries(client).invoke(req)
    }

    suspend fun getThread(req: Request): List<GPost> {
        return GetThread(client).invoke(req)
    }

    suspend fun getAllComment(req: Request): List<GComment> {
        return GetAllComment(client).invoke(req)
    }
}
