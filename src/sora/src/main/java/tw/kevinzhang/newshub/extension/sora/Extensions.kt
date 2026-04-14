package tw.kevinzhang.newshub.extension.sora

import okhttp3.HttpUrl
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Element
import tw.kevinzhang.komica_api.model.boards
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun HttpUrl.toKBoard() =
    boards().first { toString().contains(it.url) }

fun HttpUrl.Builder.setFilename(nameWithExtension: String?): HttpUrl.Builder {
    val lastIndex = build().pathSegments.lastIndex
    if (nameWithExtension != null) {
        setPathSegment(lastIndex, nameWithExtension)
    } else {
        removePathSegment(lastIndex)
    }
    return this
}

fun HttpUrl.Builder.addFilename(name: String, extension: String): HttpUrl.Builder {
    addPathSegment("$name.$extension")
    return this
}

fun HttpUrl.Builder.removeFilename(): HttpUrl.Builder {
    if (this.build().isFile()) {
        removePathSegment(build().pathSegments.lastIndex)
    }
    return this
}

fun HttpUrl.Builder.removeFilename(extension: String): HttpUrl.Builder {
    if (this.build().isFile(extension)) {
        removePathSegment(build().pathSegments.lastIndex)
    }
    return this
}

fun HttpUrl.isFile(extension: String): Boolean {
    if (!isFile()) return false
    val pathSegments = pathSegments
    val last = pathSegments.last()
    return last.endsWith(".$extension")
}

fun HttpUrl.isFile(): Boolean {
    val pathSegments = pathSegments
    val last = pathSegments.last()
    return last.contains(".")
}

fun Int?.isZeroOrNull() = this == 0 || this == null

fun Element.toResponseBody(): ResponseBody {
    return this.toString().toResponseBody()
}

fun String.toTimestamp(pattern: String, zoneId: String = "Asia/Taipei"): Long {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    val localDateTime = LocalDateTime.parse(this, formatter)
    return localDateTime.atZone(ZoneId.of(zoneId)).toInstant().toEpochMilli()
}