package tw.kevinzhang.newshub.extension.runtime

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import tw.kevinzhang.extension_api.model.*

/** Query and generation-bound continuations, independent of website page size. */
object BoardCatalogPaging {
    fun key(request: BoardPageRequest, version: String): String {
        val fields = listOf(request.query.text.trim(), request.query.categoryId, request.contextEpoch, version)
        val canonical = fields.joinToString("") { value -> value?.let { "${it.length}:$it" } ?: "-1:" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun offset(request: BoardPageRequest, version: String): Int {
        val token = request.pageToken ?: return 0
        require(token.length <= 256) { "Invalid catalog continuation" }
        val parts = runCatching { String(Base64.getUrlDecoder().decode(token), UTF_8).split(':') }.getOrNull()
        require(parts != null && parts.size == 2 && parts[0] == key(request, version)) { "Catalog continuation expired; reload first page" }
        return requireNotNull(parts[1].toIntOrNull()?.takeIf { it >= 0 }) { "Invalid catalog offset" }
    }
    fun token(request: BoardPageRequest, version: String, offset: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${key(request, version)}:$offset".toByteArray(UTF_8))

    fun page(boards: List<Board>, request: BoardPageRequest, snapshot: CatalogSnapshot<*>): BoardPage {
        val offset = offset(request, snapshot.version)
        require(offset <= boards.size) { "Catalog continuation is out of range" }
        val values = boards.drop(offset).take(request.pageSize)
        val next = offset + values.size
        return BoardPage(values, if (next < boards.size) token(request, snapshot.version, next) else null, snapshot.info())
    }
}
