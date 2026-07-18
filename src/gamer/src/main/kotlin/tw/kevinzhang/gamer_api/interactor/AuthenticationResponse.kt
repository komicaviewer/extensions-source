package tw.kevinzhang.gamer_api.interactor

import okhttp3.Response
import tw.kevinzhang.extension_api.AuthenticationRequiredException

private const val LOGIN_HOST = "user.gamer.com.tw"
private const val MAX_RESTRICTION_PAGE_BYTES = 512L * 1024L

/**
 * These strings are rendered by Gamer's age-gate dialog on protected forum pages.
 *
 * The server keeps the requested `B.php` URL and returns HTTP 200 for this dialog,
 * so a status-code or redirect check alone cannot distinguish it from a board page.
 */
private val ageGateMarkers = listOf(
    "您即將進入之文章內容需滿十八歲方可瀏覽",
    "是的，我已經年滿 18 歲了",
)

/**
 * Converts every unauthenticated Gamer response to the host API's common error.
 *
 * Gamer redirects protected forum pages to a login URL instead of consistently
 * returning an HTTP 401 or 403, so both signals must be handled here.
 */
internal fun Response.throwIfAuthenticationRequired(): Response {
    val finalUrl = request.url
    val redirectedToLogin =
        finalUrl.toString().contains("loginPage", ignoreCase = true) ||
            (finalUrl.host == LOGIN_HOST && finalUrl.encodedPath.contains("login", ignoreCase = true))
    val ageGateRequired = code in 200..299 && bodyContainsAgeGate()

    if (code == 401 || code == 403 || redirectedToLogin || ageGateRequired) {
        throw AuthenticationRequiredException(
            message = "Gamer login is required (${code} at $finalUrl)",
        )
    }
    return this
}

/**
 * Reads a bounded copy, preserving [Response.body] for the caller's parser.
 *
 * We intentionally require Gamer's explicit age-gate text rather than treating an
 * empty board selector as an authentication failure: an ordinary board can be empty.
 */
private fun Response.bodyContainsAgeGate(): Boolean {
    body ?: return false
    val html = peekBody(MAX_RESTRICTION_PAGE_BYTES).string()
    return ageGateMarkers.all(html::contains)
}
