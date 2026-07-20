package tw.kevinzhang.newshub.extension.ptt

import org.jsoup.Jsoup

/** Detects both PTT's server form and its current JavaScript cookie redirect. */
internal object PttConsentGate {
    fun isRequired(body: String, finalUrl: String, cookieHeader: String? = null): Boolean {
        if (finalUrl.contains("/ask/over18")) return true
        val document = Jsoup.parse(body)
        if (document.selectFirst("form[action*=over18]") != null && document.text().contains("十八歲")) {
            return true
        }
        val hasConsentCookie = cookieHeader.orEmpty()
            .split(';')
            .any { it.trim() == "over18=1" }
        return !hasConsentCookie && document.select("script").any { script ->
            script.data().contains("/ask/over18") && script.data().contains("over18=1")
        }
    }
}
