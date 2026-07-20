package tw.kevinzhang.newshub.extension.sora.komica

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.newshub.extension.sora.komica.parser.Parser
import tw.kevinzhang.newshub.extension.sora.komica.parser.ParsedPost
import tw.kevinzhang.newshub.extension.sora.komica.parser.UrlParser
import tw.kevinzhang.newshub.extension.sora.komica.request.ThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica.parser.SoraPostHeadParser
import tw.kevinzhang.newshub.extension.sora.komica.parser.SoraPostParser
import tw.kevinzhang.newshub.extension.sora.komica.parser.SoraThreadParser
import tw.kevinzhang.newshub.extension.sora.komica.parser.SoraThreadSummariesParser
import tw.kevinzhang.newshub.extension.sora.komica.parser.SoraUrlParser
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadRequestParser
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.komica.request.SoraThreadSummariesRequestParser

internal class SoraFactory {
    fun createUrlParser(): UrlParser = SoraUrlParser()

    fun createThreadParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        SoraThreadParser(
            SoraPostParser(urlParser, SoraPostHeadParser()),
            SoraThreadRequestParser(),
            SoraThreadRequestBuilder(),
        )

    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<ParsedPost>> =
        SoraThreadSummariesParser(
            SoraPostParser(urlParser, SoraPostHeadParser()),
            SoraThreadSummariesRequestParser(),
            SoraThreadRequestBuilder(),
        )

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        SoraThreadSummariesRequestBuilder().setUrl(board.url.toHttpUrl())

    fun createThreadRequestBuilder(url: HttpUrl): ThreadRequestBuilder =
        SoraThreadRequestBuilder().setUrl(url)
}
