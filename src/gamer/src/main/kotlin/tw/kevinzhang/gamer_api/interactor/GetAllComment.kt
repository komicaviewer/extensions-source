package tw.kevinzhang.gamer_api.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.gamer_api.model.GComment
import tw.kevinzhang.gamer_api.parser.CommentListParser
import tw.kevinzhang.gamer_api.request.RequestBuilderImpl
import java.util.logging.Logger

private val logger = Logger.getLogger("GetAllComment")

class GetAllComment(
    private val client: OkHttpClient,
) {
    suspend fun invoke(req: Request): List<GComment> = withContext(Dispatchers.IO) {
        val url = req.url.toString()
        logger.info("→ GET $url")
        val t0 = System.currentTimeMillis()
        val res = client.newCall(req).await()
        val ms = System.currentTimeMillis() - t0
        try {
            res.throwIfAuthenticationRequired()
        } catch (error: tw.kevinzhang.extension_api.AuthenticationRequiredException) {
            logger.warning("← AUTH_REQUIRED ${res.request.url} (${ms}ms)")
            res.close()
            throw error
        }
        res.use {
            logger.info("← ${it.code} $url (${ms}ms)")
            CommentListParser(RequestBuilderImpl()).parse(it.body!!, req)
        }
    }
}
