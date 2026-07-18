package tw.kevinzhang.newshub.extension.site2cat

import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catPostHeadParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catPostParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catThreadParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catThreadSummariesParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catUrlParser
import tw.kevinzhang.newshub.extension.site2cat.request.Site2catRequestBuilder

class Site2catFactory {
    fun createUrlParser(): UrlParser = Site2catUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        Site2catThreadParser(
            Site2catPostParser(urlParser, Site2catPostHeadParser(urlParser)),
            Site2catRequestBuilder(),
        )

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        Site2catThreadSummariesParser(
            Site2catPostParser(urlParser, Site2catPostHeadParser(urlParser)),
            Site2catRequestBuilder(),
        )

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        Site2catRequestBuilder(baseBoardUrl = board.url.toHttpUrl()).setUrl(board.url.toHttpUrl())

    fun createThreadRequestBuilder(): ThreadRequestBuilder = Site2catRequestBuilder()
}
