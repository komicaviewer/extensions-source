package tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat

import okhttp3.HttpUrl
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.Komica2PixmicatPostHeadParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.PixmicatPostParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.PixmicatThreadParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.PixmicatThreadSummariesParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.PixmicatUrlParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.Parser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.ParsedPost
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.parser.UrlParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.Komica2PixmicatThreadRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.Komica2PixmicatThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.PixmicatThreadRequestParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.PixmicatThreadSummariesRequestParser
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.request.ThreadSummariesRequestBuilder

/** Shared Pixmicat parser and request engine for Komica2 extension APKs. */
internal class Komica2PixmicatEngine {
    fun createUrlParser(): UrlParser = PixmicatUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        PixmicatThreadParser(
            PixmicatPostParser(urlParser, Komica2PixmicatPostHeadParser()),
            PixmicatThreadRequestParser(),
            Komica2PixmicatThreadRequestBuilder(),
        )

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        PixmicatThreadSummariesParser(
            PixmicatPostParser(urlParser, Komica2PixmicatPostHeadParser()),
            PixmicatThreadSummariesRequestParser(),
            Komica2PixmicatThreadRequestBuilder(),
        )

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        Komica2PixmicatThreadSummariesRequestBuilder().setBoard(board)

    fun createThreadRequestBuilder(board: Board): ThreadRequestBuilder =
        Komica2PixmicatThreadRequestBuilder().setBoard(board)

    fun normalizeUrl(url: HttpUrl): String = url.newBuilder()
        .removeAllQueryParameters("page")
        .removeAllQueryParameters("page_num")
        .build()
        .toString()
}
