package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.kevinzhang.extension_api.AuthSpec
import tw.kevinzhang.extension_api.AuthenticatedSource
import tw.kevinzhang.extension_api.AuthenticationRequiredException
import tw.kevinzhang.extension_api.AuthenticationSession
import tw.kevinzhang.extension_api.SourceRuntime
import tw.kevinzhang.extension_api.WebLoginUserAgentProvider
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.BoardCategory
import tw.kevinzhang.extension_api.model.BoardPage
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadPage
import tw.kevinzhang.extension_api.model.ThreadPageMetadata
import tw.kevinzhang.extension_api.model.ThreadSummary
import java.io.IOException
import tw.kevinzhang.newshub.extension.runtime.brokerBackedHttpClient

class EynySource : AuthenticatedSource, WebLoginUserAgentProvider {
    override val id = SOURCE_ID
    override val name = "EYNY 伊莉討論區"
    override val language = "zh-TW"
    override val version = 2
    override val iconUrl = "https://eyny.com/favicon.ico"
    override val supportsCommentPagination = false
    override val alwaysUseRawImage = false
    override val needsLogin = false
    override val webLoginUserAgent = EYNY_USER_AGENT
    override val authSpec: AuthSpec
        get() {
            val hosts = AUTH_HOSTS + gateway.activeHost
            return AuthSpec.WebCookie(
                loginUrl = "https://${gateway.activeHost}/member.php?mod=logging&action=login",
                allowedHosts = hosts,
                cookieOrigins = hosts.mapTo(linkedSetOf()) { "https://$it" },
                cookieDomains = setOf("eyny.com"),
            )
        }

    private var authentication: AuthenticationSession? = null
    private val parser = EynyParser()
    private val gateway = EynyGateway()
    private val catalogMutex = Mutex()
    private var catalog: EynyCatalog? = null

    override fun onAttach(runtime: SourceRuntime) {
        gateway.updateRuntime(runtime.brokerBackedHttpClient(), runtime.namedCookies)
        authentication = runtime.authentication
    }

    override suspend fun getBoardCategories(): List<BoardCategory> = loadCatalog().categories
    override suspend fun getBoardPage(request: BoardPageRequest): BoardPage {
        val loaded = loadCatalog()
        val query = request.query.text.trim()
        val selected = loaded.boards.asSequence().filter { request.query.categoryId == null || it.categoryId == request.query.categoryId }
            .filter { query.isBlank() || it.board.name.contains(query, true) || it.board.description.orEmpty().contains(query, true) }
            .map { it.board }.toList().let { if (query.isBlank()) popular(it) else it }
        val offset = request.pageToken?.toIntOrNull() ?: 0
        require(offset >= 0) { "Invalid EYNY board page token" }
        val values = selected.drop(offset).take(request.pageSize)
        return BoardPage(values, (offset + values.size).takeIf { it < selected.size }?.toString())
    }
    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> {
        require(board.sourceId == id && page > 0)
        val parsed = requireNotNull(EynyUrlPolicy.board(board.url)) { "Untrusted EYNY board" }
        return parser.parseSummaries(gateway.get(EynyUrlPolicy.canonicalBoard(parsed.fid, page)), board, iconUrl)
    }
    override suspend fun getThread(summary: ThreadSummary): Thread {
        val page = getThreadPage(summary, null)
        val metadata = requireNotNull(page.metadata)
        return Thread(metadata.id, metadata.url, metadata.title, page.posts)
    }
    override suspend fun getThreadPage(summary: ThreadSummary, pageToken: String?): ThreadPage {
        require(summary.sourceId == id)
        val original = requireNotNull(EynyUrlPolicy.thread(summary.id)) { "Untrusted EYNY thread" }
        val requested = if (pageToken == null) original else requireNotNull(EynyUrlPolicy.thread(pageToken)) { "Untrusted EYNY page token" }
        require(requested.tid == original.tid && requested.extra == original.extra && requested.page >= original.page) { "EYNY page token belongs to another thread" }
        try {
            val parsed = parser.parseThreadPage(gateway.get(requested.url), requested.url, iconUrl)
            return ThreadPage(parsed.posts, parsed.nextToken, if (pageToken == null) ThreadPageMetadata(original.url, original.url, summary.title) else null)
        } catch (error: EynyLockedException) {
            authentication?.markExpired()
            throw AuthenticationRequiredException(error.message)
        }
    }
    override suspend fun validateSession(): Boolean = try {
        val html = gateway.get("https://eyny.com/home.php?mod=spacecp")
        parser.signedIn(html).also { if (it) clearCatalog() }
    } catch (_: AuthenticationRequiredException) { false }
    override suspend fun getWebUrl(summary: ThreadSummary): String? = EynyUrlPolicy.thread(summary.id)?.url

    private suspend fun loadCatalog(): EynyCatalog = catalogMutex.withLock {
        catalog?.let { return@withLock it }
        val home = parser.parseCatalog(gateway.get("https://eyny.com/"))
        val emptyCategories = home.categories.filter { category -> home.boards.none { it.categoryId == category.id } }
        val expanded = emptyCategories.flatMap { category ->
            try {
                parser.parseCategoryBoards(gateway.get(EynyUrlPolicy.canonicalCategory(category.id)), category)
            } catch (_: IOException) {
                emptyList()
            }
        }
        home.copy(boards = (home.boards + expanded).distinctBy { it.board.url }).also {
            if (it.boards.isEmpty()) throw IOException("EYNY catalog contains no visible boards")
            catalog = it
        }
    }
    private suspend fun clearCatalog() = catalogMutex.withLock { catalog = null }
    private fun popular(all: List<Board>): List<Board> {
        val ids = listOf(27, 16, 205, 26, 447, 491, 1743)
        return ids.mapNotNull { fid -> all.firstOrNull { EynyUrlPolicy.board(it.url)?.fid == fid } }.ifEmpty { all }
    }
    companion object {
        const val SOURCE_ID = "tw.kevinzhang.eyny"

        private val AUTH_HOSTS = setOf("eyny.com", "www.eyny.com", "www52.eyny.com", "www53.eyny.com")
    }
}
