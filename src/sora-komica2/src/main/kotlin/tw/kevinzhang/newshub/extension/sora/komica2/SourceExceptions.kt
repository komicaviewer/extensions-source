package tw.kevinzhang.newshub.extension.sora.komica2

internal class HttpException(code: Int, url: String) : Exception("HTTP $code: $url")
