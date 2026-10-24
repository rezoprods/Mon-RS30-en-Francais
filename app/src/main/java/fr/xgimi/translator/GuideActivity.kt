package fr.xgimi.translator

import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/** Guide local chinois/français des menus (hors ligne). */
class GuideActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true // requis par la recherche et les onglets du guide
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.setBackgroundColor(android.graphics.Color.rgb(13, 10, 23))
        setContentView(web)
        web.loadUrl("file:///android_asset/guide.html")
    }

    @Deprecated("Conservé pour la navigation à la télécommande")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
