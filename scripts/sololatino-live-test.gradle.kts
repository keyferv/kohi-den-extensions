import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

plugins {
    base
}

tasks.register("sololatinoLiveTest") {
    doLast {
        val baseUrl = "https://sololatino.net"
        CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))

        data class FetchResult(
            val status: Int,
            val finalUrl: String,
            val body: String,
        )

        fun fetch(pathOrUrl: String, referer: String? = null): FetchResult {
            val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$baseUrl$pathOrUrl"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
                setRequestProperty("Connection", "close")
                referer?.let { setRequestProperty("Referer", it) }
            }

            return try {
                val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                FetchResult(conn.responseCode, conn.url.toString(), body)
            } finally {
                conn.disconnect()
            }
        }

        fun postJson(path: String, json: String, referer: String, xsrfToken: String): FetchResult {
            val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("X-XSRF-TOKEN", xsrfToken)
                setRequestProperty("Referer", referer)
            }

            return try {
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                FetchResult(conn.responseCode, conn.url.toString(), body)
            } finally {
                conn.disconnect()
            }
        }

        fun postForm(url: String, form: String, referer: String): FetchResult {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                setRequestProperty("Origin", "https://player.pelisserieshoy.com")
                setRequestProperty("Referer", referer)
            }

            return try {
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                FetchResult(conn.responseCode, conn.url.toString(), body)
            } finally {
                conn.disconnect()
            }
        }

        fun String.compact() = replace(Regex("\\s+"), " ").trim()

        fun assertOk(name: String, result: FetchResult) {
            check(result.status in 200..299) { "$name returned HTTP ${result.status} at ${result.finalUrl}" }
        }

        fun assertMatches(name: String, body: String, regex: Regex, min: Int): List<MatchResult> {
            val matches = regex.findAll(body).toList()
            println("$name matches: ${matches.size}")
            check(matches.size >= min) { "$name expected at least $min matches, got ${matches.size}" }
            return matches
        }

        val oldPopular = fetch("/tendencias/page/1")
        println("OLD popular route: HTTP ${oldPopular.status} ${oldPopular.finalUrl}")

        val popular = fetch("/peliculas")
        assertOk("popular", popular)
        val popularCards = assertMatches(
            "popular cards",
            popular.body,
            Regex("""<a\s+href=[\"']($baseUrl/(?:serie|pelicula)/[^\"']+)[\"'][\s\S]*?<img[^>]+alt=[\"']([^\"']+)[\"'][\s\S]*?<span\s+class=[\"']card__title[\"']>([^<]+)"""),
            5,
        )
        popularCards.take(3).forEachIndexed { index, match ->
            println("  popular ${index + 1}: ${match.groupValues[3].compact()} -> ${match.groupValues[1]}")
        }

        val latest = fetch("/series")
        assertOk("latest", latest)
        val latestCards = assertMatches("latest cards", latest.body, Regex("""card__title[\"']>([^<]+)"""), 5)
        latestCards.take(3).forEachIndexed { index, match -> println("  latest ${index + 1}: ${match.groupValues[1].compact()}") }

        val search = fetch("/buscar?q=rick")
        assertOk("search", search)
        val searchCards = assertMatches("search cards", search.body, Regex("""href=[\"']($baseUrl/(?:serie|pelicula)/[^\"']+)[\"'][\s\S]*?card__title[\"']>([^<]+)"""), 1)
        searchCards.take(3).forEachIndexed { index, match ->
            println("  search ${index + 1}: ${match.groupValues[2].compact()} -> ${match.groupValues[1]}")
        }

        val filtersSource = java.io.File("src/es/sololatino/src/eu/kanade/tachiyomi/animeextension/es/sololatino/SoloLatinoFilters.kt").readText()
        check(!filtersSource.contains("first { it is R }")) { "filters still use unsafe first predicate lookup" }
        check(filtersSource.contains("getFirstOrNull")) { "filters do not expose partial-list safe lookup" }
        println("partial filter safety: unsafe predicate lookup not present")

        val detailUrl = "$baseUrl/serie/clevatess"
        val detail = fetch(detailUrl)
        assertOk("detail", detail)
        check(detail.body.contains("<h1") && detail.body.contains("card__poster")) { "detail page did not expose title/poster structure" }
        val episodeRegex = Regex("""href=[\"']($baseUrl/serie/[^\"']+/temporada-(\d+)/episodio-(\d+))[\"'][\s\S]*?<p\s+class=[\"']ep-num[\"']>E\3</p>[\s\S]*?<p\s+class=[\"'][^\"']*font-semibold[^\"']*[\"']>\s*([^<]+)""")
        val episodes = assertMatches("episodes", detail.body, episodeRegex, 1)
        episodes.take(3).forEachIndexed { index, match ->
            println("  episode ${index + 1}: T${match.groupValues[2]}E${match.groupValues[3]} ${match.groupValues[4].compact()} -> ${match.groupValues[1]}")
        }

        val episodeUrl = "$baseUrl/serie/clevatess/temporada-2/episodio-2"
        val episode = fetch(episodeUrl)
        assertOk("episode", episode)
        val playerToken = Regex("""data-player-token=[\"']([^\"']+)[\"']""").find(episode.body)?.groupValues?.get(1)
        check(!playerToken.isNullOrBlank()) { "episode page did not expose data-player-token" }
        fetch("/sanctum/csrf-cookie", episodeUrl)
        val cookieManager = CookieHandler.getDefault() as CookieManager
        val xsrfToken = cookieManager.cookieStore.cookies
            .firstOrNull { it.name == "XSRF-TOKEN" }
            ?.value
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        check(!xsrfToken.isNullOrBlank()) { "XSRF-TOKEN cookie was not issued" }
        val playerApi = postJson("/api/player-url", "{\"t\":\"$playerToken\"}", episodeUrl, xsrfToken)
        assertOk("player api", playerApi)
        val iframeUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerApi.body)
            ?.groupValues
            ?.get(1)
            ?.replace("\\/", "/")
        check(!iframeUrl.isNullOrBlank()) { "player api did not return a playable url: ${playerApi.body}" }
        println("  iframe: $iframeUrl")

        val player = fetch(iframeUrl, episodeUrl)
        assertOk("player", player)
        val playerTokenApi = Regex("""const\s+_t\s*=\s*'([^']+)'""").find(player.body)?.groupValues?.get(1)
        check(!playerTokenApi.isNullOrBlank()) { "player page did not expose s.php token" }
        val serversApi = postForm("https://player.pelisserieshoy.com/s.php", "a=1&tok=$playerTokenApi", iframeUrl)
        assertOk("player servers api", serversApi)
        val servers = Regex("""\[\"([^\"]+)\",\"([a-f0-9]{32})\"\]""").findAll(serversApi.body).toList()
        check(servers.isNotEmpty()) { "player s.php did not expose servers: ${serversApi.body}" }
        postForm("https://player.pelisserieshoy.com/s.php", "a=click&tok=$playerTokenApi", iframeUrl)
        val streamResults = servers.mapNotNull { server ->
            val id = server.groupValues[2]
            val direct = postForm("https://player.pelisserieshoy.com/s.php", "a=2&v=$id&tok=$playerTokenApi", iframeUrl)
            val body = if (direct.body.contains("\"msg\":\"no_click\"")) {
                postForm("https://player.pelisserieshoy.com/s.php", "a=2&v=$id&tok=$playerTokenApi&r=1", iframeUrl).body
            } else {
                direct.body
            }
            Regex("""\"u\"\s*:\s*\"([^\"]+)\"""").find(body)
                ?.groupValues
                ?.get(1)
                ?.replace("\\/", "/")
                ?.let { if (it.startsWith("/")) "https://player.pelisserieshoy.com$it" else it }
        }
        val playableStream = streamResults.firstOrNull { stream ->
            stream.contains(".m3u8", ignoreCase = true) ||
                stream.contains(".mp4", ignoreCase = true) ||
                stream.contains("/p.php", ignoreCase = true)
        }
        check(!playableStream.isNullOrBlank()) {
            "player did not resolve to a direct stream; found: ${streamResults.joinToString()}"
        }
        println("  player stream: $playableStream")

        println("SoloLatino live test passed")
    }
}
