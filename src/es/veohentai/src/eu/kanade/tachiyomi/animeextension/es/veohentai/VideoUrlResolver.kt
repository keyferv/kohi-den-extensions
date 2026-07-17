package eu.kanade.tachiyomi.animeextension.es.veohentai

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class VideoUrlResult(val url: String, val cookies: List<Cookie>, val referer: String)

class VideoUrlResolver() {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun getVideoUrl(origRequestUrl: String, origRequestheader: Headers): VideoUrlResult {
        Log.d(TAG, "Loading URL: $origRequestUrl")
        val latch = CountDownLatch(1)
        val r2WarmupLatch = CountDownLatch(1)
        val isR2WarmupStarted = AtomicBoolean(false)
        var webView: WebView? = null
        var resultUrl = ""
        var r2WarmupUrl = ""
        val headers = origRequestheader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = origRequestheader["User-Agent"]
            }
            webview.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.d(TAG, "Page started: $url")
                    if (url == r2WarmupUrl) {
                        isR2WarmupStarted.set(true)
                    }
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "Page finished: $url")
                    if (url == r2WarmupUrl) {
                        r2WarmupLatch.countDown()
                    }
                    super.onPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    val url = request?.url?.toString().orEmpty()
                    if (url == r2WarmupUrl) {
                        Log.d(TAG, "Media warmup WebView error: ${error?.errorCode} ${error?.description}")
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    val url = request?.url?.toString().orEmpty()
                    if (url == r2WarmupUrl) {
                        Log.d(TAG, "Media warmup HTTP status: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase}")
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val requestUrl = request.url
                    val url = requestUrl.toString()
                    Log.d(TAG, "Intercepted: $url")
                    val videoUrl = extractVideoUrl(requestUrl)
                    if (videoUrl != null) {
                        Log.d(TAG, "CAPTURED: $videoUrl")
                        resultUrl = videoUrl
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl, headers)
        }

        val captured = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        Log.d(TAG, "Latch released — ${if (captured) "captured" else "timed out"}")

        if (resultUrl.isNotBlank()) {
            r2WarmupUrl = resultUrl
            Log.d(TAG, "Media warmup started")
            handler.post {
                webView?.loadUrl(resultUrl, buildR2WarmupHeaders(origRequestUrl, origRequestheader))
            }
            val r2WarmupFinished = r2WarmupLatch.await(R2_WARMUP_TIMEOUT_SEC, TimeUnit.SECONDS)
            Log.d(
                TAG,
                "Media warmup finished: ${if (r2WarmupFinished) "finished" else if (isR2WarmupStarted.get()) "timed out after start" else "timed out before start"}",
            )
        }

        val cookies = if (resultUrl.isNotBlank()) {
            CookieManager.getInstance()?.flush()
            CookieManager.getInstance()
                ?.getCookie(resultUrl)
                ?.split(";")
                ?.mapNotNull { Cookie.parse(resultUrl.toHttpUrl(), it.trim()) }
                ?: emptyList()
        } else {
            emptyList()
        }
        Log.d(TAG, "Media cookies extracted: ${cookies.size}")
        Log.d(TAG, "Media cookie names: ${cookies.joinToString { it.name }}")
        Log.d(TAG, "Cookies extracted: ${cookies.size}")

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        Log.d(TAG, "Result: ${if (resultUrl.isNotBlank()) resultUrl else "EMPTY"}")
        return VideoUrlResult(url = resultUrl, cookies = cookies, referer = origRequestUrl)
    }

    companion object {
        const val TIMEOUT_SEC: Long = 25
        private const val R2_WARMUP_TIMEOUT_SEC: Long = 10
        private const val TAG = "VideoUrlResolver"
        private val VIDEO_REGEX by lazy {
            Regex("^https://(r2\\.1hanime\\.com|cdn\\.hentaiplayer\\.com)/.+\\.(mp4|m3u8)(\\?|$)")
        }

        private fun buildR2WarmupHeaders(playerUrl: String, originalHeaders: Headers): Map<String, String> {
            val acceptLanguage = originalHeaders["Accept-Language"] ?: "en,de;q=0.9"
            return mapOf(
                "Referer" to playerUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to acceptLanguage,
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-User" to "?1",
            )
        }

        private fun extractVideoUrl(requestUrl: Uri): String? {
            val url = requestUrl.toString()
            if (VIDEO_REGEX.containsMatchIn(url)) return url

            val mediaUrl = requestUrl.getQueryParameter("mu") ?: return null
            return mediaUrl.takeIf { VIDEO_REGEX.containsMatchIn(it) }
        }
    }
}
