package tw.kevinzhang.newshub.extension.komica2_sora

import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.komica2_sora.parser.Komica2SoraPostHeadParser
import tw.kevinzhang.newshub.extension.komica2_sora.request.Komica2SoraThreadRequestBuilder
import tw.kevinzhang.newshub.extension.komica2_sora.request.Komica2SoraThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.parser.SoraPostParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraThreadParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraThreadSummariesParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraUrlParser
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadRequestParser
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadSummariesRequestParser

class Komica2SoraFactory {
    // SoraKomica2 boards use the Sora URL scheme and Sora-format parsers;
    // only PostHeadParser and request builders are komica2-specific.
    fun createThreadUrlParser(): UrlParser = SoraUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadParser(
            SoraPostParser(urlParser, Komica2SoraPostHeadParser()),
            SoraThreadRequestParser(),
            Komica2SoraThreadRequestBuilder(),
        )

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadSummariesParser(
            SoraPostParser(urlParser, Komica2SoraPostHeadParser()),
            SoraThreadSummariesRequestParser(),
            Komica2SoraThreadRequestBuilder(),
        )

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        Komica2SoraThreadSummariesRequestBuilder().setBoard(board)

    fun createThreadRequestBuilder(board: Board): ThreadRequestBuilder =
        Komica2SoraThreadRequestBuilder().setBoard(board)
}
