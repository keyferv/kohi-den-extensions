package eu.kanade.tachiyomi.animeextension.es.pelisjuanita

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.pelisjuanita.extractors.ByseExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.text.ifEmpty
import kotlin.text.lowercase

class PelisJuanita : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "PelisJuanita"

    override val baseUrl = "https://pelisjuanita.com"

    override val lang = "es"

    override val supportsLatest = true

    // ========================== Browser-like Headers ==========================

    /** Headers base with the same Android WebView fingerprint that solves Cloudflare. */
    private val browserHeaders: Headers by lazy {
        headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
            .set("Accept-Language", "es-US,es;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
    }

    /** Headers para requests de navegación HTML (home, detalle, listados) */
    private fun navHeaders(referer: String = "$baseUrl/"): Headers {
        return browserHeaders.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .set("sec-fetch-site", "same-origin")
            .set("sec-fetch-mode", "navigate")
            .set("sec-fetch-dest", "document")
            .set("Referer", referer)
            .set("Upgrade-Insecure-Requests", "1")
            .build()
    }

    /** Headers para endpoints AJAX (serieInfo.php, movieInfo.php, apiSeries.php, etc.) */
    private fun ajaxHeaders(referer: String): Headers {
        return browserHeaders.newBuilder()
            .set("Accept", "*/*")
            .set("sec-fetch-site", "same-origin")
            .set("sec-fetch-mode", "cors")
            .set("sec-fetch-dest", "empty")
            .set("Referer", referer)
            .build()
    }

    // ========================== Client with CF interceptor ==========================

    private val cfInterceptor by lazy { CloudflareInterceptor(super.client.newBuilder().build()) }

    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
            .addInterceptor(CfMitigatedInterceptor(cfInterceptor, this))
            .build()
    }

    /**
     * Resuelve el challenge de Cloudflare cargando baseUrl en WebView.
     * Sincroniza las cookies (cf_clearance, PHPSESSID) del WebView al cookieJar de OkHttp
     * con el scheme HTTPS correcto.
     *
     * CloudflareInterceptor.resolveWithWebView guarda cookies con scheme http (bug),
     * por lo que hacemos nuestra propia sincronización después.
     */
    fun solveCloudflare(request: Request): Boolean {
        return try {
            Log.d("PelisJuanita", "solveCloudflare: loading $baseUrl in WebView...")
            val warmRequest = okhttp3.Request.Builder()
                .url("$baseUrl/")
                .headers(browserHeaders)
                .build()

            // resolveWithWebView carga la URL en WebView, espera a que CF pase,
            // y devuelve un Request con cookies en el header
            val resolved = cfInterceptor.resolveWithWebView(warmRequest, client)

            // Además, sincronizar cookies del CookieManager de WebView al cookieJar de OkHttp
            // con el scheme HTTPS correcto (CloudflareInterceptor usa http, que es un bug)
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookieStr = cookieManager?.getCookie("$baseUrl/") ?: ""
            Log.d("PelisJuanita", "solveCloudflare: WebView cookies present = ${cookieStr.isNotBlank()}")

            if (cookieStr.isNotBlank()) {
                val pelisUrl = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("pelisjuanita.com")
                    .build()
                val cookies = cookieStr.split(";")
                    .mapNotNull { Cookie.parse(pelisUrl, it.trim()) }

                // Guardar cookies con scheme HTTPS correcto
                cookies.forEach { cookie ->
                    client.cookieJar.saveFromResponse(
                        url = pelisUrl,
                        cookies = cookies,
                    )
                }
                Log.d("PelisJuanita", "solveCloudflare: saved ${cookies.size} cookies to jar with HTTPS")
            }

            true
        } catch (e: Exception) {
            Log.e("PelisJuanita", "solveCloudflare FAILED: ${e.message}")
            false
        }
    }

    /**
     * Interceptor que detecta 403 con cf-mitigated y resuelve CF automáticamente.
     * Sincroniza cookies del WebView al cookieJar con scheme HTTPS y reintenta.
     */
    private class CfMitigatedInterceptor(
        private val cfInterceptor: CloudflareInterceptor,
        private val source: PelisJuanita,
    ) : okhttp3.Interceptor {
        @Volatile
        private var warmed = false

        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val request = chain.request()

            // Pre-warm una sola vez
            if (!warmed) {
                warmed = true
                source.solveCloudflare(request)
            }

            val response = chain.proceed(request)

            if (response.code != 403 || response.header("cf-mitigated") == null) {
                return response
            }

            Log.d("PelisJuanita", "cf-mitigated challenge detected on ${request.url}")
            response.close()

            // Resolver CF y sincronizar cookies
            val solved = source.solveCloudflare(request)
            if (!solved) {
                Log.e("PelisJuanita", "cf-mitigated: could not resolve, returning original")
                return chain.proceed(request)
            }

            // Reintentar el request original (las cookies ya están en el cookieJar)
            Log.d("PelisJuanita", "cf-mitigated: retrying original request with jar cookies")
            val retryResponse = chain.proceed(request)
            Log.d("PelisJuanita", "cf-mitigated retry result: ${retryResponse.code}")
            return retryResponse
        }
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[CAST]", "[SUB]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "Voe", "StreamWish", "Doodstream", "Filemoon",
            "Okru", "StreamTape", "Mp4Upload", "Uqload", "VidGuard",
            "Earnvids", "Byse",
        )
    }

    // ========================== Popular (Películas) ==========================

    override fun popularAnimeRequest(page: Int): Request {
        Log.d("PelisJuanita", "popularAnimeRequest: page=$page")
        return GET("$baseUrl/movies/movies.php?estrenos=$page", ajaxHeaders("$baseUrl/movies/"))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        Log.d("PelisJuanita", "popularAnimeParse: code=${response.code}, url=${response.request.url}")
        return parseGridItems(response)
    }

    // ========================== Últimas Novedades (Series) ==========================

    override fun latestUpdatesRequest(page: Int): Request {
        Log.d("PelisJuanita", "latestUpdatesRequest: page=$page")
        return GET("$baseUrl/series/apiSeries.php?=$page", ajaxHeaders("$baseUrl/series/"))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        Log.d("PelisJuanita", "latestUpdatesParse: code=${response.code}, url=${response.request.url}")
        return parseGridItems(response)
    }

    // ========================== Parsing común de listados ==========================

    private fun parseGridItems(response: Response): AnimesPage {
        Log.d("PelisJuanita", "parseGridItems: code=${response.code}, url=${response.request.url}")
        val document = response.asJsoup()
        val requestUrl = response.request.url.toString()
        val items = document.select("div.grid-item, .item-tira, a.fav-item, .cartelera-item")
        val animeList = items.mapNotNull { element ->
            val link = if (element.`is`("a[href]")) {
                element
            } else element.selectFirst("a[href]")
                ?: return@mapNotNull null
            var href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null

            // Resolver URLs relativas
            // Series: href es "ver-serie/slug" → /series/ver-serie/slug
            // Películas: href es "/movies/pelicula/slug" → ya es absoluto
            if (!href.startsWith("/") && !href.startsWith("http")) {
                // URL relativa - determinar prefijo según la URL del request
                val prefix = when {
                    requestUrl.contains("/series/") -> "/series/"
                    requestUrl.contains("/movies/") -> "/movies/"
                    else -> "/"
                }
                href = "$prefix$href"
            }

            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = element.selectFirst("h2, .texto-tira, h1")?.text()
                    ?: element.attr("aria-label").takeIf { it.isNotBlank() }
                        ?.removePrefix("Ver película ")
                        ?.removePrefix("Ver serie ")
                        ?.substringBefore(" (Película)")
                        ?.substringBefore(" (Serie)")
                    ?: element.selectFirst("img[alt]")?.attr("alt")
                    ?: ""
                thumbnail_url = element.selectFirst("img.img-cover, img.img-tira, img.cartelera-bg, img.fav-img-tapa, img[src]")?.attr("abs:src")
            }
        }.distinctBy { it.url }
        Log.d("PelisJuanita", "parseGridItems: found ${animeList.size} items")
        return AnimesPage(animeList, false)
    }

    // ========================== Búsqueda ==========================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        Log.d("PelisJuanita", "searchAnimeRequest: query=$query, page=$page")
        if (query.isNotBlank()) {
            return GET("$baseUrl/series/apiSeries.php?s=$query", ajaxHeaders("$baseUrl/series/"))
        }

        val contentType = (filters.find { it is ContentTypeFilter } as? ContentTypeFilter)?.toUriPart() ?: "movies"
        val genre = (filters.find { it is GenreFilter } as? GenreFilter)?.toUriPart() ?: ""
        val platform = (filters.find { it is PlatformFilter } as? PlatformFilter)?.toUriPart() ?: ""
        val country = (filters.find { it is CountryFilter } as? CountryFilter)?.toUriPart() ?: ""
        val year = (filters.find { it is YearFilter } as? YearFilter)?.toUriPart() ?: ""
        val studio = (filters.find { it is StudioFilter } as? StudioFilter)?.toUriPart() ?: ""

        val basePath = if (contentType == "series") "$baseUrl/series" else "$baseUrl/movies"

        val url = when {
            genre.isNotBlank() -> "$basePath/genero/$genre"
            platform.isNotBlank() -> "$basePath/plataforma/$platform"
            country.isNotBlank() -> "$basePath/pais/$country"
            year.isNotBlank() -> "$basePath/release/$year"
            studio.isNotBlank() -> "$basePath/productora/$studio"
            else -> if (contentType == "series") "$baseUrl/series/apiSeries.php?=$page" else "$baseUrl/movies/movies.php?estrenos=$page"
        }
        return GET(url, navHeaders("$basePath/"))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        Log.d("PelisJuanita", "searchAnimeParse: code=${response.code}, url=${response.request.url}")
        return parseGridItems(response)
    }

    // ========================== Detalles ==========================

    override fun animeDetailsParse(response: Response): SAnime {
        Log.d("PelisJuanita", "animeDetailsParse: code=${response.code}, url=${response.request.url}")
        val document = response.asJsoup()
        val url = response.request.url.toString()
        Log.d("PelisJuanita", "animeDetailsParse called with URL: $url")
        val isSeries = url.contains("/ver-serie/")

        return SAnime.create().apply {
            val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:title]")?.attr("content")
                ?: document.title()

            title = ogTitle
                .replace(Regex("(?i)^Ver Serie\\s+"), "")
                .replace(Regex("(?i)^Ver Pelicula\\s+"), "")
                .replace(Regex("(?i)^Ver Película\\s+"), "")
                .replace(Regex("(?i)\\s+Online en HD Gratis\\s*\\|\\s*Pelis Juanita"), "")
                .replace(Regex("(?i)\\s*\\|\\s*Pelis Juanita"), "")
                .replace(Regex("(?i)\\s*\\(\\d{4}\\)"), "")
                .trim()

            Log.d("PelisJuanita", "animeDetailsParse title: $title")

            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")

            status = if (isSeries) SAnime.ONGOING else SAnime.COMPLETED
            Log.d("PelisJuanita", "animeDetailsParse finished: title=$title, status=$status")
        }
    }

    // ========================== Episodios ==========================

    override fun episodeListParse(response: Response): List<SEpisode> {
        Log.d("PelisJuanita", "episodeListParse: code=${response.code}, url=${response.request.url}")
        val document = response.asJsoup()
        val url = response.request.url.toString()
        Log.d("PelisJuanita", "episodeListParse called with URL: $url")
        val isSeries = url.contains("/ver-serie/")

        return if (isSeries) {
            // Extraer el slug de la serie de la URL
            val slug = url.trimEnd('/').substringAfterLast("/")
            Log.d("PelisJuanita", "episodeListParse slug: $slug")

            val allEpisodes = mutableListOf<SEpisode>()
            val parsedSeasons = mutableSetOf<Int>()

            // 1. Petición inicial a serieInfo.php sin parámetros de temporada para obtener el dropdown y temporada por defecto
            val firstUrl = "$baseUrl/series/serieInfo.php?nombreSerie=$slug"
            val refererUrl = "$baseUrl/series/ver-serie/$slug"
            Log.d("PelisJuanita", "episodeListParse fetching initial: $firstUrl")
            val firstResponse = client.newCall(GET(firstUrl, ajaxHeaders(refererUrl))).execute()
            if (!firstResponse.isSuccessful) throw Exception("Error al obtener serieInfo: ${firstResponse.code}")
            val firstDoc = firstResponse.asJsoup()

            // 2. Extraer las temporadas desde el dropdown
            val seasonElements = firstDoc.select(".server-item[onclick*=cargarEpisodiosDeTemporada]")
            val seasonNumbers = seasonElements.mapNotNull {
                val onclick = it.attr("onclick")
                Regex("""cargarEpisodiosDeTemporada\('(\d+)'\)""").find(onclick)?.groupValues?.get(1)?.toIntOrNull()
            }.distinct()
            Log.d("PelisJuanita", "episodeListParse seasonNumbers from dropdown: $seasonNumbers")

            // 3. Parsear capítulos de la temporada cargada inicialmente (usando 1 de fallback para la temporada)
            val initialEpisodes = parseEpisodesFromDocument(firstDoc, 1, slug)
            allEpisodes.addAll(initialEpisodes)

            // Registrar las temporadas que ya fueron cargadas
            initialEpisodes.forEach { ep ->
                val epSeason = (ep.episode_number.toInt() / 1000)
                if (epSeason > 0) parsedSeasons.add(epSeason)
            }
            Log.d("PelisJuanita", "episodeListParse already parsed seasons: $parsedSeasons")

            // 4. Cargar las demás temporadas
            for (seasonNum in seasonNumbers) {
                if (parsedSeasons.contains(seasonNum)) continue
                val seasonUrl = "$baseUrl/series/serieInfo.php?nombreSerie=$slug&temporada=$seasonNum&snum=$seasonNum&enum=1"
                Log.d("PelisJuanita", "episodeListParse fetching other seasonUrl: $seasonUrl")
                val seasonResponse = runCatching {
                    client.newCall(GET(seasonUrl, ajaxHeaders(refererUrl))).execute()
                }.getOrNull()

                if (seasonResponse != null && seasonResponse.isSuccessful) {
                    val seasonDoc = seasonResponse.asJsoup()
                    val seasonEpisodes = parseEpisodesFromDocument(seasonDoc, seasonNum, slug)
                    allEpisodes.addAll(seasonEpisodes)
                } else {
                    Log.d("PelisJuanita", "episodeListParse failed to fetch season $seasonNum")
                }
            }

            Log.d("PelisJuanita", "episodeListParse allEpisodes size before sorting: ${allEpisodes.size}")

            // 1. Ordenar cronológicamente (Temporada ASC, Episodio ASC) usando el valor temporal
            val chronologicalEpisodes = allEpisodes.sortedWith(
                compareBy<SEpisode> { it.episode_number.toInt() / 1000 }
                    .thenBy { it.episode_number.toInt() % 1000 },
            )

            // 2. Asignar episode_number secuencial real (1, 2, 3...) para evitar "missing 900 items" en Tachiyomi
            chronologicalEpisodes.forEachIndexed { index, ep ->
                ep.episode_number = (index + 1).toFloat()
            }

            // 3. Invertir para devolver del más nuevo al más viejo (convención de Tachiyomi)
            val sortedEpisodes = chronologicalEpisodes.reversed()

            Log.d("PelisJuanita", "episodeListParse returning ${sortedEpisodes.size} episodes")
            sortedEpisodes
        } else {
            Log.d("PelisJuanita", "episodeListParse movie returning 1 episode")
            // Película = un solo episodio
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(url)
                    name = "Película"
                    episode_number = 1F
                },
            )
        }
    }

    private fun parseEpisodesFromDocument(document: org.jsoup.nodes.Document, seasonNum: Int, slug: String): List<SEpisode> {
        val items = document.select(".episodio-item")
            .ifEmpty { document.select(".ul-temporada a, #ulTemporada a") }

        return items.map { element ->
            var href = element.attr("href")
            // Si el elemento es un div sin href, buscamos el onclick de toggleVisto o similar
            if (href.isBlank()) {
                val onclick = element.selectFirst("[onclick*=toggleVisto]")?.attr("onclick") ?: ""
                href = Regex("""toggleVisto\(['\"]([^'\"]+)['\"]""").find(onclick)?.groupValues?.get(1) ?: ""
            }

            // Resolver URL relativa
            if (href.isNotBlank() && !href.startsWith("/") && !href.startsWith("http")) {
                href = "/series/$href"
            }

            // Extraer temporada y episodio del href/onclick o del h2 text
            val textToMatch = href.ifBlank { element.text() }
            val hrefMatch = Regex("""(\d+)x(\d+)""").find(textToMatch)
            val actualSeason = hrefMatch?.groupValues?.get(1)?.toIntOrNull() ?: seasonNum
            val epNum = hrefMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1

            // Título del episodio desde el h2 o fallback
            val titleText = element.selectFirst("h2.list-title")?.text() ?: ""
            val epTitle = if (titleText.contains("[")) {
                // Formato: [01x01] Título del episodio
                titleText.substringAfter("]").trim()
            } else {
                titleText
            }

            val uploadDate = runCatching {
                val infoText = element.selectFirst(".episodio-meta span.episodio-info, .episodio-info")?.text() ?: ""
                val dateMatch = Regex("""\d{2}/\d{2}/\d{4}""").find(infoText)
                val dateText = dateMatch?.value ?: ""
                if (dateText.isNotBlank()) {
                    SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(dateText)?.time ?: 0L
                } else {
                    0L
                }
            }.getOrDefault(0L)

            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                name = "T${actualSeason}xE${"%02d".format(epNum)} ${epTitle.ifBlank { "Episodio $epNum" }}"
                episode_number = (actualSeason * 1000 + epNum).toFloat()
                date_upload = uploadDate
            }
        }
    }

    // ========================== Videos ==========================

    override fun videoListRequest(episode: SEpisode): Request {
        Log.d("PelisJuanita", "videoListRequest: episode=${episode.url}")
        val url = episode.url
        return when {
            url.contains("/ver-serie/") -> {
                // Formato: /series/ver-serie/slug/SSxEE
                val segments = url.trimEnd('/').split("/")
                val slug = segments.getOrNull(segments.size - 2) ?: ""
                val epString = segments.lastOrNull() ?: ""
                val epMatch = Regex("""(\d+)x(\d+)""").find(epString)
                val season = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = epMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
                GET(
                    "$baseUrl/series/serieInfo.php?nombreSerie=$slug&nroTemporada=$season&nroEpisodio=$episodeNum",
                    ajaxHeaders("$baseUrl/series/ver-serie/$slug"),
                )
            }
            url.contains("/pelicula/") -> {
                // Formato: /movies/pelicula/slug
                val slug = url.substringAfter("/pelicula/").substringBefore("/")
                GET(
                    "$baseUrl/movies/movieInfo.php?title=$slug",
                    ajaxHeaders("$baseUrl/movies/pelicula/$slug"),
                )
            }
            else -> super.videoListRequest(episode)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val html = response.body.string()
        val document = org.jsoup.Jsoup.parse(html)
        val videos = mutableListOf<Video>()
        Log.d("PelisJuanita", "videoListParse called with URL: $url")

        // Detectar idioma de la página
        val activeLang = "[LAT]"

        // 1. Intentar extraer servidores del JavaScript embebido (nuevo formato)
        val jsVideos = extractServersFromJs(html, url)
        if (jsVideos.isNotEmpty()) {
            Log.d("PelisJuanita", "videoListParse found ${jsVideos.size} videos from JS")
            videos.addAll(jsVideos)
        }

        // 2. Buscar servidores en los elementos .row-download (formato antiguo)
        if (videos.isEmpty()) {
            document.select(".row-download[data-tipo=stream]").forEach { row ->
                val streamUrl = row.attr("data-url").ifEmpty { row.attr("onclick") }
                    .let { onclick ->
                        if (onclick.startsWith("http")) {
                            onclick
                        } else {
                            Regex("""['\"](https?://[^'\"]+)['\"]""").find(onclick)?.groupValues?.get(1) ?: ""
                        }
                    }
                if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return@forEach

                val langPrefix = parseLanguage(row.attr("data-idioma"), row.text(), activeLang)

                Log.d("PelisJuanita", "videoListParse row-download streamUrl: $streamUrl, prefix: $langPrefix")
                videos.addAll(serverVideoResolver(streamUrl, langPrefix, url))
            }
        }

        // 3. Buscar en iframes si aún no hay videos
        if (videos.isEmpty()) {
            val iframe = document.selectFirst("iframe#if-video, .video-container iframe")
            val iframeSrc = iframe?.attr("src")?.ifEmpty { iframe.attr("data-src") }
            Log.d("PelisJuanita", "videoListParse fallback iframeSrc: $iframeSrc")

            if (!iframeSrc.isNullOrBlank()) {
                val playerVideos = extractFromPlayerPage(iframeSrc, activeLang)
                Log.d("PelisJuanita", "videoListParse playerVideos size: ${playerVideos.size}")
                videos.addAll(playerVideos)
            }

            // Buscar iframes/embeds alternativos en la página
            document.select("a[href*='player'], a[href*='embed'], .server-item a, iframe[data-src]").forEach { server ->
                val serverUrl = server.attr("href").ifEmpty { server.attr("data-src") }
                if (serverUrl.isNotBlank() && serverUrl.startsWith("http") && serverUrl != iframeSrc) {
                    val prefix = parseLanguage(server.text(), fallback = activeLang)
                    Log.d("PelisJuanita", "videoListParse alternate serverUrl: $serverUrl, prefix: $prefix")
                    videos.addAll(extractFromServer(serverUrl, prefix))
                }
            }
        }

        Log.d("PelisJuanita", "videoListParse returning ${videos.size} videos")
        return videos.sort()
    }

    private fun extractServersFromJs(html: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()

        // Buscar el objeto links en el JavaScript
        // Patrón: var links = {"hls":"url","hls2":"url",...}
        val linksPattern = Regex("""var\s+links\s*=\s*\{([^}]+)\}""")
        val linksMatch = linksPattern.find(html)

        if (linksMatch != null) {
            val linksContent = linksMatch.groupValues[1]
            Log.d("PelisJuanita", "extractServersFromJs found links: ${linksContent.take(200)}")

            // Extraer cada par clave-valor
            val urlPattern = Regex("""["'](\w+)["']\s*:\s*["'](https?://[^"']+)["']""")
            urlPattern.findAll(linksContent).forEach { match ->
                val key = match.groupValues[1]
                val url = match.groupValues[2]

                val langPrefix = parseLanguage(key)

                val serverName = when {
                    "voe" in url.lowercase() -> "Voe"
                    "streamwish" in url.lowercase() || "wish" in url.lowercase() -> "StreamWish"
                    "dood" in url.lowercase() -> "DoodStream"
                    "filemoon" in url.lowercase() -> "Filemoon"
                    "okru" in url.lowercase() || "ok.ru" in url.lowercase() -> "Okru"
                    "streamtape" in url.lowercase() -> "StreamTape"
                    "mp4upload" in url.lowercase() -> "Mp4Upload"
                    "uqload" in url.lowercase() -> "Uqload"
                    "vidguard" in url.lowercase() || "guard" in url.lowercase() -> "VidGuard"
                    "earnvids" in url.lowercase() || "callistanise" in url.lowercase() || "ryderjet" in url.lowercase() -> "Earnvids"
                    "byse" in url.lowercase() -> "Byse"
                    "embed69" in url.lowercase() -> "Embed69"
                    else -> key
                }

                Log.d("PelisJuanita", "extractServersFromJs server: $serverName, url: $url, lang: $langPrefix")
                videos.addAll(serverVideoResolver(url, "$langPrefix $serverName:", referer))
            }
        }

        // Buscar también en el setup de JWPlayer
        // Patrón: jwplayer("vplayer").setup({file: "url", ...})
        val jwplayerPattern = Regex("""jwplayer\s*\([^)]*\)\s*\.\s*setup\s*\(\s*\{[^}]*file\s*:\s*["'](https?://[^"']+)["']""")
        val jwplayerMatch = jwplayerPattern.find(html)

        if (jwplayerMatch != null && videos.isEmpty()) {
            val fileUrl = jwplayerMatch.groupValues[1]
            Log.d("PelisJuanita", "extractServersFromJs found JWPlayer file: $fileUrl")
            videos.addAll(serverVideoResolver(fileUrl, "[LAT] JWPlayer:", referer))
        }

        // Buscar URLs de video directamente en el HTML
        // Patrón: src="blob:..." o src="https://...m3u8"
        if (videos.isEmpty()) {
            val videoUrlPattern = Regex("""(?:src|file)\s*[:=]\s*["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"']*)["']""")
            videoUrlPattern.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                Log.d("PelisJuanita", "extractServersFromJs found direct video URL: $url")
                videos.addAll(serverVideoResolver(url, "[LAT]", referer))
            }
        }

        // Buscar en el HTML de la tabla de servidores (nuevo formato)
        // Patrón: <a href="url" class="server-link">
        val doc = Jsoup.parse(html)
        doc.select("a[href*='getvideo'], a[href*='embed'], a.server-link, .server-item a").forEach { element ->
            val href = element.attr("href")
            if (href.isNotBlank() && href.startsWith("http")) {
                val langPrefix = parseLanguage(element.text())
                Log.d("PelisJuanita", "extractServersFromJs HTML server: ${element.text()}, url: $href")
                videos.addAll(serverVideoResolver(href, langPrefix, referer))
            }
        }

        return videos
    }

    private fun parseLangFromOnclick(onclick: String): String {
        val lang = Regex("""filtrarServidor\(['\"]([^'\"]+)['\"]""").find(onclick)?.groupValues?.get(1) ?: "latino"
        return parseLanguage(lang)
    }

    private fun parseLanguage(vararg values: String, fallback: String = "[LAT]"): String {
        val normalized = values
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()
            .replace('á', 'a')
            .replace('é', 'e')
            .replace('í', 'i')
            .replace('ó', 'o')
            .replace('ú', 'u')

        if (normalized.isBlank()) return fallback

        return when {
            listOf("sub", "subtitulado", "subtitulada", "subtitulos", "vose", "vost").any { it in normalized } -> "[SUB]"
            listOf("cast", "castellano", "espanol", "español", "esp", "espana", "españa").any { it in normalized } -> "[CAST]"
            listOf("lat", "latino", "latina", "latin", "mx", "mex", "mexico", "mexicano").any { it in normalized } -> "[LAT]"
            else -> fallback
        }
    }

    private fun extractFromPlayerPage(playerUrl: String, defaultPrefix: String): List<Video> {
        return runCatching {
            val playerDoc = client.newCall(GET(playerUrl, navHeaders(baseUrl))).execute().asJsoup()
            val videos = mutableListOf<Video>()

            // Servidores de streaming: <div class="row-download" data-idioma="latino" data-tipo="stream" data-url="...">
            playerDoc.select(".row-download[data-tipo=stream]").forEach { row ->
                val streamUrl = row.attr("data-url").ifEmpty { row.attr("onclick") }
                    .let { onclick ->
                        if (onclick.startsWith("http")) {
                            onclick
                        } else Regex("""cargarServior\(['\"]([^'\"]+)['\"]""").find(onclick)?.groupValues?.get(1) ?: ""
                    }
                if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return@forEach

                val langPrefix = parseLanguage(row.attr("data-idioma"), row.text(), fallback = defaultPrefix)

                videos.addAll(serverVideoResolver(streamUrl, langPrefix, playerUrl))
            }

            videos
        }.getOrNull() ?: emptyList()
    }

    private fun extractFromServer(url: String, prefix: String): List<Video> {
        return runCatching {
            val response = client.newCall(GET(url, navHeaders(baseUrl))).execute().asJsoup()
            val iframe = response.selectFirst("iframe")
            val src = iframe?.attr("src")?.ifEmpty { iframe.attr("data-src") } ?: return@runCatching emptyList()
            serverVideoResolver(src, prefix, url)
        }.getOrNull() ?: emptyList()
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val byseExtractor by lazy { ByseExtractor(client, headers, baseUrl) }

    private fun serverVideoResolver(url: String, prefix: String = "", referer: String): List<Video> {
        return runCatching {
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
            val newHeaders = headers.newBuilder().add("Referer", referer).build()
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "okru" -> okruExtractor.videosFromUrl(url, prefix, headers = newHeaders)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                "uqload" -> uqloadExtractor.videosFromUrl(url, prefix)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, newHeaders, prefix = "$prefix ")
                "streamwish" -> StreamWishExtractor(client, newHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "doodstream" -> doodExtractor.videosFromUrl(url, "$prefix DoodStream")
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                "vidguard" -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
                "vidhide" -> VidHideExtractor(client, newHeaders).videosFromUrl(url) { "$prefix Earnvids:$it" }
                "byse" -> byseExtractor.videosFromUrl(url, prefix)
                else -> emptyList()
            }
        }.getOrNull() ?: emptyList()
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "uqload" to listOf("uqload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "ghbrisk", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "waaw" to listOf("waaw", "netu", "hqq"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
        "vidhide" to listOf("vidhide", "streamhide", "earnvids", "callistanise", "ryderjet"),
        "byse" to listOf("byse", "bysesukior"),
    )

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ========================== Filtros ==========================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro"),
        ContentTypeFilter(),
        GenreFilter(),
        PlatformFilter(),
        CountryFilter(),
        YearFilter(),
        StudioFilter(),
    )

    private class ContentTypeFilter : UriPartFilter(
        "Tipo de contenido",
        arrayOf(
            Pair("Películas", "movies"),
            Pair("Series", "series"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            // Películas
            Pair("Seleccionar", ""),
            Pair("Anime", "anime"),
            Pair("Animación", "animación"),
            Pair("Acción (Pelis)", "accin"),
            Pair("Acción (Series)", "acion"),
            Pair("Aventura", "aventura"),
            Pair("Bélica (Pelis)", "belica"),
            Pair("Bélica (Series)", "guerra"),
            Pair("Ciencia ficción", "ficción"),
            Pair("Comedia", "comedia"),
            Pair("Crimen", "crimen"),
            Pair("Cristiana", "cristiana"),
            Pair("Documental", "documental"),
            Pair("Dorama", "dorama"),
            Pair("Drama", "drama"),
            Pair("Familia", "familia"),
            Pair("Fantasía", "fantasía"),
            Pair("Historia", "historia"),
            Pair("Misterio", "misterio"),
            Pair("Música", "música"),
            Pair("Película de TV", "tv"),
            Pair("Romance", "romance"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
            Pair("Western", "western"),
            Pair("Adultos", "adultos"),
        ),
    )

    private class PlatformFilter : UriPartFilter(
        "Plataforma",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Netflix", "netflix"),
            Pair("Amazon Video", "amazon-video"),
            Pair("Disney+", "disney-plus"),
            Pair("HBO Max", "hbo-max"),
            Pair("Apple TV", "apple-tv"),
            Pair("Hulu", "hulu"),
            Pair("Paramount+", "paramount-plus"),
            Pair("YouTube", "youtube"),
            Pair("Rakuten TV", "rakuten-tv"),
            Pair("Google Play", "google-play-movies"),
        ),
    )

    private class CountryFilter : UriPartFilter(
        "País",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Argentina", "ar"),
            Pair("México", "mx"),
            Pair("España", "es"),
            Pair("Estados Unidos", "us"),
            Pair("Francia", "fr"),
            Pair("Brasil", "br"),
            Pair("Reino Unido", "gb"),
            Pair("Italia", "it"),
            Pair("Alemania", "de"),
            Pair("Japón", "jp"),
            Pair("Canadá", "ca"),
            Pair("Australia", "au"),
        ),
    )

    private class YearFilter : UriPartFilter(
        "Año",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("2026", "2026"),
            Pair("2025", "2025"),
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1999", "1999"),
            Pair("1998", "1998"),
            Pair("1997", "1997"),
        ),
    )

    private class StudioFilter : UriPartFilter(
        "Productora",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Marvel", "marvel"),
            Pair("DC", "dc"),
            Pair("Universal", "universal"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    // ========================== Preferencias ==========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Idioma preferido"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Calidad preferida"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Servidor preferido"
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
    }
}
