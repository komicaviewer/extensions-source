package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.yield
import org.jsoup.Jsoup
import java.security.MessageDigest

internal data class EynyChallenge(val challenge: String, val timestamp: String, val difficulty: Int, val cookiePrefix: String)
internal object EynyChallengeSolver {
    const val MAX_DIFFICULTY = 6
    const val MAX_NONCE = 2_000_000L
    const val MAX_MILLIS = 8_000L
    fun parse(html: String): EynyChallenge? {
        val script = Jsoup.parse(html).select("script").joinToString("\n") { it.data() }
        val challenge = Regex("(?:var\\s+)?challenge\\s*=\\s*[\\\"']([a-fA-F0-9]{16,128})").find(script)?.groupValues?.get(1) ?: return null
        val ts = Regex("(?:var\\s+)?ts\\s*=\\s*[\\\"'](\\d{6,20})").find(script)?.groupValues?.get(1) ?: return null
        val diff = Regex("(?:var\\s+)?diff\\s*=\\s*(\\d+)").find(script)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val prefix = Regex("document\\.cookie\\s*=\\s*[\\\"']([^\\\"']+?)_n=").find(script)?.groupValues?.get(1) ?: return null
        require(diff in 1..MAX_DIFFICULTY) { "EYNY challenge difficulty is outside the local safety limit" }
        return EynyChallenge(challenge, ts, diff, prefix)
    }
    suspend fun solve(value: EynyChallenge): Long? {
        if (value.difficulty !in 1..MAX_DIFFICULTY) return null
        val began = System.nanoTime()
        val digest = MessageDigest.getInstance("SHA-256")
        for (nonce in 0..MAX_NONCE) {
            if (nonce and 0x3ffL == 0L) {
                // yield is cancellable and prevents a bounded solve from monopolising a caller thread.
                yield()
                if ((System.nanoTime() - began) / 1_000_000L > MAX_MILLIS) return null
            }
            val text = "${value.challenge}|${value.timestamp}|$nonce"
            val hash = digest.digest(text.toByteArray())
            if (hasLeadingZeroHexDigits(hash, value.difficulty)) return nonce
        }
        return null
    }

    private fun hasLeadingZeroHexDigits(hash: ByteArray, digits: Int): Boolean {
        val wholeBytes = digits / 2
        for (index in 0 until wholeBytes) {
            if (hash[index].toInt() != 0) return false
        }
        return digits % 2 == 0 || hash[wholeBytes].toInt() and 0xf0 == 0
    }
}
