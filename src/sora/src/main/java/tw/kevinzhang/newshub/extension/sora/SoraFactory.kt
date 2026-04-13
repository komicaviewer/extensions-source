package tw.kevinzhang.newshub.extension.sora

import tw.kevinzhang.komica_api.KomicaFactory
import tw.kevinzhang.komica_api.model.KBoard
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.parser.sora.SoraPostParser
import tw.kevinzhang.komica_api.parser.sora.SoraThreadParser
import tw.kevinzhang.komica_api.parser.sora.SoraThreadSummariesParser
import tw.kevinzhang.komica_api.parser.sora.SoraUrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.komica_api.request.sora.SoraThreadRequestBuilder
import tw.kevinzhang.komica_api.request.sora.SoraThreadRequestParser
import tw.kevinzhang.komica_api.request.sora.SoraThreadSummariesRequestBuilder
import tw.kevinzhang.komica_api.request.sora.SoraThreadSummariesRequestParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraPostHeadParser

object SoraFactory : KomicaFactory {
    override fun createUrlParser(): UrlParser = SoraUrlParser()

    override fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadParser(
            SoraPostParser(urlParser, SoraPostHeadParser()),
            SoraThreadRequestParser(),
            SoraThreadRequestBuilder(),
        )

    override fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadSummariesParser(
            SoraPostParser(urlParser, SoraPostHeadParser()),
            SoraThreadSummariesRequestParser(),
            SoraThreadRequestBuilder(),
        )

    override fun createThreadSummariesRequestBuilder(board: KBoard): ThreadSummariesRequestBuilder =
        SoraThreadSummariesRequestBuilder().setBoard(board)

    override fun createThreadRequestBuilder(board: KBoard): ThreadRequestBuilder =
        SoraThreadRequestBuilder().setBoard(board)
}
