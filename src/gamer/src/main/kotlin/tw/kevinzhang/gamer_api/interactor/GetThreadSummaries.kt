package tw.kevinzhang.gamer_api.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.gamer_api.model.GThreadSummary
import tw.kevinzhang.gamer_api.parser.BoardParser
import tw.kevinzhang.gamer_api.parser.ThreadSummaryParser
import tw.kevinzhang.gamer_api.request.RequestBuilderImpl
import java.util.logging.Logger

private val logger = Logger.getLogger("GetAllNews")

class GetThreadSummaries(
    private val client: OkHttpClient,
) {
    /**
     * 取得指定 Board 底下的 ThreadSummaries，這些 ThreadSummaries 與 Thread 不同，ThreadSummaries 只是 Thread 的簡單資訊，而 Thread 內包含了整個討論串的原 PO 及回文 (RePost)
     */
    suspend fun invoke(req: Request): List<GThreadSummary> = withContext(Dispatchers.IO) {
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
            BoardParser(ThreadSummaryParser(), RequestBuilderImpl()).parse(it.body!!, req)
        }
    }
}
