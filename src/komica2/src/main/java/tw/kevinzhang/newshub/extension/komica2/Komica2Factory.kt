package tw.kevinzhang.newshub.extension.komica2

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
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadRequestParser
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadSummariesRequestParser
import tw.kevinzhang.newshub.extension.komica2.parser.Komica2PostHeadParser
import tw.kevinzhang.newshub.extension.komica2.request.Komica2ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.komica2.request.Komica2ThreadSummariesRequestBuilder

class Komica2Factory {
    // SoraKomica2 boards use the Sora URL scheme and Sora-format parsers;
    // only PostHeadParser and request builders are komica2-specific.
     fun createThreadUrlParser(): UrlParser = SoraUrlParser()

     fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadParser(
            SoraPostParser(urlParser, Komica2PostHeadParser()),
            SoraThreadRequestParser(),
            Komica2ThreadRequestBuilder(),
        )

     fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadSummariesParser(
            SoraPostParser(urlParser, Komica2PostHeadParser()),
            SoraThreadSummariesRequestParser(),
            Komica2ThreadRequestBuilder(),
        )

     fun createThreadSummariesRequestBuilder(board: KBoard): ThreadSummariesRequestBuilder =
        Komica2ThreadSummariesRequestBuilder().setBoard(board)

     fun createThreadRequestBuilder(board: KBoard): ThreadRequestBuilder =
        Komica2ThreadRequestBuilder().setBoard(board)
}
