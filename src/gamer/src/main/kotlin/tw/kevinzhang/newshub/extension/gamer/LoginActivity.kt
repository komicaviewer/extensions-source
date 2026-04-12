package tw.kevinzhang.newshub.extension.gamer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl.Companion.toHttpUrl

class LoginActivity : Activity() {

    companion object {
        const val EXTRA_COOKIE_URL = "cookie_url"
        const val EXTRA_RAW_COOKIES = "raw_cookies"
        private const val LOGIN_URL = "https://user.gamer.com.tw/login.php"
    }

    private lateinit var webView: WebView
    private val loginHost = LOGIN_URL.toHttpUrl().host

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val currentHost = runCatching { url.toHttpUrl().host }.getOrNull() ?: return
                    if (currentHost != loginHost) {
                        // Navigated away from login domain — login succeeded.
                        val cookieManager = CookieManager.getInstance()
                        val rawCookies = cookieManager.getCookie(LOGIN_URL) ?: return
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(EXTRA_COOKIE_URL, LOGIN_URL)
                            putExtra(EXTRA_RAW_COOKIES, rawCookies)
                        })
                        finish()
                    }
                }
            }
            loadUrl(LOGIN_URL)
        }
        setContentView(webView)
    }

    @Deprecated("Deprecated in API 33 but still functional for minSdk 21")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else {
            setResult(RESULT_CANCELED)
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
