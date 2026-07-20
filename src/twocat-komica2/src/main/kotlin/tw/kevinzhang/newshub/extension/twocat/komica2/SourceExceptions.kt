package tw.kevinzhang.newshub.extension.twocat.komica2

internal class HttpException(code: Int, url: String) : Exception("HTTP $code: $url")
