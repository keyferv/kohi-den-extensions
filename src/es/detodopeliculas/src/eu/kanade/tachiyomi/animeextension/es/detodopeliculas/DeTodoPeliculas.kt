package eu.kanade.tachiyomi.animeextension.es.detodopeliculas

import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.detodopeliculas.extractors.ByseExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class DeTodoPeliculas : DooPlay(
    "es",
    "DeTodo Peliculas",
    "https://detodopeliculas.nu",
) {
    private val browserHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept-Language", "es-US,es;q=0.9,en-US;q=0.8,en;q=0.7")
            .set("X-Requested-With", "app.anizen")
            .build()
    }

    private fun navHeaders(referer: String = "$baseUrl/"): Headers {
        return browserHeaders.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .set("Referer", referer)
            .set("sec-fetch-site", "same-origin")
            .set("sec-fetch-mode", "navigate")
            .set("sec-fetch-dest", "document")
            .set("Upgrade-Insecure-Requests", "1")
            .build()
    }

    private val cfInterceptor by lazy { CloudflareInterceptor(super.client.newBuilder().build()) }

    private val directClient: OkHttpClient by lazy { super.client.newBuilder().build() }

    private val cloudflareSolveLock = Any()

    @Volatile
    private var lastCloudflareSolveAt = 0L

    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
            .addInterceptor(CfMitigatedInterceptor(this))
            .build()
    }

    fun solveCloudflare(): Boolean {
        return synchronized(cloudflareSolveLock) {
            if (hasFreshCloudflareCookie()) {
                Log.d(TAG, "solveCloudflare: reusing fresh WebView cookies")
                return@synchronized true
            }

            try {
                Log.d(TAG, "solveCloudflare: loading $baseUrl in WebView...")
                val warmRequest = Request.Builder()
                    .url("$baseUrl/")
                    .headers(browserHeaders)
                    .build()
                cfInterceptor.resolveWithWebView(warmRequest, client)

                val cookieManager = CookieManager.getInstance()
                val cookieStr = cookieManager.getCookie("$baseUrl/") ?: ""
                Log.d(TAG, "solveCloudflare: WebView cookies present = ${cookieStr.isNotBlank()}")

                val siteUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("detodopeliculas.nu")
                    .build()
                val cookies = cookieStr.split(";")
                    .mapNotNull { Cookie.parse(siteUrl, it.trim()) }

                if (cookies.isNotEmpty()) {
                    client.cookieJar.saveFromResponse(siteUrl, cookies)
                    Log.d(TAG, "solveCloudflare: saved ${cookies.size} cookies to jar with HTTPS")
                }

                cookies.isNotEmpty().also { solved ->
                    if (solved) lastCloudflareSolveAt = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "solveCloudflare FAILED: ${e.message}")
                false
            }
        }
    }

    private fun hasFreshCloudflareCookie(): Boolean {
        if (System.currentTimeMillis() - lastCloudflareSolveAt > CLOUDFLARE_SOLVE_CACHE_MS) return false

        val cookieStr = CookieManager.getInstance().getCookie("$baseUrl/").orEmpty()
        if (cookieStr.isNotBlank()) return true

        val siteUrl = HttpUrl.Builder()
            .scheme("https")
            .host("detodopeliculas.nu")
            .build()
        return client.cookieJar.loadForRequest(siteUrl).isNotEmpty()
    }

    private class CfMitigatedInterceptor(
        private val source: DeTodoPeliculas,
    ) : Interceptor {
        @Volatile
        private var warmed = false

        override fun intercept(chain: Interceptor.Chain): Response {
            if (!warmed) {
                warmed = source.solveCloudflare()
            }
            val response = chain.proceed(chain.request())
            if (response.code != 403 || response.header("cf-mitigated") == null) {
                return response
            }
            Log.d("DeTodoPeliculas", "cf-mitigated challenge detected on ${chain.request().url.encodedPath}")
            response.close()
            val solved = source.solveCloudflare()
            if (!solved) {
                Log.e("DeTodoPeliculas", "cf-mitigated: could not resolve, returning original")
                return chain.proceed(chain.request())
            }
            warmed = true
            Log.d("DeTodoPeliculas", "cf-mitigated: retrying original request with jar cookies")
            return chain.proceed(chain.request())
        }
    }

// ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/novedades/" else "$baseUrl/novedades/page/$page"
        Log.d(TAG, "popularAnimeRequest: page=$page url=$url")
        return GET(url, navHeaders())
    }

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularAnimeParse(response: Response): AnimesPage {
        Log.d(TAG, "popularAnimeParse: code=${response.code} url=${response.request.url}")
        return super.popularAnimeParse(response).also { page ->
            Log.d(TAG, "popularAnimeParse: items=${page.animes.size} hasNext=${page.hasNextPage}")
            page.animes.take(8).forEachIndexed { index, anime ->
                Log.d(TAG, "popularAnimeParse[$index]: title=${anime.title} url=${anime.url} thumb=${anime.thumbnail_url}")
            }
        }
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).also { anime ->
            anime.thumbnail_url = element.selectFirst("img")?.detodoImageUrl() ?: anime.thumbnail_url?.toWebpVariant()
            Log.d(TAG, "popularAnimeFromElement: title=${anime.title} url=${anime.url} thumb=${anime.thumbnail_url}")
        }
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/peliculas-de-estreno/" else "$baseUrl/peliculas-de-estreno/page/$page"
        Log.d(TAG, "latestUpdatesRequest: page=$page url=$url")
        return GET(url, navHeaders())
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        Log.d(TAG, "latestUpdatesParse: code=${response.code} url=${response.request.url}")
        return super.latestUpdatesParse(response).also { page ->
            Log.d(TAG, "latestUpdatesParse: items=${page.animes.size} hasNext=${page.hasNextPage}")
            page.animes.take(8).forEachIndexed { index, anime ->
                Log.d(TAG, "latestUpdatesParse[$index]: title=${anime.title} url=${anime.url} thumb=${anime.thumbnail_url}")
            }
        }
    }

    override fun videoListSelector() = "li.dooplay_player_option"

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val byseExtractor by lazy { ByseExtractor(client, headers, baseUrl) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

// ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        Log.d(TAG, "videoListParse: code=${response.code} url=${response.request.url}")
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val vidsonicToken = vidsonicToken(document)
        val players = document.select("ul#playeroptionsul li")
        Log.d(TAG, "videoListParse: players=${players.size}")
        if (players.isEmpty()) {
            val fv2Players = fv2PlayerOptions(document)
            Log.d(TAG, "videoListParse: fv2Players=${fv2Players.size}")
            return fv2Players.parallelFlatMapBlocking { player ->
                val url = getPlayerUrl(player.post, player.nume, player.type, referer)
                    ?: return@parallelFlatMapBlocking emptyList<Video>()
                Log.d(TAG, "videoListParse: fv2 player label=${player.label} lang=${player.lang} url=$url")
                extractVideos(url, player.lang, referer, vidsonicToken = vidsonicToken)
            }.also { Log.d(TAG, "videoListParse: videos=${it.size}") }
        }

        return players.parallelFlatMapBlocking { player ->
            val flagSrc = sequenceOf(
                player.selectFirst("span.flag img")?.attr("data-lazy-src"),
                player.selectFirst("span.flag img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

            val lang = when {
                "sub" in flagSrc.lowercase() -> "[SUB]"
                "cas" in flagSrc.lowercase() -> "[CAST]"
                "lat" in flagSrc.lowercase() -> "[LAT]"
                else -> "UNKNOWN"
            }

            val url = getPlayerUrl(player, referer) ?: return@parallelFlatMapBlocking emptyList<Video>()
            Log.d(TAG, "videoListParse: player lang=$lang url=$url")
            extractVideos(url, lang, referer, vidsonicToken = vidsonicToken)
        }.also { Log.d(TAG, "videoListParse: videos=${it.size}") }
    }

    private fun extractVideos(
        url: String,
        lang: String,
        referer: String,
        depth: Int = 0,
        vidsonicToken: String? = null,
    ): List<Video> {
        if (depth >= 3) return emptyList()

        val normalized = normalizeUrl(url)
        if (!normalized.isVideoCandidate()) return emptyList()
        Log.d(TAG, "extractVideos: depth=$depth lang=$lang url=$normalized referer=$referer")

        if (normalized.endsWith(".m3u8", true) || normalized.endsWith(".mp4", true)) {
            Log.d(TAG, "extractVideos: direct media url detected")
            return listOf(Video(normalized, "$lang - Direct", normalized, headers))
        }

        if (normalized.startsWith("$baseUrl/player")) {
            val decodedUrl = Uri.parse(normalized).getQueryParameter("id")
                ?.let { decodeBase64Url(it) ?: it }
                ?.let(::normalizeUrl)
                ?.takeIf { it.isNotBlank() }

            if (decodedUrl != null && decodedUrl != normalized) {
                return extractVideos(decodedUrl, lang, normalized, depth + 1, vidsonicToken)
            }
        }

        if (normalized.contains("vidsonic.net/e/", ignoreCase = true)) {
            return resolveVidsonic(normalized, lang, referer, vidsonicToken)
        }

        if (normalized.contains("trembed")) {
            val embedHeaders = headers.newBuilder()
                .add("Referer", referer)
                .add("Origin", baseUrl)
                .build()

            val embedBody = runCatching {
                client.newCall(GET(normalized, embedHeaders)).execute().use { response ->
                    Log.d(TAG, "extractVideos: trembed response code=${response.code} url=${response.request.url}")
                    if (!response.isSuccessful) "" else response.body.string()
                }
            }.getOrDefault("")

            if (embedBody.isBlank()) return emptyList()

            val iframeUrl = Jsoup.parse(embedBody)
                .selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                ?.let { element ->
                    sequenceOf("src", "data-src", "data-lazy-src")
                        .map(element::attr)
                        .firstOrNull { it.isNotBlank() }
                }
                ?.let(::normalizeUrl)
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()

            return extractVideos(iframeUrl, lang, referer, depth + 1, vidsonicToken)
        }
        val vidHideDomains = listOf("vidhide", "vidhidepro", "luluvdo", "vidhideplus")

        return runCatching {
            vidHideDomains.firstOrNull { normalized.contains(it, ignoreCase = true) }
                ?.let { domain ->
                    vidHideExtractor.videosFromUrl(
                        normalized,
                        videoNameGen = { "$lang - ${domain.uppercase()} : $it" },
                    )
                }
                ?: when {
                    "ok.ru" in normalized || "okru" in normalized -> okruExtractor.videosFromUrl(normalized, "$lang - ")
                    "uqload" in normalized -> uqloadExtractor.videosFromUrl(normalized, "$lang - ")
                    listOf("streamwish", "strwish", "wishembed").any { normalized.contains(it) } -> streamWishExtractor.videosFromUrl(normalized, "$lang - ")
                    listOf("vidguard", "listeamed", "guard", "listeam").any { normalized.contains(it) } -> vidGuardExtractor.videosFromUrl(normalized, "$lang - ")
                    "voe" in normalized -> voeExtractor.videosFromUrl(normalized, "$lang - ")
                    listOf("waaw", "netu", "hqq").any { normalized.contains(it, ignoreCase = true) } -> {
                        Log.d(TAG, "extractVideos: routing to UniversalExtractor for=$normalized")
                        universalExtractor.videosFromUrl(normalized, headers, prefix = "$lang - Netu")
                    }
                    listOf("filemoon", "moonplayer", "bysekoze").any { normalized.contains(it, ignoreCase = true) } -> {
                        Log.d(TAG, "extractVideos: routing to FilemoonExtractor for=$normalized")
                        filemoonExtractor.videosFromUrl(normalized, prefix = "$lang - Filemoon:", headers = headers, referer = referer)
                    }
                    listOf("byse", "bysevepoin", "bysesukior", "q8y5z").any { normalized.contains(it, ignoreCase = true) } -> {
                        Log.d(TAG, "extractVideos: routing to ByseExtractor for=$normalized")
                        byseExtractor.videosFromUrl(normalized, "$lang - Byse")
                    }
                    else -> emptyList()
                }
        }.onSuccess { videos ->
            Log.d(TAG, "extractVideos: extracted=${videos.size} from=$normalized")
        }.getOrElse {
            Log.e(TAG, "extractVideos: failed url=$normalized message=${it.message}", it)
            emptyList()
        }
    }

    private fun resolveVidsonic(url: String, lang: String, referer: String, token: String?): List<Video> {
        val code = url.substringAfter("/e/", "")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
        if (code.isBlank() || token.isNullOrBlank()) {
            Log.d(TAG, "resolveVidsonic: missing code/token code=$code hasToken=${!token.isNullOrBlank()}")
            return emptyList()
        }

        val resolveUrl = "$baseUrl/panel/vidsonic-resolve.php?code=$code&t=$token"
        val cookieHeader = CookieManager.getInstance().getCookie("$baseUrl/").orEmpty()
        val resolveHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", referer)
            .set("sec-fetch-site", "same-origin")
            .set("sec-fetch-mode", "cors")
            .set("sec-fetch-dest", "empty")
            .apply {
                if (cookieHeader.isNotBlank()) {
                    set("Cookie", cookieHeader)
                }
            }
            .build()

        return runCatching {
            directClient.newCall(GET(resolveUrl, resolveHeaders)).execute().use { response ->
                Log.d(TAG, "resolveVidsonic: code=${response.code} vidsonicCode=$code")
                if (!response.isSuccessful) return@use emptyList<Video>()

                val body = response.body.string()
                val master = masterUrlRegex.find(body)?.groupValues?.getOrNull(1)
                    ?.decodeJsonStringFragment()
                    ?.let(::normalizeUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@use emptyList<Video>()

                listOf(Video(master, "$lang - Vidsonic", master, headers))
            }
        }.getOrElse { error ->
            Log.e(TAG, "resolveVidsonic: failed url=$url message=${error.message}", error)
            emptyList()
        }
    }

    private fun getPlayerUrl(player: Element, referer: String): String? {
        // Try direct data attributes first (no AJAX needed)
        val directCandidate = sequenceOf(
            player.attr("data-option"),
            player.attr("data-player"),
            player.attr("data-src"),
            player.attr("data-url"),
            player.attr("data-video"),
            player.selectFirst("a[href]")?.attr("href"),
        ).firstOrNull { !it.isNullOrBlank() }

        if (!directCandidate.isNullOrBlank()) {
            Log.d(TAG, "getPlayerUrl: direct candidate=$directCandidate")
            return normalizeUrl(directCandidate)
        }

        val post = player.attr("data-post")
        val nume = player.attr("data-nume")
        val type = player.attr("data-type").ifBlank { "movie" }

        return getPlayerUrl(post, nume, type, referer)
    }

    private fun getPlayerUrl(post: String, nume: String, type: String, referer: String): String? {
        if (post.isBlank() || nume.isBlank()) return null

        val ajaxHeaders = headers.newBuilder()
            .add("Referer", referer)
            .add("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()

        val responseBody = runCatching {
            client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body)).execute().use { response ->
                Log.d(TAG, "getPlayerUrl: ajax code=${response.code} post=$post nume=$nume type=$type")
                response.body.string()
            }
        }.getOrElse { error ->
            Log.e(TAG, "getPlayerUrl: ajax failed post=$post nume=$nume type=$type message=${error.message}", error)
            return null
        }

        if (responseBody.isBlank()) return null

        val embedByRegex = embedUrlRegex.find(responseBody)?.groupValues?.getOrNull(1)
            ?.decodeJsonStringFragment()
            ?.extractIframeSrcOrSelf()
            ?.let(::normalizeUrl)
            ?.takeIf { it.isNotBlank() }
        if (embedByRegex != null) return embedByRegex

        val iframe = Jsoup.parse(responseBody).selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            ?.let { element ->
                sequenceOf("src", "data-src", "data-lazy-src")
                    .map(element::attr)
                    .firstOrNull { it.isNotBlank() }
            }
            ?.let(::normalizeUrl)
            ?.takeIf { it.isNotBlank() }
        if (iframe != null) return iframe

        val source = Jsoup.parse(responseBody).selectFirst("source[src]")?.attr("src")
            ?.let(::normalizeUrl)
            ?.takeIf { it.isNotBlank() }

        return source
    }

    private fun fv2PlayerOptions(document: Document): List<Fv2PlayerOption> {
        val buttons = document.select("#fv2plSrvMenu button, .fv2-pl-ddmenu button")
        val source = buildString {
            appendLine(document.select("script").joinToString("\n") { it.data().ifBlank { it.html() } })
            appendLine(document.html())
        }

        val post = listOf(
            Regex("""\bpost\s*[:=]\s*['\"]?(\d+)"""),
            Regex("""['\"]post['\"]\s*:\s*['\"]?(\d+)"""),
            Regex("""\bdata-post=['\"](\d+)"""),
            Regex("""\bpostid-(\d+)"""),
            Regex("""\bpost_id\s*[:=]\s*['\"]?(\d+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(source)?.groupValues?.getOrNull(1) }
        if (post.isNullOrBlank()) {
            Log.d(TAG, "fv2PlayerOptions: no post id found buttons=${buttons.size} hasFv2=${source.contains("FV2", ignoreCase = true)}")
            return emptyList()
        }

        val type = listOf(
            Regex("""\btype\s*:\s*['\"]([^'\"]+)"""),
            Regex("""['\"]type['\"]\s*:\s*['\"]([^'\"]+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(source)?.groupValues?.getOrNull(1) }
            ?.normalizeDooPlayType()
            ?: "movie"

        Log.d(TAG, "fv2PlayerOptions: post=$post type=$type buttons=${buttons.size}")

        val buttonOptions = buttons
            .mapNotNull { button ->
                val label = button.selectFirst(".lbl")?.text()?.trim()
                    ?: button.text().trim()
                val nume = Regex("""(\d+)""").find(label)?.groupValues?.getOrNull(1)
                    ?: return@mapNotNull null
                Fv2PlayerOption(
                    post = post,
                    nume = nume,
                    type = type,
                    label = label.ifBlank { "Servidor $nume" },
                    lang = playerLanguageFromText(label),
                )
            }
            .distinctBy { it.nume }

        if (buttonOptions.isNotEmpty()) return buttonOptions

        val scriptedOptions = Regex("""\bnume\s*:\s*['\"]?(\d+)""")
            .findAll(source)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .distinct()
            .map { nume ->
                Fv2PlayerOption(
                    post = post,
                    nume = nume,
                    type = type,
                    label = "Servidor $nume",
                    lang = "UNKNOWN",
                )
            }
            .toList()

        if (scriptedOptions.isNotEmpty()) return scriptedOptions

        Log.d(TAG, "fv2PlayerOptions: using numeric probe fallback post=$post type=$type")
        return (1..10).map { nume ->
            Fv2PlayerOption(
                post = post,
                nume = nume.toString(),
                type = type,
                label = "Servidor $nume",
                lang = "UNKNOWN",
            )
        }
    }

    private fun String.normalizeDooPlayType(): String {
        return when (lowercase()) {
            "movies", "pelicula", "peliculas" -> "movie"
            "episodes", "episode", "episodio", "episodios" -> "tv"
            else -> this
        }
    }

    private fun playerLanguageFromText(text: String): String {
        val value = text.lowercase()
        return when {
            listOf("sub", "subtitulado").any { it in value } -> "[SUB]"
            listOf("cast", "castellano", "españa").any { it in value } -> "[CAST]"
            listOf("lat", "latino").any { it in value } -> "[LAT]"
            else -> "UNKNOWN"
        }
    }

    private data class Fv2PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val label: String,
        val lang: String,
    )

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            else -> trimmed
        }
    }

    private fun String.isVideoCandidate(): Boolean {
        val cleanUrl = substringBefore("?").lowercase()
        if (isBlank() || contains("/wp-admin/admin-ajax.php")) return false
        if (cleanUrl.endsWith("/aviso.mp4") || cleanUrl.contains("/epix/aviso.mp4")) return false
        if (listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".woff", ".woff2").any(cleanUrl::endsWith)) return false
        if (listOf("plyr", "swiper", "histats", "google-analytics", "googletagmanager", "clarity.ms").any { cleanUrl.contains(it) }) return false

        // Allow dtp_canary tracking URLs (redirect to real embed via CloudflareInterceptor)
        if (contains("dtp_canary")) return true

        return listOf(
            ".m3u8",
            ".mp4",
            "/player",
            "embed",
            "trembed",
            "uqload",
            "streamwish",
            "strwish",
            "wishembed",
            "vidhide",
            "vidhidepro",
            "luluvdo",
            "vidhideplus",
            "vidsonic",
            "okru",
            "vidguard",
            "listeamed",
            "listeam",
            "voe",
            "waaw",
            "netu",
            "hqq",
            "byse",
            "bysevepoin",
            "bysesukior",
            "q8y5z",
            "filemoon",
            "moonplayer",
            "bysekoze",
        ).any { contains(it, ignoreCase = true) }
    }

    private fun Element.playerLang(): String {
        val value = listOf(text(), className(), attr("data-lang"), attr("data-language"), attr("aria-label"))
            .joinToString(" ")
            .lowercase()

        return when {
            listOf("sub", "subtitulado").any { it in value } -> "[SUB]"
            listOf("cast", "castellano", "españa").any { it in value } -> "[CAST]"
            listOf("lat", "latino").any { it in value } -> "[LAT]"
            else -> "UNKNOWN"
        }
    }

    private val embedUrlRegex = Regex("\"embed_url\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")

    private val masterUrlRegex = Regex("\"master\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")

    private val vidsonicTokenRegex = Regex("""\bAUTH\s*=\s*['"]&t=([a-f0-9]{32})['"]""", RegexOption.IGNORE_CASE)

    private fun vidsonicToken(document: Document): String? {
        val source = document.select("script").joinToString("\n") { script -> script.data().ifBlank { script.html() } }
        return vidsonicTokenRegex.find(source)?.groupValues?.getOrNull(1)
            .also { Log.d(TAG, "vidsonicToken: present=${!it.isNullOrBlank()}") }
    }

    private fun String.decodeJsonStringFragment(): String {
        return replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
    }

    private fun String.extractIframeSrcOrSelf(): String {
        if (!contains("<iframe", ignoreCase = true)) return this
        return Jsoup.parse(this).selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            ?.let { element ->
                sequenceOf("src", "data-src", "data-lazy-src")
                    .map(element::attr)
                    .firstOrNull { it.isNotBlank() }
            }
            ?: this
    }

    private fun decodeBase64Url(data: String): String? = runCatching {
        val sanitized = data
            .replace('-', '+')
            .replace('_', '/')
            .let { str ->
                val padding = str.length % 4
                if (padding == 0) str else str + "=".repeat(4 - padding)
            }
        String(Base64.decode(sanitized, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

// ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = DeTodoPeliculasFilters.FILTER_LIST

    override fun animeDetailsParse(document: Document): SAnime {
        Log.d(TAG, "animeDetailsParse: url=${document.location()} fv2=${document.selectFirst(".fv2-h1") != null}")
        val fv2Header = document.selectFirst(".fv2-h1")
        if (fv2Header == null) return super.animeDetailsParse(document)

        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = fv2Header.text()
            thumbnail_url = sequenceOf(
                document.selectFirst("#fv2list[data-poster]")?.attr("abs:data-poster"),
                document.selectFirst("meta[property=og:image]")?.attr("content"),
                document.selectFirst(".fv2-logo-img[src], .fv2-logo-img[data-src], .fv2-logo-img[data-lazy-src]")
                    ?.let { image ->
                        sequenceOf("abs:data-src", "abs:data-lazy-src", "abs:src")
                            .map(image::attr)
                            .firstOrNull { it.isNotBlank() }
                    },
            ).firstOrNull { !it.isNullOrBlank() }?.toWebpVariant()
            genre = document.select("#single a[href*=/genero/], .fv2 a[href*=/genero/]")
                .eachText()
                .joinToString()
            description = document.selectFirst(".fv2-hsyn")?.text().orEmpty()
            Log.d(TAG, "animeDetailsParse: title=$title thumb=$thumbnail_url genres=$genre descLength=${description?.length ?: 0}")
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        Log.d(TAG, "animeDetailsRequest: title=${anime.title} url=$url")
        return GET(url, navHeaders())
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        Log.d(TAG, "episodeListParse: code=${response.code} url=${response.request.url}")
        val document = getRealAnimeDoc(response.asJsoup())
        val fv2Episodes = document.select(".fv2-seas-panel .fv2-epcard[href]")

        if (fv2Episodes.isNotEmpty()) {
            return fv2Episodes.map { episodeFromFv2Element(it) }
                .also { episodes ->
                    Log.d(TAG, "episodeListParse: fv2Episodes=${episodes.size}")
                    episodes.take(8).forEachIndexed { index, episode ->
                        Log.d(TAG, "episodeListParse[$index]: name=${episode.name} url=${episode.url} number=${episode.episode_number}")
                    }
                }
        }

        val seasonList = document.select(seasonListSelector)
        val episodes = if (seasonList.isEmpty()) {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    episode_number = 1F
                    name = episodeMovieText
                },
            )
        } else {
            seasonList.flatMap(::getSeasonEpisodes).reversed()
        }

        return episodes.also { episodes ->
            Log.d(TAG, "episodeListParse: episodes=${episodes.size}")
            episodes.take(8).forEachIndexed { index, episode ->
                Log.d(TAG, "episodeListParse[$index]: name=${episode.name} url=${episode.url} number=${episode.episode_number}")
            }
        }
    }

    private fun episodeFromFv2Element(element: Element): SEpisode {
        val seasonPanel = element.parents().firstOrNull { it.hasClass("fv2-seas-panel") }
        val seasonNumber = element.attr("data-epnum")
            .substringBefore("×")
            .toIntOrNull()
            ?: seasonPanel?.attr("data-season")?.toIntOrNull()
            ?: seasonPanel?.selectFirst(".fv2-seas-head h3")?.text()?.let { text ->
                Regex("""(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            ?: 1
        val episodeNumber = element.attr("data-epnum")
            .substringAfter("×", "")
            .toFloatOrNull()
            ?: element.attr("data-epnum").substringAfter("x", "").toFloatOrNull()
            ?: element.selectFirst(".fv2-epn")?.text()?.let { text ->
                Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            }
            ?: 0F
        val title = element.attr("data-eptitle")
            .ifBlank { element.attr("title") }
            .ifBlank { element.selectFirst(".fv2-eptitle")?.text().orEmpty() }

        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            episode_number = episodeNumber
            date_upload = element.selectFirst(".fv2-epmeta")?.text().toFv2Date()
            name = "$episodeSeasonPrefix $seasonNumber x ${episodeNumber.cleanNumber()} - $title"
        }
    }

    private fun String?.toFv2Date(): Long {
        if (isNullOrBlank()) return 0L
        val normalized = replace(Regex("""\b([A-Za-z]{3})\."""), "$1")
        return listOf("MMM dd, yyyy", "MMM. dd, yyyy")
            .firstNotNullOfOrNull { pattern ->
                runCatching { SimpleDateFormat(pattern, Locale.ENGLISH).parse(normalized)?.time }.getOrNull()
                    ?: runCatching { SimpleDateFormat(pattern, Locale.ENGLISH).parse(this)?.time }.getOrNull()
            }
            ?: 0L
    }

    private fun Float.cleanNumber(): String {
        return if (rem(1F) == 0F) toInt().toString() else toString()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = if (episode.url.startsWith("http")) episode.url else baseUrl + episode.url
        Log.d(TAG, "videoListRequest: episode=${episode.name} url=$url")
        return GET(url, navHeaders())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DeTodoPeliculasFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("peliculas-de-estreno", "novedades", "peliculas-recomendadas", "peliculas")) {
                    "/${params.genre}"
                } else {
                    "/genero/${params.genre}"
                }
            }
            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=${Uri.encode(query)}"
                        else -> "/"
                    },
                )

                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            val separator = if ("?" in path) "&" else "?"
            val url = if (page == 1) {
                "$baseUrl${path.removePrefix("/")}"
            } else {
                "$baseUrl${path.removePrefix("/")}$separator${"paged=$page"}"
            }
            Log.d(TAG, "searchAnimeRequest: page=$page query=$query path=$path url=$url")
            GET(url, navHeaders())
        } else if (path == "/") {
            val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page"
            Log.d(TAG, "searchAnimeRequest: page=$page query=$query path=$path url=$url")
            GET(url, navHeaders())
        } else {
            val url = "$baseUrl$path/page/$page"
            Log.d(TAG, "searchAnimeRequest: page=$page query=$query path=$path url=$url")
            GET(url, navHeaders())
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        Log.d(TAG, "searchAnimeParse: code=${response.code} url=${response.request.url}")
        val document = response.asJsoup()
        val animes = document.select("a.hv2-card[href], div.result-item div.image a, div.content article > div.poster, article div.poster, article a:has(img)")
            .map { element ->
                if (element.`is`("a")) searchAnimeFromElement(element) else popularAnimeFromElement(element)
            }
            .distinctBy { it.url }

        val hasNextPage = document.selectFirst("a#vv2more[rel=next], a.vv2-more-btn[rel=next], a[href*=&paged=], ${searchAnimeNextPageSelector()}") != null
        Log.d(TAG, "searchAnimeParse: items=${animes.size} hasNext=$hasNextPage")
        animes.take(8).forEachIndexed { index, anime ->
            Log.d(TAG, "searchAnimeParse[$index]: title=${anime.title} url=${anime.url} thumb=${anime.thumbnail_url}")
        }

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return runCatching { super.searchAnimeFromElement(element) }.getOrElse {
            SAnime.create().apply {
                val link = if (element.`is`("a")) element else element.selectFirst("a[href]")
                val img = element.selectFirst("img")
                setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
                title = element.selectFirst(".hv2-t")?.text()
                    ?: link?.attr("title")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("alt")?.ifBlank { link?.text() }
                        .orEmpty()
                thumbnail_url = img?.detodoImageUrl()
            }
        }.also { anime ->
            anime.thumbnail_url = element.selectFirst("img")?.detodoImageUrl() ?: anime.thumbnail_url?.toWebpVariant()
            Log.d(TAG, "searchAnimeFromElement: title=${anime.title} url=${anime.url} thumb=${anime.thumbnail_url}")
        }
    }

    private fun Element.detodoImageUrl(): String? {
        return sequenceOf(
            attr("abs:data-lazy-src"),
            attr("abs:data-src"),
            attr("abs:src"),
            attr("data-lazy-src"),
            attr("data-src"),
            attr("src"),
            attr("abs:srcset").substringBefore(" "),
            attr("srcset").substringBefore(" "),
        )
            .firstOrNull { it.isNotBlank() }
            ?.let(::normalizeUrl)
            ?.toWebpVariant()
    }

    private fun String.toWebpVariant(): String {
        val cleanUrl = substringBefore("?")
        return if (
            contains("/wp-content/uploads/", ignoreCase = true) &&
            !cleanUrl.endsWith(".webp", ignoreCase = true) &&
            listOf(".jpg", ".jpeg", ".png").any { cleanUrl.endsWith(it, ignoreCase = true) }
        ) {
            "$this.webp"
        } else {
            this
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
        screen.addPreference(langPref)
    }

// ============================= Utilities ==============================
    override fun String.toDate() = 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault) ?: prefQualityDefault
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "[LAT]"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private const val TAG = "DeTodoPeliculas"
        private const val CLOUDFLARE_SOLVE_CACHE_MS = 60_000L
        private val PREF_LANG_ENTRIES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val PREF_LANG_VALUES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload", "VidGuard", "VidHide", "Okru", "Voe", "Filemoon", "Netu", "Byse")
    }
}
