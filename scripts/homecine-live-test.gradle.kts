import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// --- Extension properties defined at top level to avoid nesting ---
val baseUrl = "https://www3.homecine.to"
val episodePath = "/episode/el-mentalista-temporada-1-capitulo-1"
val harPath = System.getenv("HOMECINE_HAR") ?: "${System.getProperty("user.home")}\\Downloads\\homecine.har"
val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

data class FetchResult(val status: Int, val finalUrl: String, val body: String, val ms: Long)
data class ServerTab(val label: String, val anchorId: String, val iframeSrc: String, val resolvedSrc: String, val host: String, val extractor: String)
data class HarQuality(val tabLabel: String, val masterPlaylist: String, val variantPlaylists: List<String>, val segmentCount: Int)

fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    return block() to ((System.nanoTime() - start) / 1_000_000)
}

fun fetch(pathOrUrl: String, referer: String? = null, timeoutMs: Int = 12_000): FetchResult {
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
            }
            try {
                val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                Triple(conn.responseCode, conn.url.toString(), stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        }.getOrElse { Triple(0, url, "ERROR: ${it::class.simpleName}: ${it.message}") }
    }
    return FetchResult(result.first, result.second, result.third, ms)
}

fun resolveUrl(base: String, value: String): String = URI(base).resolve(value).toString()
fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

fun extractorForHost(src: String): String {
    val s = src.lowercase()
    return when {
        s.contains("fastream") -> "FastreamExtractor"
        s.contains("upstream") -> "UpstreamExtractor"
        s.contains("yourupload") -> "YourUploadExtractor"
        s.contains("voe") -> "VoeExtractor"
        s.contains("wishembed") || s.contains("streamwish") || s.contains("wish") -> "StreamWishExtractor"
        s.contains("mp4upload") -> "Mp4uploadExtractor"
        s.contains("burst") -> "BurstCloudExtractor"
        s.contains("filemoon") || s.contains("moonplayer") -> "FilemoonExtractor"
        else -> "UNKNOWN"
    }
}

fun parseHarQualities(harFilePath: String): List<HarQuality> {
    // Try filtered HAR first (faster), fall back to full HAR
    val dir = java.io.File(harFilePath).parent
    val fastreamHar = java.io.File(dir, "homecine-fastream.har")
    val fileToRead = if (fastreamHar.exists()) fastreamHar else java.io.File(harFilePath)
    if (!fileToRead.exists()) {
        println("  HAR file not found at: $harFilePath")
        return emptyList()
    }
    return runCatching {
        val content = fileToRead.readText()
        // Simpler regex: match URLs containing fastream.to and .m3u8 or .ts
        val m3u8Regex = Regex("""https://[a-zA-Z0-9./_\-?=&%,;:]+fastream\.to[a-zA-Z0-9./_\-?=&%,;:]+\.m3u8[a-zA-Z0-9./_\-?=&%,;:]*""")
        val tsRegex = Regex("""https://[a-zA-Z0-9./_\-?=&%,;:]+fastream\.to[a-zA-Z0-9./_\-?=&%,;:]+\.ts[a-zA-Z0-9./_\-?=&%,;:]*""")

        val allM3u8 = m3u8Regex.findAll(content).map { it.value }.toList()
        val allTs = tsRegex.findAll(content).map { it.value }.toList()

        println("  HAR file: ${fileToRead.name} (${content.length} bytes, ${allM3u8.size} m3u8, ${allTs.size} ts)")

        val keyRegex = Regex("""/hls2/\d+/\d+/([a-z0-9]+?)_""")
        val masterRegex = Regex("""master\.m3u8""")
        val variantRegex = Regex("""index-v\d+-a\d+\.m3u8""")

        val keys = allM3u8.mapNotNull { keyRegex.find(it)?.groupValues?.get(1) }.distinct()
        keys.map { key ->
            val keyM3u8 = allM3u8.filter { it.contains("/$key") }
            val master = keyM3u8.firstOrNull { masterRegex.containsMatchIn(it) } ?: ""
            val variants = keyM3u8.filter { variantRegex.containsMatchIn(it) }
            val segs = allTs.count { it.contains("/${key}_") }
            HarQuality("key=$key", master, variants, segs)
        }
    }.getOrDefault(emptyList())
}

// =============================================================
// Test entry point (called from Gradle task)
// =============================================================
fun runTest() {
    CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))

    val episodeUrl = "$baseUrl$episodePath"

    println("=== HomeCine Live Test ===")
    println("Episode URL: $episodeUrl")
    println("HAR path: $harPath")
    println()

    val episode = fetch(episodeUrl)
    if (episode.status != 200) {
        error("Episode fetch failed: HTTP ${episode.status} from ${episode.finalUrl}")
    }
    println("Episode fetch: HTTP ${episode.status} (${episode.ms}ms, ${episode.body.length} bytes)")
    println("  Final URL: ${episode.finalUrl}")

    // --- Parse server tabs from FULL body ---
    println()
    println("--- Server tabs ---")

    val tabRegex = Regex(
        """<a[^>]*href\s*=\s*"(#tab\d+)"[^>]*>([^<]*)</a>""",
        RegexOption.IGNORE_CASE,
    )
    val tabs = tabRegex.findAll(episode.body).toList()
    println("  Tab matches in full body: ${tabs.size}")
    tabs.forEach { match ->
        println("    ${match.groupValues[1]} → ${match.groupValues[2].trim()}")
    }

    // --- Iframe resolution ---
    println()
    println("--- Iframe resolution ---")

    val serverTabs = tabs.mapNotNull { match ->
        val anchorHref = match.groupValues[1]
        val label = match.groupValues[2].trim()
        val anchorId = anchorHref.removePrefix("#")

        val divPos = episode.body.indexOf("id=\"$anchorId\"")
        if (divPos < 0) {
            println("  [$label] NO div with id=\"$anchorId\" → SKIP")
            return@mapNotNull null
        }
        val window = episode.body.substring(divPos, minOf(divPos + 2000, episode.body.length))
        val iframeMatch = Regex(
            """<iframe[^>]*src\s*=\s*"([^"]*)"""",
            RegexOption.IGNORE_CASE,
        ).find(window)

        if (iframeMatch == null) {
            println("  [$label] NO iframe found near id=\"$anchorId\" → SKIP")
            return@mapNotNull null
        }

        var iframeSrc = iframeMatch.groupValues[1]
            .replace("&amp;", "&")
            .replace("#038;", "&")

        if (!iframeSrc.startsWith("http")) {
            iframeSrc = resolveUrl(episode.finalUrl, iframeSrc)
        }
        println("  [$label] iframe: ...${iframeSrc.takeLast(60)}")

        var resolvedSrc = iframeSrc
        if (iframeSrc.contains("home")) {
            println("  [$label]   → is 'home' wrapper, fetching nested...")
            val wrapper = fetch(iframeSrc, episode.finalUrl)
            if (wrapper.status == 200) {
                val nested = Regex("""<iframe[^>]*src\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(wrapper.body)?.groupValues?.get(1)?.let { src ->
                        val resolved = src.replace("&amp;", "&").replace("#038;", "&")
                        if (resolved.startsWith("http")) resolved else resolveUrl(wrapper.finalUrl, resolved)
                    }
                if (nested != null) {
                    println("  [$label]   → nested: ...${nested.takeLast(60)}")
                    resolvedSrc = nested
                }
            }
        }

        ServerTab(label, anchorId, iframeSrc, resolvedSrc, hostOf(resolvedSrc), extractorForHost(resolvedSrc))
    }

    // --- HAR-derived quality analysis ---
    println()
    println("--- HAR-derived quality data ---")
    val harQualities = parseHarQualities(harPath)
    if (harQualities.isEmpty()) {
        println("  No HAR quality data extracted.")
    } else {
        println("  Streams from HAR (${harQualities.size}):")
        harQualities.forEachIndexed { i, hq ->
            println("  Stream ${i + 1}: ${hq.tabLabel}")
            if (hq.masterPlaylist.isNotBlank()) {
                println("    Master: ...${hq.masterPlaylist.takeLast(80)}")
            }
            hq.variantPlaylists.forEach { v ->
                val hint = when { v.contains("_n/") -> "normal"; v.contains("_l/") -> "low"; v.contains("_h/") -> "high"; else -> "?" }
                println("    Variant [$hint]: ...${v.takeLast(80)}")
            }
            println("    TS segments: ${hq.segmentCount}")
        }
    }

    // --- Report ---
    println()
    println("========================================")
    println("=== RESULTS ===")
    println("========================================")
    println("Server tabs detected: ${serverTabs.size}")
    println()

    if (serverTabs.isEmpty()) {
        println("FAIL: No server tabs detected.")
        return
    }

    serverTabs.forEachIndexed { index, tab ->
        println("Server ${index + 1}:")
        println("  Label:      ${tab.label}")
        println("  Anchor ID:  ${tab.anchorId}")
        println("  Host:       ${tab.host}")
        println("  Extractor:  ${tab.extractor}")
        println("  Iframe:     ...${tab.iframeSrc.takeLast(60)}")
        println()
    }

    println("--- Quality mapping (label → HAR stream) ---")
    serverTabs.forEach { tab ->
        // Extract video key from iframe URL: embed-<key>.html
        val embedKey = Regex("""embed-([a-z0-9]+)\.html""").find(tab.iframeSrc)?.groupValues?.get(1) ?: ""
        val harStream = harQualities.firstOrNull { it.tabLabel.contains(embedKey) }
        if (harStream != null) {
            val tiers = harStream.variantPlaylists.map { v ->
                when { v.contains("_n/") -> "normal"; v.contains("_l/") -> "low"; v.contains("_h/") -> "high"; else -> "?" }
            }
            println("  ${tab.label} → ${harStream.segmentCount} segments, ${harStream.variantPlaylists.size} variant(s) [${tiers.joinToString()}] — HAR-derived")
        } else {
            println("  ${tab.label} → no HAR stream matched (key=$embedKey) — live extractor")
        }
    }

    println()
    println("--- Summary ---")
    println("Total servers:    ${serverTabs.size}")
    val extractorCounts = serverTabs.groupingBy { it.extractor }.eachCount()
    println("Extractors:       ${extractorCounts.entries.joinToString { "${it.key}=${it.value}" }}")
    val hosts = serverTabs.map { it.host }.distinct()
    println("Unique hosts:     ${hosts.size} (${hosts.joinToString()})")
    println("Qualities (page): ${serverTabs.map { it.label }.joinToString(" | ")}")
    println("Data source:      HAR-derived (m3u8/ts from HAR) + live page fetch")
    println()
    println("=== HomeCine live test: PASSED ===")
}

// =============================================================
// Gradle task registration (minimal nesting)
// =============================================================
gradle.projectsLoaded {
    gradle.rootProject.apply(plugin = "base")
    gradle.rootProject.tasks.register("homecineLiveTest") {
        doLast { runTest() }
    }
}
