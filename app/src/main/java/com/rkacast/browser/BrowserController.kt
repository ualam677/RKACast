package com.rkacast.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rkacast.data.MediaUtils
import org.json.JSONArray

data class DetectedMedia(
    val url: String,
    val pageUrl: String,
    val pageTitle: String,
    val userAgent: String,
    val cookies: String?,
) {
    val headers: Map<String, String>
        get() = buildMap {
            put("Referer", pageUrl)
            put("User-Agent", userAgent)
            cookies?.let { put("Cookie", it) }
        }
}

/** Holds the WebView and the list of videos sniffed from the pages it loads. */
class BrowserController {
    private val main = Handler(Looper.getMainLooper())
    var webView: WebView? = null
        private set

    var currentUrl by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableStateOf(100)
    var canGoBack by mutableStateOf(false)
    val detected = mutableStateListOf<DetectedMedia>()

    private val adHosts = listOf("doubleclick.net", "googlesyndication.com", "adservice.google", "popads.net", "propellerads", "exoclick.com")

    private val probeJs = """
        (function(){
          var out=[];
          document.querySelectorAll('video, video source, audio, audio source').forEach(function(v){
            var s=v.currentSrc||v.src; if(s && s.indexOf('blob:')!==0) out.push(s);
          });
          return JSON.stringify(out);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                // Block popups to intent://, market:// etc.
                return scheme != "http" && scheme != "https"
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (adHosts.any { url.contains(it) }) return WebResourceResponse("text/plain", "utf-8", null)
                if (MediaUtils.looksLikeMedia(url)) {
                    val referer = request.requestHeaders["Referer"]
                    main.post { addDetected(url, referer) }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != currentUrl) detected.clear()
                currentUrl = url
                canGoBack = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                title = view.title ?: ""
                canGoBack = view.canGoBack()
                probe()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
                if (newProgress == 100) probe()
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                title = t ?: ""
            }
        }
        webView = wv
        return wv
    }

    fun probe() {
        val wv = webView ?: return
        wv.evaluateJavascript(probeJs) { result ->
            runCatching {
                // result is a JSON-encoded string containing a JSON array
                val inner = org.json.JSONTokener(result).nextValue() as? String ?: return@runCatching
                val arr = JSONArray(inner)
                for (i in 0 until arr.length()) addDetected(arr.getString(i), null)
            }
        }
    }

    private fun addDetected(url: String, referer: String?) {
        if (detected.any { it.url == url }) return
        val wv = webView
        val page = referer ?: currentUrl
        detected.add(
            0,
            DetectedMedia(
                url = url,
                pageUrl = page,
                pageTitle = title,
                userAgent = wv?.settings?.userAgentString ?: "Mozilla/5.0",
                cookies = CookieManager.getInstance().getCookie(url),
            ),
        )
    }

    fun load(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            ' ' !in input && '.' in input -> "https://$input"
            else -> "https://duckduckgo.com/?q=" + android.net.Uri.encode(input)
        }
        webView?.loadUrl(url) ?: run { pending = url }
        currentUrl = url
    }

    var pending: String? = null

    fun goBack(): Boolean {
        val wv = webView ?: return false
        if (wv.canGoBack()) { wv.goBack(); return true }
        return false
    }

    fun destroy() {
        webView?.destroy()
        webView = null
    }
}
