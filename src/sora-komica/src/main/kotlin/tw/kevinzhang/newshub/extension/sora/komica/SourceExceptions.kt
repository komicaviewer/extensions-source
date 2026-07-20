package tw.kevinzhang.newshub.extension.sora.komica

internal class HttpException(code: Int, url: String) : Exception("HTTP $code: $url")
