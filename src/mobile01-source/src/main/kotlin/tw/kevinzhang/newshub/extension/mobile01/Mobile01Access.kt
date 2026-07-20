package tw.kevinzhang.newshub.extension.mobile01

import java.io.IOException

internal enum class Mobile01AccessFailure { ACCESS_DENIED, RATE_LIMITED, BOT_CHALLENGE, HTTP_ERROR }

/** Explicitly distinguishes access controls from parser changes; callers must not try to bypass them. */
internal class Mobile01AccessException(
    val failure: Mobile01AccessFailure,
    statusCode: Int,
    url: String,
) : IOException("Mobile01 $failure (HTTP $statusCode): $url")

internal class Mobile01PageStructureException(page: String) :
    IOException("Mobile01 returned an unsupported $page document structure")

internal object Mobile01AccessClassifier {
    fun classify(statusCode: Int, body: String, server: String? = null): Mobile01AccessFailure? {
        val normalized = "$server\n$body".lowercase()
        return when {
            statusCode == 429 -> Mobile01AccessFailure.RATE_LIMITED
            statusCode == 403 && ("akamai" in normalized || "access denied" in normalized || "edgesuite" in normalized) ->
                Mobile01AccessFailure.ACCESS_DENIED
            statusCode == 403 -> Mobile01AccessFailure.ACCESS_DENIED
            statusCode in 200..299 && BOT_DOCUMENT_MARKERS.any(normalized::contains) ->
                Mobile01AccessFailure.BOT_CHALLENGE
            else -> null
        }
    }

    private val BOT_DOCUMENT_MARKERS = listOf(
        "<title>access denied",
        "<title>just a moment",
        "id=\"challenge-form\"",
        "id='challenge-form'",
        "class=\"captcha",
        "id=\"captcha",
        "cf-chl-",
        "/akam/13/pixel_",
    )
}
