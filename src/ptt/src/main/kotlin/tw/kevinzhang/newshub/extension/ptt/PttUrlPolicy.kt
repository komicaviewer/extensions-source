package tw.kevinzhang.newshub.extension.ptt

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keeps network requests limited to PTT while allowing safe media links in article content. */
internal object PttUrlPolicy {
    private const val HOST = "www.ptt.cc"
    private val BOARD_NAME = Regex("[A-Za-z0-9_-]{1,64}")

    fun isBoardName(value: String): Boolean = BOARD_NAME.matches(value)

    fun popularBoardsUrl(): String = "https://$HOST/bbs/index.html"

    fun boardUrl(boardName: String): String {
        require(isBoardName(boardName)) { "Invalid PTT board name: $boardName" }
        return "https://$HOST/bbs/$boardName/index.html"
    }

    fun boardNameFromUrl(url: String): String? = trustedPttUrl(url)?.pathSegments
        ?.let { segments ->
            if (
                segments.size == 3 && segments[0] == "bbs" && isBoardName(segments[1]) &&
                BOARD_INDEX.matches(segments[2])
            ) segments[1] else null
        }

    fun articleUrl(url: String): String? {
        val parsed = trustedPttUrl(url) ?: return null
        val segments = parsed.pathSegments
        return parsed.toString().takeIf {
            segments.size == 3 && segments[0] == "bbs" && isBoardName(segments[1]) &&
                ARTICLE_FILE.matches(segments[2])
        }
    }

    fun resolveArticle(baseUrl: String, href: String): String? =
        trustedPttUrl(baseUrl)?.resolve(href)?.toString()?.let(::articleUrl)

    fun safeExternalUrl(value: String): String? = value.toHttpUrlOrNull()
        ?.takeIf { it.scheme == "https" || it.scheme == "http" }
        ?.toString()

    fun trustedPttUrl(value: String): HttpUrl? = value.toHttpUrlOrNull()?.takeIf {
        it.scheme == "https" && it.host == HOST && it.port == 443
    }

    private val ARTICLE_FILE = Regex("M\\.[A-Za-z0-9]+\\.[A-Za-z0-9]+\\.[A-Za-z0-9]+\\.html")
    private val BOARD_INDEX = Regex("index(?:\\d+)?\\.html")
}
