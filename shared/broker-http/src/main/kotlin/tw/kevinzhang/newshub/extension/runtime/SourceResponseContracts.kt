package tw.kevinzhang.newshub.extension.runtime

import java.io.IOException
import okhttp3.Response

/** Stable, non-secret reason for a website response that cannot be consumed by a Source. */
enum class SourceSiteUnavailableReason {
    HTTP_ERROR,
    ACCESS_CHALLENGE,
}

/**
 * An upstream website failure, deliberately modelled as [IOException] so extension-api preserves
 * `SITE_UNAVAILABLE` across Binder instead of collapsing it into `EXTENSION_RUNTIME`.
 */
class SourceSiteUnavailableException(
    val statusCode: Int,
    val reason: SourceSiteUnavailableReason,
) : IOException("Source website unavailable: ${reason.name} (HTTP $statusCode)")

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
    val reason = if (
        code == 403 && header("cf-mitigated").equals("challenge", ignoreCase = true)
    ) {
        SourceSiteUnavailableReason.ACCESS_CHALLENGE
    } else {
        SourceSiteUnavailableReason.HTTP_ERROR
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
