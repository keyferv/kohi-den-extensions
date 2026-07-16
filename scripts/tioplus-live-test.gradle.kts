import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    base
}

tasks.register("tioplusLiveTest") {
    doLast {
        val baseUrl = "https://tioplus.app"
        val episodePath = "/serie/el-mentalista/season/4/episode/6"
        val episodeUrl = "$baseUrl$episodePath"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
        val aesKey = "kiemtienmua911ca".toByteArray()
        val aesIv = "1234567890oiuytr".toByteArray()
        CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))

        data class TimedFetch(val status: Int, val finalUrl: String, val body: String, val ms: Long)
        data class MediaFetch(val status: Int, val finalUrl: String, val contentType: String, val byteCount: Int, val ms: Long)
        data class Probe(
            val label: String,
            val host: String,
            val redirectUrl: String,
            val status: String,
            val totalMs: Long,
            val streamUrl: String = "",
            val evidence: String = "",
        )

        fun <T> timed(block: () -> T): Pair<T, Long> {
            val start = System.nanoTime()
            return block() to ((System.nanoTime() - start) / 1_000_000)
        }

        fun fetch(pathOrUrl: String, referer: String? = null, origin: String? = null, timeoutMs: Int = 8_000): TimedFetch {
            val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$baseUrl$pathOrUrl"
            val (result, ms) = timed {
                runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
                    referer?.let { setRequestProperty("Referer", it) }
                    origin?.let { setRequestProperty("Origin", it) }
                }
                try {
                    val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                    Triple(conn.responseCode, conn.url.toString(), stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                } finally {
                    conn.disconnect()
                }
                }.getOrElse { Triple(0, url, "ERROR: ${it::class.simpleName}: ${it.message}") }
            }
            return TimedFetch(result.first, result.second, result.third, ms)
        }

        fun fetchMedia(url: String, referer: String, origin: String, timeoutMs: Int = 5_000): MediaFetch {
            val (result, ms) = timed {
                runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
                    setRequestProperty("Origin", origin)
                    setRequestProperty("Referer", referer)
                }
                try {
                    val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                    val bytes = stream?.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        input.read(buffer).coerceAtLeast(0)
                    } ?: 0
                    MediaFetch(conn.responseCode, conn.url.toString(), conn.contentType.orEmpty(), bytes, 0)
                } finally {
                    conn.disconnect()
                }
                }.getOrElse { MediaFetch(0, url, "ERROR: ${it::class.simpleName}: ${it.message}", 0, 0) }
            }
            return result.copy(ms = ms)
        }

        fun resolveUrl(base: String, value: String): String = URI(base).resolve(value.trim()).toString()

        fun firstMediaLine(playlist: String): String = playlist
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            .orEmpty()

        fun validateHls(playlistUrl: String, referer: String): Pair<String, String> {
            val origin = URI(referer).let { "${it.scheme}://${it.host}" }
            val playlist = fetch(playlistUrl, referer, origin, timeoutMs = 5_000)
            if (playlist.status != 200 || !playlist.body.startsWith("#EXTM3U")) {
                return "NO_STREAM" to "playlist status=${playlist.status}; playlistMs=${playlist.ms}; body=${playlist.body.take(80)}"
            }

            val variantUrl = if (playlist.body.contains("#EXT-X-STREAM-INF")) {
                resolveUrl(playlist.finalUrl, firstMediaLine(playlist.body))
            } else {
                playlist.finalUrl
            }

            val mediaPlaylist = if (variantUrl == playlist.finalUrl) playlist else fetch(variantUrl, referer, origin, timeoutMs = 5_000)
            if (mediaPlaylist.status != 200 || !mediaPlaylist.body.startsWith("#EXTM3U")) {
                return "NO_STREAM" to "variant=$variantUrl; variant status=${mediaPlaylist.status}; variantMs=${mediaPlaylist.ms}; body=${mediaPlaylist.body.take(80)}"
            }

            val segment = firstMediaLine(mediaPlaylist.body).takeIf { it.isNotBlank() }
                ?: return "NO_STREAM" to "variant=$variantUrl; no media segment found"
            val segmentUrl = resolveUrl(mediaPlaylist.finalUrl, segment)
            val segmentFetch = fetchMedia(segmentUrl, referer, origin, timeoutMs = 5_000)
            val isMediaContent = segmentFetch.contentType.startsWith("video/") || segmentFetch.contentType == "application/octet-stream"
            val ok = segmentFetch.status in 200..299 && segmentFetch.byteCount > 0 && isMediaContent
            val evidence = "playlistMs=${playlist.ms}; variant=$variantUrl; variantMs=${mediaPlaylist.ms}; segment=${segmentFetch.finalUrl}; segment status=${segmentFetch.status}; segmentMs=${segmentFetch.ms}; contentType=${segmentFetch.contentType}; bytes=${segmentFetch.byteCount}"
            return if (ok) "PLAYABLE_HLS" to evidence else "NO_STREAM" to evidence
        }

        fun decryptPelisPlusPayload(hex: String): String {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            val clean = hex.trim()
            val bytes = ByteArray(clean.length / 2) { index ->
                clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return String(cipher.doFinal(bytes))
        }

        fun jsonString(json: String, key: String): String =
            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                .orEmpty()

        fun resolvePlayer(dataServer: String): Pair<String, Long> {
            val encoded = Base64.getEncoder().encodeToString(dataServer.toByteArray())
            val player = fetch("$baseUrl/player/$encoded", episodeUrl)
            val redirect = Regex("window\\.location\\.href\\s*=\\s*['\"]([^'\"]+)")
                .find(player.body)
                ?.groupValues
                ?.get(1)
                ?: Regex("<iframe[^>]+src=['\"]([^'\"]+)")
                    .find(player.body)
                    ?.groupValues
                    ?.get(1)
                ?: ""
            return redirect to player.ms
        }

        fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

        fun probePelisPlusPlayer(url: String, label: String, playerMs: Long): Probe {
            val hash = url.substringAfter("#", "").substringBefore("&")
            val origin = url.substringBefore("/#").substringBefore("#")
            if (hash.isBlank()) return Probe(label, hostOf(url), url, "NO_STREAM", playerMs, evidence = "missing hash")
            val (probe, probeMs) = timed {
                fetch(url, origin)
                fetch("$origin/api/v1/info?id=$hash", origin)
                val video = fetch("$origin/api/v1/video?id=$hash&w=1600&h=900&r=", origin)
                if (video.status != 200) {
                    return@timed Triple("NO_STREAM", "", "video endpoint status=${video.status}; endpointMs=${video.ms}; body=${video.body.take(80)}")
                }
                val json = runCatching { decryptPelisPlusPayload(video.body) }.getOrElse {
                    return@timed Triple("NO_STREAM", "", "decrypt failed: ${it.message}")
                }
                val hlsFields = listOf("source", "cf", "cfNative", "hlsVideoTiktok", "hlsVideoGoogle")
                    .mapNotNull { key -> jsonString(json, key).takeIf { it.startsWith("http") }?.let { key to it } }
                if (hlsFields.isEmpty()) return@timed Triple("NO_STREAM", "", "no HLS field in decrypted JSON")

                val attempts = hlsFields.map { (key, hls) ->
                    val (status, evidence) = validateHls(hls, origin)
                    Triple(status, hls, "$key: $evidence")
                }
                val playable = attempts.firstOrNull { it.first == "PLAYABLE_HLS" }
                playable ?: Triple("NO_STREAM", attempts.first().second, attempts.joinToString(" | ") { it.third })
            }
            return Probe(label, hostOf(url), url, probe.first, playerMs + probeMs, probe.second, probe.third)
        }

        fun probeEmTurbo(url: String, label: String, playerMs: Long): Probe {
            val (probe, probeMs) = timed {
                val body = fetch(url, baseUrl)
                val hls = Regex("urlPlay\\s*=\\s*['\"]([^'\"]+)").find(body.body)?.groupValues?.get(1).orEmpty()
                if (hls.isBlank()) return@timed Triple("NO_STREAM", "", "urlPlay not found; pageMs=${body.ms}")
                val (status, evidence) = validateHls(hls, url)
                Triple(status, hls, "pageMs=${body.ms}; $evidence")
            }
            return Probe(label, hostOf(url), url, probe.first, playerMs + probeMs, probe.second, probe.third)
        }

        fun probeExternal(url: String, label: String, playerMs: Long, status: String, evidence: String): Probe =
            Probe(label, hostOf(url), url, status, playerMs, evidence = evidence)

        fun probeServer(label: String, redirectUrl: String, playerMs: Long): Probe = when {
            redirectUrl.contains("emturbovid", true) -> probeEmTurbo(redirectUrl, label, playerMs)
            listOf("upns.pro", "rpmstream.live", "strp2p.com", "4meplayer.pro").any { it in redirectUrl.lowercase() } -> probePelisPlusPlayer(redirectUrl, label, playerMs)
            redirectUrl.contains("vidhide", true) -> probeExternal(redirectUrl, label, playerMs, "EXTERNAL_EXTRACTOR", "handled by VidHideExtractor in extension")
            listOf("waaw", "netu", "hqq").any { it in redirectUrl.lowercase() } -> probeExternal(redirectUrl, label, playerMs, "EXTERNAL_EXTRACTOR", "handled by UniversalExtractor/WebView in extension")
            else -> probeExternal(redirectUrl, label, playerMs, "UNKNOWN", "no inline probe")
        }

        fun assertTrue(name: String, condition: Boolean, detail: String = "") {
            if (!condition) error("$name failed${if (detail.isNotBlank()) ": $detail" else ""}")
            println("[OK] $name")
        }

        val episode = fetch(episodeUrl)
        assertTrue("episode HTTP 200", episode.status == 200, episode.finalUrl)

        val serverRegex = Regex("<li[^>]+data-server=['\"]([^'\"]+)['\"][\\s\\S]*?<span>([^<]+)</span>")
        val serverRows = serverRegex.findAll(episode.body).toList()
        assertTrue("episode exposes four options", serverRows.size == 4, "size=${serverRows.size}")

        val probes = serverRows.map { match ->
            val label = match.groupValues[2]
            val (redirect, playerMs) = resolvePlayer(match.groupValues[1])
            if (redirect.isBlank()) {
                Probe(label, "", "", "NO_REDIRECT", playerMs)
            } else {
                probeServer(label, redirect, playerMs)
            }
        }

        println("\nTioPlus live episode: $episodeUrl")
        println("Episode fetch: status=${episode.status}; ms=${episode.ms}; bytes=${episode.body.length}")
        println("\nServer probes:")
        probes.forEach { probe ->
            println("- ${probe.label}: ${probe.status}; host=${probe.host}; totalMs=${probe.totalMs}")
            println("  redirect=${probe.redirectUrl}")
            println("  stream=${probe.streamUrl.ifBlank { "-" }}")
            if (probe.evidence.isNotBlank()) println("  evidence=${probe.evidence}")
        }

        assertTrue("Earnvids resolves to VidHide", probes.any { it.label.contains("Earnvids", true) && it.host.contains("vidhide", true) })
        assertTrue("UPFAST has playable HLS", probes.any { it.label.contains("UPFAST", true) && it.status == "PLAYABLE_HLS" })
        assertTrue("Netu resolves to Waaw", probes.any { it.label.contains("Netu", true) && it.host.contains("waaw", true) })
        assertTrue("Plus resolves to EmTurbo", probes.any { it.label.contains("Plus", true) && it.host.contains("emturbovid", true) })
        assertTrue("at least one fast playable stream", probes.any { it.status == "PLAYABLE_HLS" && it.totalMs < 15_000 })
    }
}
