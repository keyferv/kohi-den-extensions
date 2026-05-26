package eu.kanade.tachiyomi.animeextension.es.cine24h

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

open class Cine24h : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Cine24h"

    override val baseUrl = "https://cine24h.online"

    override val lang = "es"

    override val supportsLatest = true

    // Site sits behind Cloudflare; use the client that can solve JS challenges.
    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf("Voe", "Fastream", "Filemoon", "Doodstream")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            description = document.selectFirst(".Single .Description")?.text()
            genre = document.select(".Single .InfoList a").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".Single .Image img")?.getImageUrl()?.replace("/w185/", "/w500/")
            if (document.location().contains("/peliculas/")) {
                status = SAnime.COMPLETED
            } else {
                val statusText = document.selectFirst(".SubTitle .Qlty")?.text().orEmpty()
                status = when {
                    statusText.contains("Returning", true) -> SAnime.ONGOING
                    statusText.contains("Canceled", true) || statusText.contains("Ended", true) -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int): Request {
        // "Most viewed" section was removed by the site; use "Estrenos" (releases) instead.
        return if (page > 1) GET("$baseUrl/estrenos/page/$page/", headers) else GET("$baseUrl/estrenos/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("li.TPostMv").map { element ->
            val link = element.selectFirst("article.TPost > a")
            val titleItem = element.selectFirst(".TPMvCn .Title")?.text()?.trim()
                ?: element.selectFirst("article.TPost > a h2")?.text()?.trim().orEmpty()
            val langs = element.select(".language-box .lang-item span").map { it.text().trim().uppercase() }

            val prefix = buildString {
                if (langs.any { it.contains("LAT") }) append("\uD83C\uDDF2\uD83C\uDDFD ")
                if (langs.any { it.contains("ESP") }) append("\uD83C\uDDEA\uD83C\uDDF8 ")
                if (langs.any { it.contains("SUB") }) append("\uD83C\uDDFA\uD83C\uDDF8 ")
            }
            SAnime.create().apply {
                title = prefix + titleItem
                thumbnail_url = element.selectFirst(".Image img")?.getImageUrl()?.replace("/w185/", "/w300/")
                setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
            }
        }
        val nextPage = document.select(".wp-pagenavi a.nextpostslink, .wp-pagenavi .next").any()
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return GET("$baseUrl/page/$page/?s=trfilter&trfilter=1&years%5B0%5D=$currentYear#038;trfilter=1&years%5B0%5D=$currentYear", headers)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.location().contains("/peliculas/")) {
            listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "PELÍCULA"
                    scanlator = document.select(".AAIco-date_range").text().trim()
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            var episodeCounter = 1F
            document.select(".AABox").flatMap { season ->
                val seasonText = season.selectFirst(".Title")?.text().orEmpty()
                val noSeason = Regex("(?:Season|Temporada)\\s*(\\d+)").find(seasonText)?.groupValues?.get(1)
                    ?: Regex("\\d+").findAll(seasonText).lastOrNull()?.value
                    ?: "1"
                season.select(".TPTblCn tbody tr").map { ep ->
                    SEpisode.create().apply {
                        episode_number = episodeCounter++
                        name = "T$noSeason - E${ep.select(".Num").text().trim()} - ${ep.select(".MvTbTtl a").text().trim()}"
                        scanlator = ep.select(".MvTbTtl span").text()
                        setUrlWithoutDomain(ep.select(".MvTbTtl a").attr("abs:href"))
                    }
                }
            }.reversed()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        // Each player option lives in .optns-bx with a base64-encoded trembed URL.
        // The inline .TPlayerTb iframe is only the default option, so iterate them all.
        val options = document.select(".optns-bx .optnslst li[data-src]")
            .mapNotNull { li ->
                val embedUrl = runCatching {
                    String(Base64.decode(li.attr("data-src"), Base64.DEFAULT))
                }.getOrNull()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                embedUrl to li.selectFirst("button span:not(.nmopt)")?.ownText()?.trim().orEmpty()
            }
            .ifEmpty {
                document.select(".TPlayerTb iframe[src]").map { it.attr("abs:src") to "" }
            }
            .distinctBy { it.first }

        return options.parallelCatchingFlatMapBlocking { (embedUrl, lang) ->
            val link = client.newCall(GET(embedUrl, headers)).execute().asJsoup()
                .selectFirst("iframe")?.attr("abs:src") ?: ""
            val videos = serverVideoResolver(link)
            if (lang.isBlank()) {
                videos
            } else {
                videos.map { Video(it.url, "[$lang] ${it.quality}", it.videoUrl, it.headers) }
            }
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("fastream") -> {
                val link = if (url.contains("emb.html")) "https://fastream.to/embed-${url.split("/").last()}.html" else url
                FastreamExtractor(client, headers).videosFromUrl(link)
            }
            arrayOf("filemoon", "moonplayer").any(url) -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            arrayOf("voe").any(url) -> voeExtractor.videosFromUrl(url)
            arrayOf("doodstream", "dood.", "ds2play", "doods.").any(url) -> doodExtractor.videosFromUrl(url)
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url)
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Películas", "peliculas"),
            Pair("Series", "series"),
            Pair("Acción", "category/accion"),
            Pair("Animación", "category/animacion"),
            Pair("Anime", "category/anime"),
            Pair("Aventura", "category/aventura"),
            Pair("Bélica", "category/belica"),
            Pair("Ciencia ficción", "category/ciencia-ficcion"),
            Pair("Comedia", "category/comedia"),
            Pair("Crimen", "category/crimen"),
            Pair("Documental", "category/documental"),
            Pair("Drama", "category/drama"),
            Pair("Familia", "category/familia"),
            Pair("Fantasía", "category/fantasia"),
            Pair("Gerra", "category/gerra"),
            Pair("Historia", "category/historia"),
            Pair("Misterio", "category/misterio"),
            Pair("Música", "category/musica"),
            Pair("Navidad", "category/navidad"),
            Pair("Película de TV", "category/pelicula-de-tv"),
            Pair("Romance", "category/romance"),
            Pair("Suspenso", "category/suspense"),
            Pair("Terror", "category/terror"),
            Pair("Western", "category/western"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    protected open fun org.jsoup.nodes.Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> ""
        }
    }

    protected open fun org.jsoup.nodes.Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("data:image/")
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
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
    }
}
