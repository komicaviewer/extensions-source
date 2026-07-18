package tw.kevinzhang.gamer_api.interactor

import okhttp3.Response
import tw.kevinzhang.extension_api.AuthenticationRequiredException

private const val LOGIN_HOST = "user.gamer.com.tw"

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

    if (code == 401 || code == 403 || redirectedToLogin) {
        throw AuthenticationRequiredException(
            message = "Gamer login is required (${code} at $finalUrl)",
        )
    }
    return this
}
