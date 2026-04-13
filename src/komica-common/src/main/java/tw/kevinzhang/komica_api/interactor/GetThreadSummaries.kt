package tw.kevinzhang.komica_api.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.komica_api.HttpException
import tw.kevinzhang.komica_api.KomicaFactory
import tw.kevinzhang.komica_api.model.KPost

class GetThreadSummaries(
    private val client: OkHttpClient,
    private val factory: KomicaFactory,
) {
    suspend fun invoke(req: Request): List<KPost> = withContext(Dispatchers.IO) {
        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = factory.createUrlParser()
        factory.createThreadSummariesParser(urlParser).parse(response.body!!, req)
    }
}