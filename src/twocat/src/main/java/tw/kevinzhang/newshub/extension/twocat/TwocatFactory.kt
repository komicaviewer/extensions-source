package tw.kevinzhang.newshub.extension.twocat

import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.parser.TwocatPostHeadParser
import tw.kevinzhang.newshub.extension.twocat.parser.TwocatPostParser
import tw.kevinzhang.newshub.extension.twocat.parser.TwocatThreadParser
import tw.kevinzhang.newshub.extension.twocat.parser.TwocatThreadSummariesParser
import tw.kevinzhang.newshub.extension.twocat.parser.TwocatUrlParser
import tw.kevinzhang.newshub.extension.twocat.request.TwocatRequestBuilder

class TwocatFactory {
    fun createUrlParser(): UrlParser = TwocatUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        TwocatThreadParser(
            TwocatPostParser(urlParser, TwocatPostHeadParser(urlParser)),
            TwocatRequestBuilder(),
        )

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        TwocatThreadSummariesParser(
            TwocatPostParser(urlParser, TwocatPostHeadParser(urlParser)),
            TwocatRequestBuilder(),
        )

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        TwocatRequestBuilder(baseBoardUrl = board.url.toHttpUrl()).setUrl(board.url.toHttpUrl())

    fun createThreadRequestBuilder(): ThreadRequestBuilder = TwocatRequestBuilder()
}
