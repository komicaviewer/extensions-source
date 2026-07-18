package tw.kevinzhang.newshub.extension.komica2_sora

import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.pixmicat.Komica2PixmicatEngine
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.extension_api.model.Board

class Komica2SoraFactory {
    private val engine = Komica2PixmicatEngine()

    fun createThreadUrlParser(): UrlParser = engine.createUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        engine.createThreadParser(urlParser)

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        engine.createThreadSummariesParser(urlParser)

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        engine.createThreadSummariesRequestBuilder(board)

    fun createThreadRequestBuilder(board: Board): ThreadRequestBuilder =
        engine.createThreadRequestBuilder(board)

    fun normalizeUrl(url: okhttp3.HttpUrl): String = engine.normalizeUrl(url)
}
