package tw.kevinzhang.newshub.extension.komica2

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
import tw.kevinzhang.komica_api.request.sora.SoraThreadRequestParser
import tw.kevinzhang.komica_api.request.sora.SoraThreadSummariesRequestParser
import tw.kevinzhang.newshub.extension.komica2.parser.Komica2PostHeadParser
import tw.kevinzhang.newshub.extension.komica2.request.Komica2ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.komica2.request.Komica2ThreadSummariesRequestBuilder

object Komica2Factory : KomicaFactory {
    // SoraKomica2 boards use the Sora URL scheme and Sora-format parsers;
    // only PostHeadParser and request builders are komica2-specific.
    override fun createUrlParser(): UrlParser = SoraUrlParser()

    override fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadParser(
            SoraPostParser(urlParser, Komica2PostHeadParser()),
            SoraThreadRequestParser(),
            Komica2ThreadRequestBuilder(),
        )

    override fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>> =
        SoraThreadSummariesParser(
            SoraPostParser(urlParser, Komica2PostHeadParser()),
            SoraThreadSummariesRequestParser(),
            Komica2ThreadRequestBuilder(),
        )

    override fun createThreadSummariesRequestBuilder(board: KBoard): ThreadSummariesRequestBuilder =
        Komica2ThreadSummariesRequestBuilder().setBoard(board)

    override fun createThreadRequestBuilder(board: KBoard): ThreadRequestBuilder =
        Komica2ThreadRequestBuilder().setBoard(board)
}
