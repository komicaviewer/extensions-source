package tw.kevinzhang.newshub.extension.sora

import tw.kevinzhang.komica_api.model.KBoard
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraPostParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraThreadParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraThreadSummariesParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraUrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadRequestParser
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadSummariesRequestParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraPostHeadParser

class SoraFactory {
     fun createUrlParser(): UrlParser = SoraUrlParser()

     fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadParser(
            SoraPostParser(urlParser, SoraPostHeadParser()),
            SoraThreadRequestParser(),
            SoraThreadRequestBuilder(),
        )

     fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadSummariesParser(
            SoraPostParser(urlParser, SoraPostHeadParser()),
            SoraThreadSummariesRequestParser(),
            SoraThreadRequestBuilder(),
        )

     fun createThreadSummariesRequestBuilder(board: KBoard): ThreadSummariesRequestBuilder =
        SoraThreadSummariesRequestBuilder().setBoard(board)

     fun createThreadRequestBuilder(board: KBoard): ThreadRequestBuilder =
        SoraThreadRequestBuilder().setBoard(board)
}
