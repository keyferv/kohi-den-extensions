
package eu.kanade.tachiyomi.animeextension.es.homecine

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class HomeCine : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "HomeCine"

    override val baseUrl = "https://www3.homecine.to"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "YourUpload"
        private val SERVER_LIST = arrayOf(
            "YourUpload",
            "BurstCloud",
            "Voe",
            "StreamWish",
            "Mp4Upload",
            "Fastream",
            "Upstream",
            "Filemoon",
        )
    }

    // Cached URL of the latest "release-year" listing (e.g. /release-year/2026).
    // Resolved from the site menu so it keeps working when the year rolls over.
    private var cachedRecientesUrl: String? = null

    private fun recientesUrl(): String {
        cachedRecientesUrl?.let { return it }
        val resolved = runCatching {
            val document = client.newCall(GET("$baseUrl/cartelera", headers)).execute().asJsoup()
            document.select("li.menu-item-object-release-year a[href*=/release-year/]")
                .ifEmpty { document.select("a[href*=/release-year/]") }
                .mapNotNull { it.attr("abs:href").trimEnd('/').takeIf(String::isNotBlank) }
                .maxByOrNull { it.substringAfterLast("/").toIntOrNull() ?: 0 }
        }.getOrNull()
        val url = resolved ?: "$baseUrl/release-year/${Calendar.getInstance().get(Calendar.YEAR)}"
        cachedRecientesUrl = url
        return url
    }

    override fun popularAnimeRequest(page: Int): Request {
        val url = recientesUrl()
        return if (page > 1) GET("$url/page/$page", headers) else GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.movies-list .ml-item").map { element ->
            SAnime.create().apply {
                val link = element.selectFirst("a.ml-mask")
                setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
                title = link?.attr("oldtitle").takeUnless { it.isNullOrBlank() }
                    ?: element.selectFirst(".mli-info h2")?.text()
                    ?: element.selectFirst("img.mli-thumb")?.attr("alt").orEmpty()
                thumbnail_url = element.selectFirst("img.mli-thumb")?.let { getImageUrl(it) }
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li.active ~ li a.page.larger") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/?s=$query", headers)

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val isSeries = response.request.url.toString().contains("/series/")
        return SAnime.create().apply {
            title = document.selectFirst(".mvic-desc [itemprop=name]")?.text().orEmpty()
            description = document.selectFirst(".mvic-desc .desc")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".mvic-thumb img")?.let { getImageUrl(it)?.replace("/w185/", "/w500/") }
            genre = document.select(".mvici-left a[href*=/genre/]").joinToString { it.text() }
            status = when {
                !isSeries -> SAnime.COMPLETED
                else -> when {
                    document.select(".mvici-right p:contains(TV Status) span").text().contains("Returning", true) -> SAnime.ONGOING
                    document.select(".mvici-right p:contains(TV Status) span").text().let { it.contains("Canceled", true) || it.contains("Ended", true) } -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }
        }
    }

    private fun getImageUrl(element: Element): String? {
        return when {
            element.hasAttr("data-original") -> element.attr("abs:data-original")
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("src") -> element.attr("abs:src")
            else -> null
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val seasons = document.select("#seasons .tvseason")

        // Movies have no season block -> single playable entry
        if (seasons.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "Película"
                    setUrlWithoutDomain(url)
                },
            )
        }

        // Series list every episode inline under #seasons (no AJAX needed)
        return seasons.flatMap { season ->
            val seasonNum = Regex("\\d+").find(season.selectFirst(".les-title strong")?.text().orEmpty())
                ?.value?.toIntOrNull() ?: 1
            season.select(".les-content a").map { ep ->
                val epNum = Regex("\\d+").find(ep.text().trim())?.value?.toIntOrNull() ?: 1
                SEpisode.create().apply {
                    setUrlWithoutDomain(ep.attr("abs:href"))
                    name = "T$seasonNum - Episodio $epNum"
                    episode_number = (seasonNum * 1000 + epNum).toFloat()
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(".player_nav .idTabs a[href^=#]").forEach {
            val prefix = runCatching {
                val label = it.text().lowercase()
                when {
                    label.contains("latino") -> "[LAT]"
                    label.contains("castellano") -> "[CAST]"
                    label.contains("sub") || label.contains("vose") -> "[SUB]"
                    else -> ""
                }
            }.getOrDefault("")

            val ide = it.attr("href")
            var src = (document.selectFirst("$ide iframe")?.attr("abs:src") ?: "").replace("#038;", "&").replace("&amp;", "")
            try {
                if (src.contains("home")) {
                    src = client.newCall(GET(src)).execute().asJsoup().selectFirst("iframe")?.attr("src") ?: ""
                }

                if (src.contains("fastream")) {
                    if (src.contains("emb.html")) {
                        val key = src.split("/").last()
                        src = "https://fastream.to/embed-$key.html"
                    }
                    FastreamExtractor(client, headers).videosFromUrl(src, needsSleep = false, prefix = "$prefix Fastream:").also(videoList::addAll)
                }
                if (src.contains("upstream")) {
                    UpstreamExtractor(client).videosFromUrl(src, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("yourupload")) {
                    YourUploadExtractor(client).videoFromUrl(src, headers, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("voe")) {
                    VoeExtractor(client, headers).videosFromUrl(src, prefix = "$prefix ").also(videoList::addAll)
                }
                if (src.contains("wishembed") || src.contains("streamwish") || src.contains("wish")) {
                    StreamWishExtractor(client, headers).videosFromUrl(src) { "$prefix StreamWish:$it" }.also(videoList::addAll)
                }
                if (src.contains("mp4upload")) {
                    Mp4uploadExtractor(client).videosFromUrl(src, headers, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("burst")) {
                    BurstCloudExtractor(client).videoFromUrl(src, headers = headers, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("filemoon") || src.contains("moonplayer")) {
                    FilemoonExtractor(client).videosFromUrl(src, headers = headers, prefix = "$prefix Filemoon:").let { videoList.addAll(it) }
                }
            } catch (_: Exception) {}
        }
        return videoList
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
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
    }
}
