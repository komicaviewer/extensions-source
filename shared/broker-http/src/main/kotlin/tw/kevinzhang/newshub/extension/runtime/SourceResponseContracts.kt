package tw.kevinzhang.newshub.extension.runtime

import java.io.IOException
import okhttp3.Response
import tw.kevinzhang.extension_api.SourceFailure
import tw.kevinzhang.extension_api.SourceFailureCode
import tw.kevinzhang.extension_api.SourceFailureException

/** Stable, non-secret reason for a website response that cannot be consumed by a Source. */
enum class SourceSiteUnavailableReason {
    HTTP_ERROR,
    ACCESS_CHALLENGE,
    ACCESS_DENIED,
}

/** An upstream website failure carrying only a stable typed reason across Binder. */
class SourceSiteUnavailableException(
    val statusCode: Int,
    val reason: SourceSiteUnavailableReason,
) : SourceFailureException(
    SourceFailure(
        code = when (reason) {
            SourceSiteUnavailableReason.HTTP_ERROR -> SourceFailureCode.SITE_UNAVAILABLE
            SourceSiteUnavailableReason.ACCESS_CHALLENGE -> SourceFailureCode.ACCESS_CHALLENGE
            SourceSiteUnavailableReason.ACCESS_DENIED -> SourceFailureCode.ACCESS_DENIED
        },
    ),
)

/**
 * A deterministic parser/schema mismatch. The extension protocol maps [IllegalArgumentException]
 * to `PARSER_CONTRACT`, while the message remains free of URLs and response content.
 */
class SourceParserContractException(
    contract: String,
) : IllegalArgumentException("Source parser contract failed: ${contract.safeContractName()}")

/** Closes a rejected response before raising its stable typed failure. */
fun Response.requireSourceSuccess(): Response {
    if (isSuccessful) return this
    val reason = when {
        code == 403 && header("cf-mitigated").equals("challenge", ignoreCase = true) ->
            SourceSiteUnavailableReason.ACCESS_CHALLENGE
        code == 403 -> SourceSiteUnavailableReason.ACCESS_DENIED
        else -> SourceSiteUnavailableReason.HTTP_ERROR
    }
    close()
    throw SourceSiteUnavailableException(code, reason)
}

private fun String.safeContractName(): String = takeIf { value ->
    value.length in 1..64 && value.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '_' || character == '-' || character == '.'
    }
} ?: "invalid_document"
