package tw.kevinzhang.komica_api

import okhttp3.OkHttpClient
import okhttp3.Request
import tw.kevinzhang.komica_api.interactor.GetAllBoards
import tw.kevinzhang.komica_api.interactor.GetThread
import tw.kevinzhang.komica_api.interactor.GetThreadSummaries
import tw.kevinzhang.komica_api.model.KBoard
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder

class KomicaApi(
    private val client: OkHttpClient,
    private val factory: KomicaFactory,
) {
    fun getThreadSummariesRequestBuilder(board: KBoard): ThreadSummariesRequestBuilder =
        factory.createThreadSummariesRequestBuilder(board)

    fun getThreadRequestBuilder(board: KBoard): ThreadRequestBuilder =
        factory.createThreadRequestBuilder(board)

    suspend fun getAllBoards() = GetAllBoards().invoke()

    suspend fun getThread(req: Request): List<KPost> {
        val urlParser = factory.createUrlParser()
        return if (urlParser.hasPostId(req.url)) {
            GetThread(client, factory).invoke(req)
        } else {
            GetThreadSummaries(client, factory).invoke(req)
        }
    }
}