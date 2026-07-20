package tw.kevinzhang.newshub.extension.twocat.komica

import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.newshub.extension.twocat.komica.parser.Parser
import tw.kevinzhang.newshub.extension.twocat.komica.parser.ParsedPost
import tw.kevinzhang.newshub.extension.twocat.komica.parser.UrlParser
import tw.kevinzhang.newshub.extension.twocat.komica.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica.parser.TwocatPostHeadParser
import tw.kevinzhang.newshub.extension.twocat.komica.parser.TwocatPostParser
import tw.kevinzhang.newshub.extension.twocat.komica.parser.TwocatThreadParser
import tw.kevinzhang.newshub.extension.twocat.komica.parser.TwocatThreadSummariesParser
import tw.kevinzhang.newshub.extension.twocat.komica.parser.TwocatUrlParser
import tw.kevinzhang.newshub.extension.twocat.komica.request.TwocatRequestBuilder

internal class TwocatFactory {
    fun createUrlParser(): UrlParser = TwocatUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        TwocatThreadParser(
            TwocatPostParser(urlParser, TwocatPostHeadParser(urlParser)),
            TwocatRequestBuilder(),
        )

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        TwocatThreadSummariesParser(
            TwocatPostParser(urlParser, TwocatPostHeadParser(urlParser)),
            TwocatRequestBuilder(),
        )

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        TwocatRequestBuilder(baseBoardUrl = board.url.toHttpUrl()).setUrl(board.url.toHttpUrl())

    fun createThreadRequestBuilder(): ThreadRequestBuilder = TwocatRequestBuilder()
}
