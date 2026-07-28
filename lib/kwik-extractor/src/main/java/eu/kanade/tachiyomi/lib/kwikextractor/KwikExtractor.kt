package eu.kanade.tachiyomi.lib.kwikextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import dev.datlag.jsunpacker.JsUnpacker
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class KwikExtractor(
    client: OkHttpClient,
    private val headers: Headers,
) {

    private val kwikClient: OkHttpClient = client.newBuilder()
        .addInterceptor(CloudflareInterceptor(client))
        .build()

    private val playlistUtils by lazy { PlaylistUtils(kwikClient, headers) }

    fun videosFromUrl(
        url: String,
        prefix: String = "",
        subtitleList: List<Track> = emptyList(),
    ): List<Video> {
        Log.d(TAG, "videosFromUrl: starting extraction for ${url.take(120)}")

        val qualityLabel = prefix.takeIf(String::isNotBlank)?.let { "$it " } ?: ""

        return try {
            val videoUrl = getHlsStreamUrl(url)
            val videoHeaders = Headers.Builder()
                .set("Referer", "https://kwik.cx/")
                .set("Origin", "https://kwik.cx")
                .build()

            when {
                "m3u8" in videoUrl -> {
                    Log.d(TAG, "Extracting HLS from ${videoUrl.take(100)}")
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        referer = "https://kwik.cx/",
                        masterHeaders = videoHeaders,
                        videoHeaders = videoHeaders,
                        videoNameGen = { "${qualityLabel}Kwik - $it" },
                        subtitleList = subtitleList,
                    )
                }
                else -> listOf(
                    Video(videoUrl, "${qualityLabel}Kwik", videoUrl, videoHeaders, subtitleTracks = subtitleList),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun getHlsStreamUrl(kwikUrl: String): String {
        Log.d(TAG, "getHlsStreamUrl: $kwikUrl")

        val html = fetchEPage(kwikUrl)

        return parseHlsFromHtml(html, kwikUrl)
    }

    private fun fetchEPage(kwikUrl: String): String {
        kwikClient.newCall(GET(kwikUrl, buildEPageHeaders())).execute().use { response ->
            val body = response.body.string()
            if (body.contains("eval(function(")) {
                Log.d(TAG, "Fetch succeeded (${body.length} bytes)")
                return body
            }
            throw ExtractionException("kwik.cx returned page without packed scripts (${body.length} bytes, HTTP ${response.code})")
        }
    }

    private fun parseHlsFromHtml(html: String, baseUrl: String): String {
        val document = Jsoup.parse(html, baseUrl)
        val packedScripts = document.select("script:containsData(eval\\(function)")

        if (packedScripts.isEmpty()) {
            throw ExtractionException("No packed scripts found (${html.length} bytes)")
        }

        for (script in packedScripts) {
            val scriptData = script.data()
            val evalPositions = mutableListOf<Int>()
            var searchFrom = 0
            while (true) {
                val idx = scriptData.indexOf("eval(function(", searchFrom)
                if (idx == -1) break
                evalPositions.add(idx)
                searchFrom = idx + 1
            }

            for (pos in evalPositions.reversed()) {
                val packedContent = scriptData.substring(pos + "eval(".length)
                if (packedContent.isBlank()) continue
                try {
                    JsUnpacker.unpackAndCombine("eval($packedContent")?.let { unpacked ->
                        extractM3u8FromUnpacked(unpacked)?.let { return it }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unpack failed at pos $pos: ${e.message}")
                }
            }
        }

        throw ExtractionException("Could not extract m3u8 from kwik.cx page")
    }

    private fun extractM3u8FromUnpacked(unpacked: String): String? {
        val patterns = listOf(
            "const source=\\'" to "\\';",
            "var source=\\'" to "\\';",
            "const source='" to "';",
            "var source='" to "';",
            "const source=\"" to "\";",
            "var source=\"" to "\";",
        )
        for ((prefix, suffix) in patterns) {
            unpacked.substringAfter(prefix).substringBefore(suffix).let { url ->
                if (url.startsWith("http") && ".m3u8" in url) return url
            }
        }
        return M3U8_REGEX.find(unpacked)?.groupValues?.get(1)
    }

    private fun buildEPageHeaders(): Headers = Headers.Builder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Sec-Fetch-Dest", "iframe")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "cross-site")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .build()

    private class ExtractionException(message: String) : Exception(message)

    companion object {
        private const val TAG = "KwikExtractor"
        private val M3U8_REGEX = Regex("""(https?://[^\s\\'"]+\.m3u8[^\s\\'"]*?)""")
    }
}
