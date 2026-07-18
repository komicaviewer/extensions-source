package tw.kevinzhang.komica_api.pixmicat

import okhttp3.HttpUrl
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.pixmicat.parser.Komica2PixmicatPostHeadParser
import tw.kevinzhang.komica_api.pixmicat.parser.PixmicatPostParser
import tw.kevinzhang.komica_api.pixmicat.parser.PixmicatThreadParser
import tw.kevinzhang.komica_api.pixmicat.parser.PixmicatThreadSummariesParser
import tw.kevinzhang.komica_api.pixmicat.parser.PixmicatUrlParser
import tw.kevinzhang.komica_api.pixmicat.request.Komica2PixmicatThreadRequestBuilder
import tw.kevinzhang.komica_api.pixmicat.request.Komica2PixmicatThreadSummariesRequestBuilder
import tw.kevinzhang.komica_api.pixmicat.request.PixmicatThreadRequestParser
import tw.kevinzhang.komica_api.pixmicat.request.PixmicatThreadSummariesRequestParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder

/** Shared Pixmicat parser and request engine for Komica2 extension APKs. */
class Komica2PixmicatEngine {
    fun createUrlParser(): UrlParser = PixmicatUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        PixmicatThreadParser(
            PixmicatPostParser(urlParser, Komica2PixmicatPostHeadParser()),
            PixmicatThreadRequestParser(),
            Komica2PixmicatThreadRequestBuilder(),
        )

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
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
