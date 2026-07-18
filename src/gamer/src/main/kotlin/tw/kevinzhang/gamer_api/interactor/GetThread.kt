package tw.kevinzhang.gamer_api.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.gamer_api.model.GPost
import tw.kevinzhang.gamer_api.parser.PostParser
import tw.kevinzhang.gamer_api.parser.ThreadParser
import tw.kevinzhang.gamer_api.parser.UrlParserImpl
import tw.kevinzhang.gamer_api.request.RequestBuilderImpl
import java.util.logging.Logger

private val logger = Logger.getLogger("GetAllPost")

class GetThread(
    private val client: OkHttpClient,
) {
    suspend fun invoke(req: Request): List<GPost> = withContext(Dispatchers.IO) {
        val url = req.url.toString()
        logger.info("→ GET $url")
        val t0 = System.currentTimeMillis()
        val response = client.newCall(req).await()
        val ms = System.currentTimeMillis() - t0
        try {
            response.throwIfAuthenticationRequired()
        } catch (error: tw.kevinzhang.extension_api.AuthenticationRequiredException) {
            logger.warning("← AUTH_REQUIRED ${response.request.url} (${ms}ms)")
            response.close()
            throw error
        }
        response.use {
            logger.info("← ${it.code} $url (${ms}ms)")
            parse(it, req)
        }
    }

    private fun parse(res: Response, req: Request) =
        ThreadParser(PostParser(UrlParserImpl()), UrlParserImpl(), RequestBuilderImpl()).parse(res.body!!, req)
}
