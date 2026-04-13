package tw.kevinzhang.newshub.extension.site2cat

import tw.kevinzhang.komica_api.KomicaFactory
import tw.kevinzhang.komica_api.model.KBoard
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.komica_api.request.site2cat.Site2catRequestBuilder
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catPostHeadParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catPostParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catThreadParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catThreadSummariesParser
import tw.kevinzhang.newshub.extension.site2cat.parser.Site2catUrlParser

object Site2catFactory : KomicaFactory {
    override fun createUrlParser(): UrlParser = Site2catUrlParser()

    override fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        Site2catThreadParser(
            Site2catPostParser(urlParser, Site2catPostHeadParser(urlParser)),
            Site2catRequestBuilder(),
        )

    override fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        Site2catThreadSummariesParser(
            Site2catPostParser(urlParser, Site2catPostHeadParser(urlParser)),
            Site2catRequestBuilder(),
        )

    override fun createThreadSummariesRequestBuilder(board: KBoard): ThreadSummariesRequestBuilder =
        Site2catRequestBuilder().setBoard(board)

    override fun createThreadRequestBuilder(board: KBoard): ThreadRequestBuilder =
        Site2catRequestBuilder().setBoard(board)
}
