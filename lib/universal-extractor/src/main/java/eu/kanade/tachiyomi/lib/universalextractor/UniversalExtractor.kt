package eu.kanade.tachiyomi.lib.universalextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class UniversalExtractor(private val client: OkHttpClient) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(
        origRequestUrl: String,
        origRequestHeader: Headers,
        customQuality: String? = null,
        prefix: String = "",
        timeoutSec: Long = TIMEOUT_SEC,
        requiresUserGesture: Boolean = true,
    ): List<Video> {
        val host = origRequestUrl.toHttpUrl().host.substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()
        val shouldTraceRequests = origRequestUrl.toHttpUrl().host.contains("p2pplay.online")

        Log.d(
            TAG,
            "start host=${origRequestUrl.safeHost()} path=${origRequestUrl.safePath()} timeoutSec=$timeoutSec " +
                "requiresUserGesture=$requiresUserGesture hasReferer=${origRequestHeader["Referer"] != null}",
        )

        handler.post {
            val newView = WebView(context)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                mediaPlaybackRequiresUserGesture = requiresUserGesture
                userAgentString = origRequestHeader["User-Agent"]
            }
            newView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (shouldTraceRequests) {
                        Log.d(
                            TAG,
                            "console ${consoleMessage.messageLevel()} " +
                                consoleMessage.message().safeLogMessage(),
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }
            newView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (shouldTraceRequests) {
                        Log.d(TAG, "page finished host=${url.orEmpty().safeHost()} path=${url.orEmpty().safePath()}")
                        view?.triggerPlaybackStart(delayMs = 0)
                        view?.triggerPlaybackStart(delayMs = 1_500)
                        view?.triggerPlaybackStart(delayMs = 5_000)
                    }
                    super.onPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (shouldTraceRequests && request != null) {
                        Log.d(
                            TAG,
                            "web error host=${request.url.toString().safeHost()} path=${request.url.toString().safePath()} " +
                                "code=${error?.errorCode} desc=${error?.description?.toString().safeLogMessage()}",
                        )
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (shouldTraceRequests && request != null) {
                        Log.d(
                            TAG,
                            "http error host=${request.url.toString().safeHost()} path=${request.url.toString().safePath()} " +
                                "status=${errorResponse?.statusCode}",
                        )
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (shouldTraceRequests && P2PPLAY_TRACE_REGEX.containsMatchIn(url)) {
                        Log.d(TAG, "request host=${url.safeHost()} path=${url.safePath()}")
                    }
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        Log.d(TAG, "media intercepted host=${url.safeHost()} path=${url.safePath()}")
                        resultUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl, headers)
        }

        latch.await(timeoutSec, TimeUnit.SECONDS)

        if (resultUrl.isBlank()) {
            Log.d(TAG, "timeout/no media host=${origRequestUrl.safeHost()} timeoutSec=$timeoutSec")
        }

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        // terabox special case start
        if ("M3U8_AUTO_360" in resultUrl) {
            val qualities = listOf("1080", "720", "480", "360")
            val allVideos = mutableListOf<Video>()

            for (quality in qualities) {
                val modifiedUrl = resultUrl.replace("M3U8_AUTO_360", "M3U8_AUTO_$quality")
                val videos = playlistUtils.extractFromHls(modifiedUrl, origRequestUrl, videoNameGen = { "$prefix - $host: ${stnQuality(it)} $quality" + "p" })

                if (videos.isNotEmpty()) {
                    allVideos.addAll(videos)
                }
            }

            if (allVideos.isNotEmpty()) {
                return allVideos
            }
        }
        // terabox special case end

        return when {
            "m3u8" in resultUrl -> {
                Log.d(TAG, "m3u8 host=${resultUrl.safeHost()} path=${resultUrl.safePath()}")
                playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$prefix - $host: ${stnQuality(it)}" })
            }
            "mpd" in resultUrl -> {
                Log.d(TAG, "mpd host=${resultUrl.safeHost()} path=${resultUrl.safePath()}")
                playlistUtils.extractFromDash(resultUrl, { it -> "$prefix - $host: $it" }, referer = origRequestUrl)
            }
            "mp4" in resultUrl -> {
                Log.d(TAG, "mp4 host=${resultUrl.safeHost()} path=${resultUrl.safePath()}")
                Video(resultUrl, "$prefix - $host: ${customQuality ?: "Mirror"}", resultUrl, origRequestHeader.newBuilder().add("referer", origRequestUrl).build()).let(::listOf)
            }
            else -> emptyList()
        }
    }

    private fun String.safeHost(): String = runCatching { toHttpUrl().host }.getOrDefault("invalid")

    private fun String.safePath(): String = runCatching { toHttpUrl().encodedPath }.getOrDefault("invalid")

    private fun String?.safeLogMessage(): String = orEmpty()
        .replace(Regex("https?://\\S+")) { match ->
            "${match.value.safeHost()}${match.value.safePath()}"
        }
        .take(180)

    private fun WebView.triggerPlaybackStart(delayMs: Long) {
        this@UniversalExtractor.handler.postDelayed(
            {
                evaluateJavascript(
                    """
                    (function() {
                      const emit = function(target, type) {
                        try {
                          target.dispatchEvent(new Event(type, { bubbles: true, cancelable: true }));
                        } catch (_) {}
                      };
                      const targets = [
                        document.querySelector('#overlay'),
                        document.querySelector('#playback'),
                        document.querySelector('.jwplayer'),
                        document.querySelector('[role="button"]'),
                        document.querySelector('button'),
                        document.querySelector('video'),
                        document.body,
                        document.documentElement
                      ].filter(Boolean);
                      targets.forEach(function(target) {
                        ['pointerdown', 'mousedown', 'touchstart', 'click', 'mouseup', 'touchend'].forEach(function(type) {
                          emit(target, type);
                        });
                        try {
                          if (typeof target.click === 'function') target.click();
                        } catch (_) {}
                      });
                      const video = document.querySelector('video');
                      if (video) {
                        try {
                          video.muted = true;
                          const promise = video.play();
                          if (promise && typeof promise.catch === 'function') {
                            promise.catch(function() {});
                          }
                        } catch (_) {
                        }
                      }
                      if (window.jwplayer) {
                        try {
                          const jw = window.jwplayer();
                          if (jw && typeof jw.play === 'function') jw.play();
                        } catch (_) {
                        }
                      }
                      return 'playback-triggered';
                    })();
                    """.trimIndent(),
                ) { result ->
                    Log.d(TAG, "playback trigger delayMs=$delayMs result=${result.safeLogMessage()}")
                }
            },
            delayMs,
        )
    }

    private fun stnQuality(quality: String): String {
        val intQuality = quality.trim().toInt()
        val standardQualities = listOf(144, 240, 360, 480, 720, 1080)
        val result =  standardQualities.minByOrNull { abs(it - intQuality) } ?: quality
        return "${result}p"
    }

    private fun String.proper(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(
            Locale.getDefault()) else it.toString() }
    }

    companion object {
        private const val TAG = "UniversalExtractor"
        const val TIMEOUT_SEC: Long = 10
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$") }
        private val P2PPLAY_TRACE_REGEX by lazy { Regex("p2pplay\\.online|/api/v1/|/hlsmod/|/player/|jwplayer|provider", RegexOption.IGNORE_CASE) }
    }
}
