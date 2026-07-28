package eu.kanade.tachiyomi.lib.megaupextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume

class MegaUpExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val context: Application? = null,
) {
    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }

    private val tag by lazy { javaClass.simpleName }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun encDecHeaders(url: String): Headers {
        val referer = headers["Referer"] ?: url
        val origin = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }

        return headers.newBuilder().apply {
            set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            set("Accept", "application/json, text/plain, */*")
            origin?.let { set("Origin", it) }
            set("Referer", referer)
            set("Sec-Fetch-Dest", "empty")
            set("Sec-Fetch-Mode", "cors")
            set("Sec-Fetch-Site", "cross-site")
        }
            .build()
    }

    private suspend fun unwrapIframeUrl(url: String): String {
        try {
            val parsedUrl = url.toHttpUrl()
            val iframeHeaders = headers.newBuilder().apply {
                set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                set("Referer", url)
            }
                .build()

            val html = client.newCall(GET(url, iframeHeaders))
                .awaitSuccess()
                .body.string()

            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']*(?:/e/|megaup)[^"']*)["']""", RegexOption.IGNORE_CASE)
            var realUrl = iframeRegex.find(html)?.groupValues?.getOrNull(1)

            if (!realUrl.isNullOrBlank()) {
                realUrl = fixUrl(realUrl, "${parsedUrl.scheme}://${parsedUrl.host}")

                if (!realUrl.isNullOrBlank()) {
                    Log.d(tag, "Unwrapped iframe via OkHttp: $realUrl")
                    return realUrl
                }
            }
        } catch (_: Exception) {
            Log.d(tag, "OkHttp unwrap failed (Cloudflare block), falling back to WebView...")
        }

        if (context != null) {
            Log.d(tag, "Launching background WebView to bypass Cloudflare...")
            return withTimeout(15_000) {
                unwrapWithWebView(url)
            }
        }

        Log.e(tag, "Failed to unwrap iframe. Blocked by Turnstile.")
        throw IllegalStateException("Server is protected by Cloudflare Turnstile. Cannot extract video.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun unwrapWithWebView(url: String): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context!!).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = headers["User-Agent"]
                        ?: DEFAULT_USER_AGENT

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            if (loadedUrl.contains("/cdn-cgi/")) return

                            view.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        var iframe = document.querySelector('iframe[src]');
                                        if (iframe && (iframe.src.includes('/e/') || iframe.src.includes('megaup'))) {
                                            return iframe.src;
                                        }
                                    } catch(e) {}
                                    return '';
                                })();
                                """.trimIndent(),
                            ) { result ->
                                val extractedUrl = result?.replace("\\\"", "\"")?.trim('"')?.takeIf { it.isNotEmpty() }

                                if (extractedUrl != null) {
                                    view.destroy()
                                    if (continuation.isActive) {
                                        continuation.resume(extractedUrl)
                                    }
                                }
                            }
                        }
                    }
                    loadUrl(url)
                }

                continuation.invokeOnCancellation {
                    try {
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    suspend fun videosFromUrl(
        url: String,
        serverName: String? = null,
    ): List<Video> {
        val parsedUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT

        if (parsedUrl.pathSegments.firstOrNull() == "iframe") {
            Log.d(tag, "Detected iframe wrapper. Attempting to unwrap...")
            val unwrappedUrl = unwrapIframeUrl(url)
            Log.d(tag, "Unwrapped real MegaUp URL: $unwrappedUrl")
            return videosFromUrl(unwrappedUrl, serverName)
        }

        val megaHost = "${parsedUrl.scheme}://${parsedUrl.host}"
        val host = extractHoster(parsedUrl.host).proper()
        val prefix = serverName ?: host

        Log.d(tag, "Fetching videos for $prefix from: $url")

        val token = parsedUrl.pathSegments.lastOrNull(String::isNotBlank)
            ?: throw IllegalArgumentException("No token found in URL: $url")

        val megaUrl = "$megaHost/media/$token"

        val mediaHeaders = headers.newBuilder().apply {
            set("User-Agent", userAgent)
            set("Accept", "application/json, text/plain, */*")
            set("X-Requested-With", "XMLHttpRequest")
            set("Referer", url)
        }
            .build()

        val megaToken = client.newCall(GET(megaUrl, mediaHeaders))
            .awaitSuccess()
            .parseAs<InternalEncryptedResponse>().result

        val tokenBody = buildJsonObject {
            put("text", megaToken)
            put("agent", userAgent)
        }.toString().toRequestBody("application/json".toMediaType())

        Log.d(tag, "Sending token to decryption API: https://enc-dec.app/api/dec-mega")

        val megaUpResult = client.newCall(
            POST("https://enc-dec.app/api/dec-mega", body = tokenBody, headers = encDecHeaders(url)),
        ).awaitSuccess()
            .parseAs<InternalTokenResponse>().result

        val subtitleTracks = megaUpResult.subtitleTracks()

        val videoHeaders = headers.newBuilder().apply {
            set("User-Agent", userAgent)
            set("Origin", megaHost)
            set("Referer", "$megaHost/")
        }
            .build()

        return megaUpResult.sources.flatMap {
            val videoUrl = it.file
            when {
                m3u8Regex.containsMatchIn(videoUrl) -> {
                    Log.d(tag, "m3u8 URL found: $videoUrl")
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        referer = "$megaHost/",
                        subtitleList = subtitleTracks,
                        videoNameGen = { quality -> "$prefix: $quality" },
                    )
                }

                mpdRegex.containsMatchIn(videoUrl) -> {
                    Log.d(tag, "mpd URL found: $videoUrl")
                    playlistUtils.extractFromDash(
                        mpdUrl = videoUrl,
                        videoNameGen = { quality -> "$prefix: $quality" },
                        subtitleList = subtitleTracks,
                        referer = "$megaHost/",
                    )
                }

                mp4Regex.containsMatchIn(videoUrl) -> {
                    Log.d(tag, "mp4 URL found: $videoUrl")
                    Video(
                        url = videoUrl,
                        quality = "$prefix: MP4",
                        videoUrl = videoUrl,
                        headers = videoHeaders,
                        subtitleTracks = subtitleTracks,
                    ).let(::listOf)
                }

                else -> emptyList()
            }
        }
    }

    private val m3u8Regex by lazy { Regex(""".+\.m3u8(?:\?.*)?$""", RegexOption.IGNORE_CASE) }
    private val mpdRegex by lazy { Regex(""".+\.mpd(?:\?.*)?$""", RegexOption.IGNORE_CASE) }
    private val mp4Regex by lazy { Regex(""".+\.mp4(?:\?.*)?$""", RegexOption.IGNORE_CASE) }

    private val hosterRegex by lazy { Regex("""([^.]+)\.[^.]+$""") }

    private fun extractHoster(host: String): String = hosterRegex.find(host)?.groupValues?.getOrNull(1) ?: host

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }

    private fun fixUrl(url: String, baseUrl: String): String? {
        val resolved = url.toHttpUrlOrNull()
        if (resolved != null) return resolved.toString()
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        return try {
            base.resolve(url)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class InternalEncryptedResponse(val result: String)

    @Serializable
    private data class InternalTokenResponse(val result: InternalMegaUpResult)

    @Serializable
    private data class InternalMegaUpResult(
        val sources: List<InternalMegaUpSource>,
        val tracks: List<InternalMegaUpTrack> = emptyList(),
    ) {
        fun subtitleTracks(): List<Track> = tracks
            .filter { it.kind == "captions" && it.file.endsWith(".vtt", ignoreCase = true) }
            .sortedByDescending { it.default }
            .map { Track(it.file, it.label ?: "Unknown") }
    }

    @Serializable
    private data class InternalMegaUpSource(val file: String)

    @Serializable
    private data class InternalMegaUpTrack(
        val file: String,
        val label: String? = null,
        val kind: String,
        val default: Boolean = false,
    )
}
