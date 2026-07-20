package tw.kevinzhang.newshub.extension.twocat.komica

internal class HttpException(code: Int, url: String) : Exception("HTTP $code: $url")
