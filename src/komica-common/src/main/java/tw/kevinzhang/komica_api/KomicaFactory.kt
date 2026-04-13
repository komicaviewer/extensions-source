package tw.kevinzhang.komica_api

import tw.kevinzhang.komica_api.model.KBoard
import tw.kevinzhang.komica_api.model.KPost
import tw.kevinzhang.komica_api.parser.Parser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.request.ThreadRequestBuilder
import tw.kevinzhang.komica_api.request.ThreadSummariesRequestBuilder

interface KomicaFactory {
    fun createUrlParser(): UrlParser
    fun createThreadParser(urlParser: UrlParser): Parser<List<KPost>>
    fun createThreadSummariesParser(urlParser: UrlParser): Parser<List<KPost>>
    fun createThreadSummariesRequestBuilder(board: KBoard): ThreadSummariesRequestBuilder
    fun createThreadRequestBuilder(board: KBoard): ThreadRequestBuilder
}
