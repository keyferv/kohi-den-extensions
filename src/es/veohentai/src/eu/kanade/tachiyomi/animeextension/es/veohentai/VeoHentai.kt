package eu.kanade.tachiyomi.animeextension.es.veohentai

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class VeoHentai : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "VeoHentai"

    override val baseUrl = "https://veohentai.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VeoHentai"
        private val SERVER_LIST = arrayOf("VeoHentai")

        private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }

    private val videoUrlResolver by lazy { VideoUrlResolver() }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".pb-2 h1")?.text()?.trim() ?: ""
            status = SAnime.UNKNOWN
            description = document.select(".entry-content p").joinToString { it.text() }
            genre = document.select(".tags a").joinToString { it.text() }
            thumbnail_url = document.selectFirst("#thumbnail-post img")?.getImageUrl()
            document.select(".gap-4 div").map { it.text() }.map { textContent ->
                when {
                    "Marca" in textContent -> author = textContent.substringAfter("Marca").trim()
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/mas-visitados/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".gap-6 a")
        val nextPage = document.select(".nav-links a:contains(Next)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst("h2")?.text()?.trim() ?: ""
                thumbnail_url = element.selectFirst("img:not([class*=cover])")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

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
        return listOf(
            SEpisode.create().apply {
                episode_number = 1f
                name = "Capítulo"
                setUrlWithoutDomain(document.location())
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val frame = document.selectFirst("iframe[webkitallowfullscreen]")
        val src = frame?.attr("abs:src")?.takeIf { !it.startsWith("about") }
        val dataLitespeedSrc = frame?.attr("data-litespeed-src")?.takeIf { !it.startsWith("about") }
        val playerUrl = listOfNotNull(dataLitespeedSrc, src)
            .distinct()
            .sortedByDescending { it.contains("hentaiplayer.com", ignoreCase = true) }
            .firstNotNullOfOrNull { link ->
                runCatching {
                    val docPlayer = client.newCall(GET(link)).execute().asJsoup()
                    val dataId = docPlayer.selectFirst("[data-id]")?.attr("data-id") ?: return@runCatching null
                    val host = docPlayer.location().toHttpUrl().host
                    "https://$host$dataId"
                }.getOrNull()
            } ?: return emptyList()

        val result = videoUrlResolver.getVideoUrl(playerUrl, headers)
        if (result.url.isBlank()) return emptyList()

        // The WebView resolver can obtain the signed R2 URL, but Cloudflare may still
        // reject the native MPV/ffmpeg playback request because it does not share the
        // same browser/TLS fingerprint as WebView. Keep browser-like headers here as
        // the best extension-level attempt; a complete fix likely requires app-level
        // WebView playback, a browser-like proxy, or a different player network stack.
        if (result.cookies.isNotEmpty()) {
            result.cookies.forEach { cookie ->
                client.cookieJar.saveFromResponse(
                    url = HttpUrl.Builder()
                        .scheme("http")
                        .host(cookie.domain)
                        .build(),
                    cookies = listOf(cookie),
                )
            }
        }

        val videoHeaders = Headers.Builder().apply {
            add("Referer", result.referer)
            add("User-Agent", headers["User-Agent"] ?: DEFAULT_UA)
            add("Accept", "*/*")
            add("Accept-Language", "en,de;q=0.9")
            add("Sec-Fetch-Site", "cross-site")
            add("Sec-Fetch-Mode", "no-cors")
            add("Sec-Fetch-Dest", "video")
            add("Sec-Fetch-Storage-Access", "active")
            if (result.cookies.isNotEmpty()) {
                add("Cookie", result.cookies.joinToString("; ") { "${it.name}=${it.value}" })
            }
        }.build()

        return listOf(
            Video(result.url, "VeoHentai:MP4", result.url, headers = videoHeaders, subtitleTracks = emptyList()),
        )
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
            Pair("3D", "genero/3d"),
            Pair("Ahegao", "genero/ahegao"),
            Pair("Anal", "genero/anal"),
            Pair("Bondage", "genero/bondage"),
            Pair("Casadas", "genero/casadas"),
            Pair("Censurado", "genero/censurado"),
            Pair("Chikan", "genero/chikan"),
            Pair("Corridas", "genero/corridas"),
            Pair("Ecchi", "genero/ecchi"),
            Pair("Enfermeras", "genero/enfermeras"),
            Pair("Escolares", "genero/hentai-escolares"),
            Pair("Fantasia", "genero/fantasia"),
            Pair("Futanari", "genero/futanari"),
            Pair("Gore", "genero/gore"),
            Pair("Hardcore", "genero/hardcore"),
            Pair("Harem", "genero/harem"),
            Pair("Incesto", "genero/incesto"),
            Pair("Josei", "genero/josei"),
            Pair("Juegos Sexuales", "genero/juegos-sexuales"),
            Pair("Lesbiana", "genero/lesbiana"),
            Pair("Lolicon", "genero/lolicon"),
            Pair("Maids", "genero/maids"),
            Pair("Manga", "genero/manga"),
            Pair("Masturbación", "genero/masturbacion"),
            Pair("Milfs", "genero/milfs"),
            Pair("Netorare", "genero/netorare"),
            Pair("Ninfomania", "genero/ninfomania"),
            Pair("Ninjas", "genero/ninjas"),
            Pair("Orgias", "genero/orgias"),
            Pair("Romance", "genero/romance"),
            Pair("Sexo oral", "genero/sexo-oral"),
            Pair("Shota", "genero/shota"),
            Pair("Sin Censura", "genero/hentai-sin-censura"),
            Pair("Softcore", "genero/softcore"),
            Pair("Succubus", "genero/succubus"),
            Pair("Teacher", "genero/teacher"),
            Pair("Tentaculos", "genero/tentaculos"),
            Pair("Tetonas", "genero/tetonas"),
            Pair("Vanilla", "genero/vanilla"),
            Pair("Violacion", "genero/violacion"),
            Pair("Virgenes", "genero/virgenes"),
            Pair("Yaoi", "genero/yaoi"),
            Pair("Yuri", "genero/yuri"),
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

    private fun String.getItems(): List<String> {
        val pattern = "\\{([^}]*)\\}".toRegex()
        return pattern.findAll(this).map { it.groupValues[1] }.toList()
    }

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
