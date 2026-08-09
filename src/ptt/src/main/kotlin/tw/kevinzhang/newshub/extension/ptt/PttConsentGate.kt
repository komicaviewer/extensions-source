package tw.kevinzhang.newshub.extension.ptt

import org.jsoup.Jsoup

/** Detects both PTT's server form and its current JavaScript cookie redirect. */
internal object PttConsentGate {
    fun isRequired(body: String, finalUrl: String, hasAdultConsent: Boolean = false): Boolean =
        isUnconditionallyRequired(body, finalUrl) || (hasCookieRedirect(body) && !hasAdultConsent)

    fun isUnconditionallyRequired(body: String, finalUrl: String): Boolean {
        if (finalUrl.contains("/ask/over18")) return true
        val document = Jsoup.parse(body)
        if (document.selectFirst("form[action*=over18]") != null && document.text().contains("十八歲")) {
            return true
        }
        return false
    }

    fun hasCookieRedirect(body: String): Boolean = Jsoup.parse(body).select("script").any { script ->
            script.data().contains("/ask/over18") && script.data().contains("over18=1")
    }
}
