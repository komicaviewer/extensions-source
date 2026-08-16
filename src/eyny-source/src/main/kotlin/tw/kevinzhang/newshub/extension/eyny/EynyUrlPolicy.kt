package tw.kevinzhang.newshub.extension.eyny

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only hosts authorized by the Host policy and named-cookie capability are requestable. */
internal object EynyUrlPolicy {
    private const val ROOT = "eyny.com"
    private const val WWW = "www.eyny.com"
    private const val WWW52 = "www52.eyny.com"
    private const val WWW53 = "www53.eyny.com"
    val allowedHosts = setOf(ROOT, WWW, WWW52, WWW53)
    private val THREAD = Regex("/thread-(\\d+)-(\\d+)-([A-Za-z0-9_-]+)\\.html")
    private val BOARD = Regex("/forum-(\\d+)-(\\d+)\\.html")
    private val EXTRA = Regex("[A-Za-z0-9_-]{1,64}")

    fun isAllowedHost(host: String): Boolean = host in allowedHosts
    fun canonicalBoard(fid: Int, page: Int = 1): String {
        require(fid > 0 && page > 0)
        return "https://$ROOT/forum-$fid-$page.html"
    }
    fun canonicalCategory(gid: String): String {
        require(gid.toLongOrNull()?.let { it > 0 } == true)
        return "https://$ROOT/forum.php?gid=$gid"
    }
    fun canonicalThread(tid: String, page: Int = 1, extra: String = "1"): String {
        require(tid.toLongOrNull()?.let { it > 0 } == true && page > 0 && EXTRA.matches(extra))
        return "https://$ROOT/thread-$tid-$page-$extra.html"
    }
    fun board(value: String): EynyBoardUrl? {
        val url = trusted(value) ?: return null
        val path = BOARD.matchEntire(url.encodedPath)
        if (path != null) {
            val fid = path.groupValues[1].toInt()
            val page = path.groupValues[2].toInt()
            return EynyBoardUrl(fid, page, canonicalBoard(fid, page))
        }
        val fid = url.queryParameter("fid")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val page = url.queryParameter("page")?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        if (url.encodedPath != "/forum.php" || url.queryParameter("mod") != "forumdisplay") return null
        return EynyBoardUrl(fid, page, canonicalBoard(fid, page))
    }
    fun thread(value: String): EynyThreadUrl? {
        val url = trusted(value) ?: return null
        val path = THREAD.matchEntire(url.encodedPath)
        if (path != null) {
            val tid = path.groupValues[1]
            val page = path.groupValues[2].toInt()
            val extra = path.groupValues[3]
            return EynyThreadUrl(tid, page, extra, canonicalThread(tid, page, extra))
        }
        val tid = url.queryParameter("tid")?.takeIf { it.toLongOrNull()?.let { value -> value > 0 } == true } ?: return null
        val page = url.queryParameter("page")?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val extra = url.queryParameter("extra")?.takeIf(EXTRA::matches) ?: "1"
        if (url.encodedPath != "/forum.php" || url.queryParameter("mod") != "viewthread") return null
        return EynyThreadUrl(tid, page, extra, canonicalThread(tid, page, extra))
    }
    fun resolve(base: String, href: String): String? = trusted(base)?.resolve(href)?.toString()?.let { trusted(it)?.toString() }
    fun resolvedHost(base: String, href: String): String? = trusted(base)?.resolve(href)?.host
    fun resolveThread(base: String, href: String): EynyThreadUrl? = resolve(base, href)?.let(::thread)
    fun requestUrl(canonical: String, activeHost: String): String {
        require(isAllowedHost(activeHost))
        return requireNotNull(trusted(canonical)).newBuilder().host(activeHost).build().toString()
    }
    fun safeContent(value: String): String? = value.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.toString()
    fun trusted(value: String): HttpUrl? = value.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && it.port == 443 && isAllowedHost(it.host) }
}

internal data class EynyBoardUrl(val fid: Int, val page: Int, val url: String)
internal data class EynyThreadUrl(val tid: String, val page: Int, val extra: String, val url: String)
