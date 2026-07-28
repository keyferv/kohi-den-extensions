package eu.kanade.tachiyomi.multisrc.yflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.rapidshareextractor.RapidShareExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

open class YFlixTheme(
    override val name: String,
    protected val domainList: List<String>,
    protected val defaultDomain: String = "https://${domainList.first()}",
    override val lang: String = "en",
) : AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest: Boolean = true

    protected open val context: Application by injectLazy()

    protected open val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    protected open val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String by lazy {
        preferences.getString(PREF_DOMAIN_KEY, defaultDomain) ?: defaultDomain
    }

    protected open val encdecHeaders by lazy {
        headers.newBuilder().apply {
            set(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36",
            )
        }.build()
    }

    init {
        clearOldPrefs()
    }

    protected open fun headersReferrerBuilder(url: String = baseUrl): Headers.Builder = headers.newBuilder()
        .set("Referer", "$url/")

    protected open var docHeaders: Headers = headersReferrerBuilder().build()

    protected open var rapidShareExtractor: RapidShareExtractor =
        RapidShareExtractor(client, docHeaders, context)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?sort=trending&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browser?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/browser".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .also { builder ->
                YFlixThemeFilters.getFilters(filters).forEach {
                    it.addQueryParameters(builder)
                }
            }.build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    protected open val moviesSelector = "div.film-section div.item"

    protected open fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(moviesSelector).mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val title = item.selectFirst("a.title")?.text() ?: return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(poster.attr("href"))
                this.title = title
                thumbnail_url = item.selectFirst("img")?.attr("data-src")
            }
        }

        val hasNextPage = document.selectFirst("li.page-item a[rel=next]") != null

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    protected open fun Document.isMovie(): Boolean = selectFirst(".metadata > span:contains(Movie)") != null

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val document = response.asJsoup()

        title = document.selectFirst("h1.title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("div.poster img")?.attr("src")
        val isMovie = document.isMovie()
        status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING

        genre = document.select("ul.mics li:has(a[href*=/genre/]) a").eachText().joinToString()
        author = document.select("ul.mics li:has(a[href*=/production/]) a").eachText().joinToString()

        // fancy score
        val scorePosition = preferences.scorePosition
        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> document.getFancyScore()
            else -> ""
        }

        description = buildString {
            if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                append(fancyScore)
                append("\n\n")
            }

            document.selectFirst(".description")?.text()?.also { append("$it\n\n") }

            val type = if (isMovie) "Movie" else "TV Show"
            append("**Type:** $type\n")

            fun getInfo(label: String): String? = document.selectFirst("ul.mics li:contains($label:)")
                ?.text()?.substringAfter(":")?.trim()

            getInfo("Country")?.let { append("**Country:** $it\n") }
            getInfo("Released")?.let { append("**Released:** $it\n") }
            getInfo("Casts")?.let { append("**Casts:** $it\n") }

            document.selectFirst(".metadata .IMDb")?.text()?.let {
                val rating = it.substringAfter("IMDb").trim()
                if (rating.isNotEmpty()) append("**IMDb:** $rating")
            }

            document.getBackdropUrl()?.let { coverUrl ->
                if (coverUrl.isNotBlank()) {
                    append("\n\n![Cover]($coverUrl)")
                }
            }

            if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(fancyScore)
            }
        }
    }

    protected open val backgroundUrlRegex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }
    protected open fun Document.getBackdropUrl(): String? = selectFirst("div.detail-bg")
        ?.attr("style")
        ?.let { backgroundUrlRegex.find(it)?.groupValues?.getOrNull(1) }

    protected open fun Document.getScore(): String? = selectFirst("div.rating")?.attr("data-score")

    protected open fun Document.getFancyScore(): String {
        val score = getScore()
        if (score.isNullOrBlank()) return ""

        return try {
            val scoreBig = BigDecimal(score.trim())
            if (scoreBig.compareTo(BigDecimal.ZERO) == 0) return ""

            val stars = scoreBig.divide(BigDecimal(2))
                .setScale(0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(0, 5)

            val scoreString = scoreBig.stripTrailingZeros().toPlainString()

            buildString {
                append("\u2605".repeat(stars))
                if (stars < 5) append("\u2606".repeat(5 - stars))
                append(" $scoreString")
            }
        } catch (_: NumberFormatException) {
            ""
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = YFlixThemeFilters.FILTER_LIST

    // ============================== Episodes ==============================

    protected open fun Document.contentIdSelect(): String? = selectFirst("div.rating[data-id]")?.attr("data-id")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = baseUrl + anime.url
        val document = client.newCall(GET(animeUrl, docHeaders)).awaitSuccess().use { it.asJsoup() }
        val contentId = document.contentIdSelect()
            ?: throw Exception("Unable to find content id.")

        val encryptedId = encrypt(contentId)
        val ajaxUrl = "$baseUrl/ajax/episodes/list".toHttpUrl().newBuilder()
            .addQueryParameter("id", contentId)
            .addQueryParameter("_", encryptedId)
            .build()

        val resultDoc = client.newCall(GET(ajaxUrl.toString(), ajaxHeaders(animeUrl)))
            .awaitSuccess().use {
                it.parseAs<ResultResponse>().toDocument()
            }

        return resultDoc.select("ul.episodes[data-season]").flatMap { seasonElement ->
            val seasonNum = seasonElement.attr("data-season")

            seasonElement.select("li a").map { element ->
                if (element.selectFirst("span.num") != null) {
                    tvEpisodeFromElement(element, anime.url, seasonNum)
                } else {
                    movieEpisodeFromElement(element, anime.url)
                }
            }
        }.reversed()
            .ifEmpty {
                throw Exception("No episodes/movie found.")
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used.")

    protected open fun tvEpisodeFromElement(element: Element, animeUrl: String, seasonNum: String): SEpisode = SEpisode.create().apply {
        val epNum = element.attr("num")
        url = "$animeUrl#${element.attr("eid")}"
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "S$seasonNum E$epNum: ${element.selectFirst("span:not(.num)")?.text()?.trim()}"
        date_upload = parseDate(element.attr("title"))
    }

    protected open fun movieEpisodeFromElement(element: Element, animeUrl: String): SEpisode = SEpisode.create().apply {
        url = "$animeUrl#${element.attr("eid")}"
        episode_number = 1F
        name = element.selectFirst("span")?.text()?.trim() ?: "Movie"
    }

    // ============================ Video Links =============================

    protected open val serversSelector = "li.server"

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlParts = episode.url.split('#', limit = 2)
        val animeUrl = urlParts.firstOrNull() ?: return emptyList()
        val episodeId = urlParts.getOrNull(1) ?: return emptyList()
        val referer = baseUrl + animeUrl

        val encryptedId = encrypt(episodeId)
        val serversUrl = "$baseUrl/ajax/links/list".toHttpUrl().newBuilder()
            .addQueryParameter("eid", episodeId)
            .addQueryParameter("_", encryptedId)
            .build()

        val serversDoc = client.newCall(GET(serversUrl.toString(), ajaxHeaders(referer)))
            .awaitSuccess().use {
                it.parseAs<ResultResponse>().toDocument()
            }

        return serversDoc.select(serversSelector).parallelCatchingFlatMap { serverElement ->
            val serverName = serverElement.selectFirst("span")?.text()
                ?: return@parallelCatchingFlatMap emptyList()

            if (serverName !in preferences.hosterPref) return@parallelCatchingFlatMap emptyList()

            val serverId = serverElement.attr("data-lid")
            val encryptedServerId = encrypt(serverId)
            val viewUrl = "$baseUrl/ajax/links/view".toHttpUrl().newBuilder()
                .addQueryParameter("id", serverId)
                .addQueryParameter("_", encryptedServerId)
                .build()

            val encryptedIframeResult = client.newCall(GET(viewUrl.toString(), ajaxHeaders(referer)))
                .awaitSuccess().use {
                    it.parseAs<ResultResponse>().result
                }

            val iframeUrl = decrypt(encryptedIframeResult)
            rapidShareExtractor.videosFromUrl(iframeUrl, serverName, preferences.subLangPref)
        }
    }

    // ============================= Utilities ==============================

    protected open fun ajaxHeaders(referer: String) = docHeaders.newBuilder()
        .set("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    protected open suspend fun <T> fetchEncDec(url: String, parse: Response.() -> T): T {
        var lastError: Exception? = null

        repeat(ENC_DEC_MAX_ATTEMPTS) {
            try {
                return client.newCall(GET(url, encdecHeaders)).awaitSuccess().use { it.parse() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("enc-dec request failed.")
    }

    protected open suspend fun encrypt(text: String): String = fetchEncDec(
        "https://enc-dec.app/api/enc-movies-flix".toHttpUrl().newBuilder()
            .addQueryParameter("text", text)
            .build()
            .toString(),
    ) {
        parseAs<ResultResponse>()
    }.result

    protected open suspend fun decrypt(text: String): String = fetchEncDec(
        "https://enc-dec.app/api/dec-movies-flix".toHttpUrl().newBuilder()
            .addQueryParameter("text", text)
            .build()
            .toString(),
    ) {
        parseAs<DecryptedIframeResponse>()
    }.result.url

    protected open fun parseDate(dateStr: String): Long = runCatching {
        synchronized(DATE_FORMATTER) {
            DATE_FORMATTER.parse(dateStr)?.time
        }
    }.getOrNull() ?: 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.qualityPref
        val server = preferences.serverPref
        val qualities = QUALITIES.reversed()

        return sortedWith(
            compareByDescending<Video> {
                it.quality.contains(quality, true) && it.quality.startsWith(server, true)
            }
                .thenByDescending { it.quality.contains(quality, true) }
                .thenByDescending { it.quality.startsWith(server, true) }
                .thenByDescending { video -> qualities.indexOfFirst { video.quality.contains(it) } },
        )
    }

    // ============================== Preferences ==============================

    private val SharedPreferences.qualityPref: String
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val SharedPreferences.subLangPref: String
        get() = getString(PREF_SUB_LANG_KEY, PREF_SUB_LANG_DEFAULT) ?: PREF_SUB_LANG_DEFAULT

    private val SharedPreferences.serverPref: String
        get() = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val SharedPreferences.hosterPref: Set<String>
        get() = getStringSet(PREF_HOSTER_KEY, SERVERS.toSet()) ?: SERVERS.toSet()

    private val SharedPreferences.scorePosition: String
        get() = getString(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT) ?: PREF_SCORE_POSITION_DEFAULT

    protected open fun clearOldPrefs() {
        val domain = preferences.getString(PREF_DOMAIN_KEY, defaultDomain) ?: return
        val domainHost = domain.toHttpUrlOrNull()?.host ?: domain
        if (domainHost !in domainList) {
            preferences.edit()
                .putString(PREF_DOMAIN_KEY, defaultDomain)
                .apply()
        }
        val hostToggle = preferences.getStringSet(PREF_HOSTER_KEY, SERVERS.toSet()) ?: return
        if (hostToggle.any { it !in SERVERS }) {
            preferences.edit()
                .putStringSet(PREF_HOSTER_KEY, SERVERS.toSet())
                .putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
                .apply()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain"
            entries = domainList.toTypedArray()
            entryValues = domainList.map { "https://$it" }.toTypedArray()
            setDefaultValue(defaultDomain)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).apply()
                docHeaders = headersReferrerBuilder(selected).build()
                rapidShareExtractor = RapidShareExtractor(client, docHeaders, context)
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITIES.toTypedArray()
            entryValues = QUALITIES.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_LANG_KEY
            title = "Preferred sub language"
            entries = SUB_LANGS.toTypedArray()
            entryValues = SUB_LANGS.toTypedArray()
            setDefaultValue(PREF_SUB_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVERS.toTypedArray()
            entryValues = SERVERS.toTypedArray()
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION_KEY
            title = "Score display position"
            entries = PREF_SCORE_POSITION_ENTRIES.toTypedArray()
            entryValues = PREF_SCORE_POSITION_VALUES.toTypedArray()
            setDefaultValue(PREF_SCORE_POSITION_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/disable servers"
            entries = SERVERS.toTypedArray()
            entryValues = SERVERS.toTypedArray()
            setDefaultValue(SERVERS.toSet())
            summary = "Select which video server to show in the episode list"
        }.also(screen::addPreference)
    }

    companion object {
        protected const val PREF_DOMAIN_KEY = "pref_domain_key"

        const val PREF_QUALITY_KEY = "pref_quality_key"
        protected val QUALITIES = listOf("1080p", "720p", "480p", "360p")
        protected val PREF_QUALITY_DEFAULT = QUALITIES.first()

        const val PREF_SUB_LANG_KEY = "pref_sub_lang_key"
        protected val SUB_LANGS = listOf(
            "English",
            "Arabic",
            "Chinese",
            "French",
            "German",
            "Indonesian",
            "Italian",
            "Japanese",
            "Korean",
            "Persian",
            "Portuguese",
            "Russian",
            "Spanish",
            "Turkish",
            "Urdu",
            "Vietnamese",
        )
        internal val PREF_SUB_LANG_DEFAULT = SUB_LANGS.first()

        const val PREF_SERVER_KEY = "pref_server_key"
        protected val SERVERS = listOf("Server 1", "Server 2")
        protected val PREF_SERVER_DEFAULT = SERVERS.first()

        const val PREF_HOSTER_KEY = "pref_hoster_key"

        protected const val PREF_SCORE_POSITION_KEY = "score_position"
        protected const val SCORE_POS_TOP = "top"
        protected const val SCORE_POS_BOTTOM = "bottom"
        protected const val SCORE_POS_NONE = "none"
        protected const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        protected const val ENC_DEC_MAX_ATTEMPTS = 2
        protected val PREF_SCORE_POSITION_ENTRIES = listOf(
            "Top of description",
            "Bottom of description",
            "Don't show",
        )
        protected val PREF_SCORE_POSITION_VALUES = listOf(
            SCORE_POS_TOP,
            SCORE_POS_BOTTOM,
            SCORE_POS_NONE,
        )

        protected val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
