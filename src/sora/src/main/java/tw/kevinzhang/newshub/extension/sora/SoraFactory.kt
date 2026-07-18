package tw.kevinzhang.newshub.extension.sora

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.parser.SoraPostHeadParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraPostParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraThreadParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraThreadSummariesParser
import tw.kevinzhang.newshub.extension.sora.parser.SoraUrlParser
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadRequestBuilder
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadRequestParser
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadSummariesRequestBuilder
import tw.kevinzhang.newshub.extension.sora.request.SoraThreadSummariesRequestParser

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

    fun createThreadSummariesRequestBuilder(board: Board): ThreadSummariesRequestBuilder =
        SoraThreadSummariesRequestBuilder().setUrl(board.url.toHttpUrl())

    fun createThreadRequestBuilder(url: HttpUrl): ThreadRequestBuilder =
        SoraThreadRequestBuilder().setUrl(url)
}
