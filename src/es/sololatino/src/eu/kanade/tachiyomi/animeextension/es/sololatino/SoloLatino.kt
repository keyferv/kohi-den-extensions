package eu.kanade.tachiyomi.animeextension.es.sololatino

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class SoloLatino : DooPlay(
    "es",
    "SoloLatino",
    "https://sololatino.net",
) {

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Accept-Language", "es-419,es;q=0.9,en;q=0.8")

    override val fetchGenres = false

    override fun getFilterList() = SoloLatinoFilters.FILTER_LIST

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/peliculas?page=$page", headers)

    override fun popularAnimeSelector() = CARD_SELECTOR

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromCard(element)

    override fun popularAnimeNextPageSelector(): String = "a[href*='page=']"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/series?page=$page", headers)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromCard(element)

    private fun animeFromCard(element: Element): SAnime = SAnime.create().apply {
        val img = element.selectFirst("img.card__poster, img[src]")
        val titleElement = element.selectFirst(".card__title")
        setUrlWithoutDomain(element.attr("abs:href"))
        title = titleElement?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: "Serie/Película"
        thumbnail_url = img?.getImageUrl()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = doc.select("a.ep-item[href*=/temporada-][href*=/episodio-]")
            .mapNotNull(::episodeFromElementSafe)

        return if (episodes.isEmpty()) {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                    name = episodeMovieText
                    date_upload = doc.selectFirst("main span")?.text()?.toDate() ?: 0L
                },
            )
        } else {
            episodes.reversed()
        }
    }

    private fun episodeFromElementSafe(element: Element): SEpisode? {
        val href = element.attr("abs:href").takeIf(String::isNotBlank) ?: return null
        val season = SEASON_EPISODE_REGEX.find(href)?.groupValues?.get(1) ?: "0"
        val episode = SEASON_EPISODE_REGEX.find(href)?.groupValues?.get(2) ?: "0"
        val title = element.selectFirst("p.text-sm.font-semibold")?.text()?.trim()
            ?: element.selectFirst(".ep-num")?.text()?.trim()
            ?: "Episodio $episode"

        return SEpisode.create().apply {
            setUrlWithoutDomain(href)
            episode_number = episode.toFloatOrNull() ?: 0F
            name = "T$season - Episodio $episode: $title"
            date_upload = element.select("p.text-xs")
                .asSequence()
                .map { it.text().trim() }
                .firstOrNull { DATE_SLASH_REGEX.matches(it) }
                ?.toDate() ?: 0L
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = episodeFromElementSafe(element)
        ?: throw IllegalArgumentException("Invalid episode element")

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    // ============================ Video Links =============================
    override fun videoListSelector() = "[data-player-token]"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val tokens = document.select(videoListSelector())
            .map { it.attr("data-player-token") }
            .filter(String::isNotBlank)
            .distinct()

        return tokens.flatMap { token ->
            val playerUrl = resolvePlayerUrl(token, referer) ?: return@flatMap emptyList()
            when {
                PLAYER_HOST in playerUrl -> extractPelisSeriesHoyVideos(playerUrl)
                playerUrl.isDirectVideoUrl() -> listOf(newVideo(playerUrl, "SoloLatino", referer))
                else -> emptyList()
            }
        }.sort()
    }

    private fun resolvePlayerUrl(token: String, referer: String): String? {
        fetchXsrfCookie(referer)
        val xsrfToken = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .firstOrNull { it.name == "XSRF-TOKEN" }
            ?.value
            ?.let { URLDecoder.decode(it, "UTF-8") }

        val body = "{\"t\":\"$token\"}".toRequestBody(JSON_MEDIA_TYPE)
        val apiHeaders = headersBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", referer)
            .apply {
                if (!xsrfToken.isNullOrBlank()) {
                    set("X-XSRF-TOKEN", xsrfToken)
                } else {
                    extractCsrfTokenFromReferer(referer)?.let { set("X-CSRF-TOKEN", it) }
                }
            }
            .build()

        return client.newCall(POST("$baseUrl/api/player-url", apiHeaders, body)).execute().use { apiResponse ->
            if (!apiResponse.isSuccessful) return null
            PLAYER_URL_REGEX.find(apiResponse.body.string())
                ?.groupValues
                ?.get(1)
                ?.replace("\\/", "/")
        }
    }

    private fun fetchXsrfCookie(referer: String) {
        client.newCall(GET("$baseUrl/sanctum/csrf-cookie", headersBuilder().set("Referer", referer).build()))
            .execute()
            .close()
    }

    private fun extractPelisSeriesHoyVideos(playerUrl: String): List<Video> {
        val playerHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val playerBody = client.newCall(GET(playerUrl, playerHeaders)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body.string()
        }
        val token = PLAYER_TOKEN_REGEX.find(playerBody)?.groupValues?.get(1) ?: return emptyList()
        val apiHeaders = headersBuilder()
            .set("Accept", "*/*")
            .set("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .set("Origin", PLAYER_BASE_URL)
            .set("Referer", playerUrl)
            .build()

        val servers = resolvePelisSeriesHoyServers(token, apiHeaders)
        if (servers.isEmpty()) return emptyList()

        postPelisSeriesHoyForm("a=click&tok=${token.urlEncode()}", apiHeaders)

        return servers.flatMap { (label, serverId) ->
            resolvePelisSeriesHoyServer(playerUrl, token, label, serverId, apiHeaders)
        }
    }

    private fun resolvePelisSeriesHoyServers(token: String, headers: okhttp3.Headers): List<Pair<String, String>> {
        val body = postPelisSeriesHoyForm("a=1&tok=${token.urlEncode()}", headers) ?: return emptyList()
        return PLAYER_SERVER_REGEX.findAll(body)
            .mapIndexed { index, match ->
                val label = match.groupValues[1].decodeJsonUnicodeEscapes().cleanServerLabel()
                    .ifBlank { "Server ${index + 1}" }
                label to match.groupValues[2]
            }
            .distinctBy { it.second }
            .toList()
    }

    private fun resolvePelisSeriesHoyServer(
        playerUrl: String,
        token: String,
        label: String,
        serverId: String,
        headers: okhttp3.Headers,
    ): List<Video> {
        val body = postPelisSeriesHoyForm(
            "a=2&v=${serverId.urlEncode()}&tok=${token.urlEncode()}",
            headers,
        ) ?: return emptyList()
        val streamBody = if (body.contains("\"msg\":\"no_click\"")) {
            postPelisSeriesHoyForm(
                "a=2&v=${serverId.urlEncode()}&tok=${token.urlEncode()}&r=1",
                headers,
            ) ?: return emptyList()
        } else {
            body
        }
        val streamUrl = STREAM_URL_REGEX.find(streamBody)
            ?.groupValues
            ?.get(1)
            ?.replace("\\/", "/")
            ?.toAbsolutePlayerUrl()
            ?: return emptyList()

        return listOf(newVideo(streamUrl, "SoloLatino - $label", playerUrl))
    }

    private fun postPelisSeriesHoyForm(body: String, headers: okhttp3.Headers): String? {
        return client.newCall(
            POST("$PLAYER_BASE_URL/s.php", headers, body.toRequestBody(FORM_MEDIA_TYPE)),
        ).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
    }

    private fun newVideo(videoUrl: String, quality: String, referer: String): Video {
        return Video(videoUrl, quality, videoUrl, headersBuilder().set("Referer", referer).build())
    }

    private fun extractCsrfTokenFromReferer(referer: String): String? {
        return runCatching {
            client.newCall(GET(referer, headers)).execute().use { response ->
                CSRF_TOKEN_REGEX.find(response.body.string())?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    // ============================== Search ================================
    override fun searchAnimeFromElement(element: Element): SAnime = animeFromCard(element)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SoloLatinoFilters.getSearchParameters(filters)
        val path = when {
            query.isNotBlank() -> "/buscar?q=${query.urlEncode()}&page=$page"
            params.genre.isNotBlank() -> "/genero/${params.genre}?page=$page"
            params.platform.isNotBlank() -> "/red/${params.platform}?page=$page"
            params.year.isNotBlank() -> "/buscar?year=${params.year}&page=$page"
            else -> when (params.type) {
                "serie" -> "/series?page=$page"
                "pelicula" -> "/peliculas?page=$page"
                "anime" -> "/animes?page=$page"
                "toon" -> "/genero/dibujos?page=$page"
                else -> "/buscar?sort=rating&page=$page"
            }
        }

        return GET("$baseUrl$path", headers)
    }

    // ============================= Details ================================
    override val additionalInfoSelector = "main"

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val main = document.selectFirst("main") ?: document
        val titleElement = main.selectFirst("h1:not(.sr-only), h1.sr-only")
        val poster = main.selectFirst("img[style*=aspect-ratio], img.w-44, img.w-52, img[src*=/w500/]")

        setUrlWithoutDomain(document.location())
        title = titleElement?.text()?.trim()
            ?: poster?.attr("alt")?.trim()
            ?: document.title().substringBefore(" — ").substringBefore(" | ").trim()
        thumbnail_url = poster?.getImageUrl()
        genre = main.select("a[href*=/genero/]")
            .eachText()
            .distinct()
            .joinToString()
        description = main.select("p.text-sm.leading-relaxed, p.line-clamp-3")
            .firstOrNull { it.text().length > 60 }
            ?.text()
            ?.trim()
            .orEmpty()
    }

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun String.toDate(): Long {
        val value = trim()
        return DATE_FORMATTERS.firstNotNullOfOrNull { formatter ->
            runCatching { formatter.parse(value)?.time }.getOrNull()
        } ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return sortedWith(compareBy { it.quality.contains(server, true) }).reversed()
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.toAbsolutePlayerUrl(): String {
        return if (startsWith("/")) "$PLAYER_BASE_URL$this" else this
    }

    private fun String.isDirectVideoUrl(): Boolean {
        return contains(".m3u8", ignoreCase = true) ||
            contains(".mp4", ignoreCase = true) ||
            contains("/p.php", ignoreCase = true)
    }

    private fun String.decodeJsonUnicodeEscapes(): String {
        return UNICODE_ESCAPE_REGEX.replace(this) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun String.cleanServerLabel(): String {
        return filter { it.isLetterOrDigit() || it.isWhitespace() || it == '+' || it == '-' }
            .trim()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private const val PLAYER_BASE_URL = "https://player.pelisserieshoy.com"
        private const val PLAYER_HOST = "player.pelisserieshoy.com"
        private const val CARD_SELECTOR = "a[href*=/serie/]:has(img.card__poster), a[href*=/pelicula/]:has(img.card__poster)"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()
        private val SEASON_EPISODE_REGEX = "/temporada-(\\d+)/episodio-(\\d+)".toRegex()
        private val PLAYER_URL_REGEX = """"url"\s*:\s*"([^"]+)""".toRegex()
        private val PLAYER_TOKEN_REGEX = """const\s+_t\s*=\s*'([^']+)'""".toRegex()
        private val PLAYER_SERVER_REGEX = """\["([^"]+)","([a-f0-9]{32})"\]""".toRegex()
        private val STREAM_URL_REGEX = """"u"\s*:\s*"([^"]+)""".toRegex()
        private val UNICODE_ESCAPE_REGEX = """\\u([0-9a-fA-F]{4})""".toRegex()
        private val CSRF_TOKEN_REGEX = """csrf-token"\s+content="([^"]+)""".toRegex()
        private val DATE_SLASH_REGEX = """\d{2}/\d{2}/\d{4}""".toRegex()
        private val DATE_FORMATTERS = listOf(
            SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH),
            SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es", "ES")),
            SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale.ENGLISH),
        )

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "[LAT]"
        private val PREF_LANG_ENTRIES = arrayOf("Latino", "Castellano", "Subtitulado", "Unknown")
        private val PREF_LANG_VALUES = arrayOf("[LAT]", "[CAST]", "[SUB]", "[UNK]")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "SoloLatino"
        private val SERVER_LIST = arrayOf("SoloLatino")
    }
}
