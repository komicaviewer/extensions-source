package tw.kevinzhang.komica_api.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.komica_api.HttpException
import tw.kevinzhang.komica_api.KomicaFactory
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.model.KReplyTo
import tw.kevinzhang.komica_api.model.replyTo

class GetThread(
    private val client: OkHttpClient,
    private val factory: KomicaFactory,
) {
    suspend fun invoke(req: Request): List<KPost> = withContext(Dispatchers.IO) {
        val response = client.newCall(req).await()
        if (!response.isSuccessful) throw HttpException(response.code, req.url.toString())
        val urlParser = factory.createUrlParser()
        factory.createThreadParser(urlParser).parse(response.body!!, req)
    }

    suspend fun withFillReplyTo(req: Request): List<KPost> {
        val urlParser = factory.createUrlParser()
        val headPostId = requireNotNull(urlParser.parseHeadPostId(req.url)) {
            "No head post ID found in URL: ${req.url}"
        }
        val origin = invoke(req)
        return origin.map { p ->
            if (p.replyTo().isEmpty()) {
                val originContent = p.content
                p.copy(content = listOf(KReplyTo(headPostId)).plus(originContent))
            } else {
                p
            }
        }
    }
}