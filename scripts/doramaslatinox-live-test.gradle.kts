import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

plugins {
    base
}

tasks.register("doramaslatinoxLiveTest") {
    doLast {
        val baseUrl = "https://doramaslatinox.com"
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
                connectTimeout = 15_000
                readTimeout = 15_000
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

        fun postForm(url: String, form: String, referer: String): FetchResult {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
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

        fun assertNone(name: String, body: String, regex: Regex) {
            val matches = regex.findAll(body).toList()
            check(matches.isEmpty()) { "$name expected no matches, got ${matches.size}" }
        }

        val articleCardRegex = Regex(
            """<article[^>]*class=\"item\s+tvshows\"[\s\S]*?<img[^>]+src=\"([^\"]+)\"[^>]+alt=\"([^\"]+)\"""",
            RegexOption.IGNORE_CASE,
        )
        val seriesLinkRegex = Regex("href=\"($baseUrl/series/[^\"]+)\"", RegexOption.IGNORE_CASE)
        val episodeLinkRegex = Regex("href=\"($baseUrl/episodio/[^\"]+)\"", RegexOption.IGNORE_CASE)

        // --- popular (series) ---
        val popular = fetch("/tipo/serie/")
        assertOk("popular", popular)
        val popularCards = assertMatches("popular cards", popular.body, articleCardRegex, 5)
        val popularSeriesLinks = assertMatches("popular series links", popular.body, seriesLinkRegex, 5)
        assertNone("popular episode links", popular.body, episodeLinkRegex)
        popularCards.take(3).forEachIndexed { index, match ->
            println("  popular ${index + 1}: ${match.groupValues[2].compact()} -> ${match.groupValues[1]}")
        }
        println("  popular series links: ${popularSeriesLinks.size}")

        // --- latest updates (dorama) ---
        val latest = fetch("/tipo/dorama/")
        assertOk("latest", latest)
        val latestCards = assertMatches("latest cards", latest.body, articleCardRegex, 5)
        val latestSeriesLinks = assertMatches("latest series links", latest.body, seriesLinkRegex, 5)
        assertNone("latest episode links", latest.body, episodeLinkRegex)
        latestCards.take(3).forEachIndexed { index, match ->
            println("  latest ${index + 1}: ${match.groupValues[2].compact()} -> ${match.groupValues[1]}")
        }
        println("  latest series links: ${latestSeriesLinks.size}")

        val latestPage2 = fetch("/tipo/dorama/page/2/")
        assertOk("latest page 2", latestPage2)
        val latestPage2Cards = assertMatches("latest page 2 cards", latestPage2.body, articleCardRegex, 5)
        val latestPage2SeriesLinks = assertMatches("latest page 2 series links", latestPage2.body, seriesLinkRegex, 5)
        assertNone("latest page 2 episode links", latestPage2.body, episodeLinkRegex)
        println("  latest page 2 cards: ${latestPage2Cards.size}, series links: ${latestPage2SeriesLinks.size}")

        // --- search ---
        val search = fetch("/?s=Dokgo")
        assertOk("search", search)
        val searchAll = assertMatches(
            "search all results",
            search.body,
            Regex("""href=\"($baseUrl/(?:series|episodio)/[^\"]+)\"""", RegexOption.IGNORE_CASE),
            1,
        )
        val searchEpisodes = Regex("""href=\"($baseUrl/episodio/[^\"]+)\"""").findAll(search.body).toList()
        val searchSeries = Regex("""href=\"($baseUrl/series/[^\"]+)\"""").findAll(search.body).toList()
        println("  search total: ${searchAll.size}, series: ${searchSeries.size}, episodes: ${searchEpisodes.size}")
        check(searchSeries.isNotEmpty()) { "search returned no series results" }
        searchSeries.take(3).forEachIndexed { index, match ->
            println("  search series ${index + 1}: ${match.groupValues[1]}")
        }

        // --- series detail ---
        val detailUrl = "$baseUrl/series/dokgo-rewind/"
        val detail = fetch(detailUrl)
        assertOk("detail", detail)
        val titleMatch = Regex("""<h1[^>]*>([^<]+)</h1>""").find(detail.body)
        check(titleMatch != null) { "detail page has no h1 title" }
        println("  title: ${titleMatch.groupValues[1].compact()}")

        val genres = Regex("""<a\s+href="$baseUrl/genre/[^"]+"\s+rel="tag">([^<]+)</a>""", RegexOption.IGNORE_CASE)
            .findAll(detail.body).toList()
        println("  genres: ${genres.joinToString { it.groupValues[1].compact() }}")
        check(genres.isNotEmpty()) { "detail page has no genres" }

        val infoMatch = Regex("""<div id="info" class="sbox">([\s\S]*?)</div>\s*<!-- Show Cast -->""", RegexOption.IGNORE_CASE)
            .find(detail.body)
        val detailDescription = infoMatch?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&nbsp;", " ")
            ?.compact()
            .orEmpty()
        println("  description chars: ${detailDescription.length}")
        check(detailDescription.isNotBlank()) { "detail page has no description/info text" }

        // --- episodes (REST API) ---
        val docgoSlug = "dokgo-rewind"
        val restEpisodes = fetch("/wp-json/wp/v2/episodes?per_page=100")
        assertOk("episodes REST", restEpisodes)
        val epPattern = """"slug":"($docgoSlug-\d+x\d+)""""
        println("  epPattern: $epPattern")
        println("  episodes body len: ${restEpisodes.body.length}")
        val episodeSlugs = Regex(epPattern).findAll(restEpisodes.body).toList()
        check(episodeSlugs.isNotEmpty()) { "no episodes found for slug $docgoSlug via REST API" }
        println("  episodes via REST: ${episodeSlugs.size}")
        episodeSlugs.take(5).forEachIndexed { index, match ->
            val slug = match.groupValues[1]
            val se = Regex("""(\d+)x(\d+)$""").find(slug)
            println("  ep ${index + 1}: slug=$slug S${se?.groupValues?.get(1)}E${se?.groupValues?.get(2)}")
        }

        // --- first episode player servers ---
        val episodePage = fetch("/episodio/dokgo-rewind-1x1/", detailUrl)
        assertOk("episode page", episodePage)
        val playerOptions = Regex(
            """<li[^>]+class=['\"]dooplay_player_option['\"][^>]+data-type=['\"]([^'\"]+)['\"][^>]+data-post=['\"]([^'\"]+)['\"][^>]+data-nume=['\"]([^'\"]+)['\"][\s\S]*?<span class=['\"]title['\"]>([^<]+)</span>""",
            RegexOption.IGNORE_CASE,
        ).findAll(episodePage.body).toList()
        check(playerOptions.isNotEmpty()) { "episode page has no player options" }
        println("  player servers: ${playerOptions.joinToString { it.groupValues[4].compact() }}")

        var p2pplayFound = false
        var abyssplayerFound = false

        playerOptions.forEach { player ->
            val type = player.groupValues[1]
            val post = player.groupValues[2]
            val nume = player.groupValues[3]
            val server = player.groupValues[4].compact()
            val playerResponse = fetch("/wp-json/dooplayer/v2/$post/$type/$nume", episodePage.finalUrl)
            assertOk("player $server", playerResponse)
            val embedUrl = Regex("""\"embed_url\"\s*:\s*\"([^\"]+)\"""")
                .find(playerResponse.body)
                ?.groupValues
                ?.get(1)
                ?.replace("\\/", "/")
                .orEmpty()
            check(embedUrl.startsWith("http")) { "player $server returned no embed_url: ${playerResponse.body.take(120)}" }
            println("  player $server embed: $embedUrl")

            when {
                embedUrl.contains("p2pplay.online") -> {
                    p2pplayFound = true
                    val hashId = embedUrl.substringAfter("#").trim()
                    check(hashId.isNotBlank()) { "p2pplay embed missing hash video id: $embedUrl" }
                    println("    p2pplay video id: $hashId")

                    val p2pplayUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
                    val p2pplayOrigin = Regex("(https?://[^/]+)").find(embedUrl)?.groupValues?.get(1) ?: embedUrl
                    val p2pplayVideoUrl = "$p2pplayOrigin/api/v1/video?id=$hashId&w=1600&h=900&r=doramaslatinox.com"

                    val p2pplayConn = (URL(p2pplayVideoUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                        connectTimeout = 15_000
                        readTimeout = 15_000
                        setRequestProperty("User-Agent", p2pplayUserAgent)
                        setRequestProperty("Referer", embedUrl)
                        setRequestProperty("Origin", p2pplayOrigin)
                        setRequestProperty("Accept", "*/*")
                    }

                    val p2pplayEncrypted = try {
                        val stream = if (p2pplayConn.responseCode >= 400) p2pplayConn.errorStream else p2pplayConn.inputStream
                        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    } finally {
                        p2pplayConn.disconnect()
                    }

                    check(p2pplayEncrypted.isNotBlank()) { "p2pplay direct returned empty response" }

                    fun String.hexToByteArray(): ByteArray =
                        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES"),
                        javax.crypto.spec.IvParameterSpec("1234567890oiuytr".toByteArray()),
                    )
                    val decryptedBody = cipher.doFinal(p2pplayEncrypted.trim().hexToByteArray()).toString(Charsets.UTF_8)

                    val hasCfNative = Regex("\"cfNative\"\\s*:\\s*\"[^\"]+\"").containsMatchIn(decryptedBody)
                    val hasSource = Regex("\"source\"\\s*:\\s*\"[^\"]+\"").containsMatchIn(decryptedBody)
                    check(hasCfNative || hasSource) { "p2pplay direct decrypted JSON has no cfNative or source" }
                    println("    p2pplay direct sources verified")
                }
                embedUrl.contains("abyssplayer.com") -> {
                    abyssplayerFound = true
                    println("    abyssplayer recognized as known unsupported/deferred host")
                }
            }
        }

        check(p2pplayFound) { "no p2pplay embed found; p2pplay is the supported extraction route" }
        println("  p2pplay embed verified with non-empty hash video id")
        if (abyssplayerFound) {
            println("  abyssplayer recognized as known unsupported/deferred host")
        }

        println("DoramasLatinoX live test passed")
    }
}
