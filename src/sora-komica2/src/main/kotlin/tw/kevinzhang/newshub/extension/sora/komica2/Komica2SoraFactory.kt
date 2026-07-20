package tw.kevinzhang.newshub.extension.sora.komica2

import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.Komica2PixmicatEngine
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.Parser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPost
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.UrlParser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.request.ThreadSummariesRequestBuilder

internal class Komica2SoraFactory {
    private val engine = Komica2PixmicatEngine()

    fun createThreadUrlParser(): UrlParser = engine.createUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        engine.createThreadParser(urlParser)

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        engine.createThreadSummariesParser(urlParser)

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        engine.createThreadSummariesRequestBuilder(board)

    fun createThreadRequestBuilder(board: Board): ThreadRequestBuilder =
        engine.createThreadRequestBuilder(board)

    fun normalizeUrl(url: okhttp3.HttpUrl): String = engine.normalizeUrl(url)
}
