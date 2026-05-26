package eu.kanade.tachiyomi.animeextension.es.pandrama

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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Pandrama : ConfigurableAnimeSource, AnimeHttpSource() {

    override val id: Long = 8290662435507939982

    override val name = "Pandrama"

    override val baseUrl = "https://www.pandrama.tv"

    private val apiUrl get() = "$baseUrl/api/v1"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // The API replies 401 unless a Referer pointing to the site is sent.
    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        // Channels exposed by the site that map to titles / latest episodes.
        private const val POPULAR_CHANNEL = "top-10-mensual"
        private const val LATEST_CHANNEL = "ultimos-episodios"

        private val EPISODE_URL_REGEX = Regex("""/titulo/(\d+)/temporada/(\d+)/episodio/(\d+)""")
    }

    /*-------------------------------- Popular / Latest -------------------------------*/

    private fun channelUrl(slug: String, page: Int) =
        GET("$apiUrl/channel/$slug?channelType=channel&restriction=&loader=channelPage&page=$page", headers)

    override fun popularAnimeRequest(page: Int) = channelUrl(POPULAR_CHANNEL, page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val content = json.decodeFromString<ChannelResponse>(response.body.string()).channel.content
        val animeList = content.data.map { it.toSAnime() }.distinctBy { it.url }
        return AnimesPage(animeList, content.nextPage != null)
    }

    override fun latestUpdatesRequest(page: Int) = channelUrl(LATEST_CHANNEL, page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    /*-------------------------------- Search -------------------------------*/

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/search/$encoded?loader=searchPage", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val results = json.decodeFromString<SearchResponse>(response.body.string()).results
        return AnimesPage(results.map { it.toSAnime() }, false)
    }

    /*-------------------------------- Details -------------------------------*/

    override fun animeDetailsRequest(anime: SAnime) =
        GET("$apiUrl/titles/${anime.titleId()}?loader=titlePage", headers)

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl${anime.url}"

    override fun animeDetailsParse(response: Response): SAnime {
        val title = json.decodeFromString<TitlePageResponse>(response.body.string()).title
        return SAnime.create().apply {
            this.title = title.name.orEmpty()
            thumbnail_url = title.poster ?: title.backdrop
            description = title.description
            genre = title.genres.mapNotNull { it.displayName ?: it.name }.joinToString()
            status = when (title.status?.lowercase()) {
                "ongoing" -> SAnime.ONGOING
                "ended", "completed", "canceled" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    /*-------------------------------- Episodes -------------------------------*/

    override fun episodeListRequest(anime: SAnime) =
        GET("$apiUrl/titles/${anime.titleId()}?loader=titlePage", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = json.decodeFromString<TitlePageResponse>(response.body.string())
        val titleId = data.title.id

        if (!data.title.isSeries) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "Película"
                    setUrlWithoutDomain("/titulo/$titleId/temporada/1/episodio/1")
                },
            )
        }

        val seasons = data.seasons.data.ifEmpty { listOf(SeasonDto(number = 1)) }
        return seasons.flatMap { season ->
            val epsResponse = client.newCall(
                GET(
                    "$apiUrl/titles/$titleId/seasons/${season.number}/episodes" +
                        "?perPage=500&excludeDescription=true&orderBy=episode_number&orderDir=asc&page=1",
                    headers,
                ),
            ).execute().body.string()

            json.decodeFromString<EpisodesResponse>(epsResponse).pagination.data.map { ep ->
                SEpisode.create().apply {
                    setUrlWithoutDomain("/titulo/$titleId/temporada/${ep.seasonNumber}/episodio/${ep.episodeNumber}")
                    name = "T${ep.seasonNumber} E${ep.episodeNumber}" + ep.name.orEmpty().let { if (it.isBlank()) "" else " - $it" }
                    episode_number = (ep.seasonNumber * 1000 + ep.episodeNumber).toFloat()
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    /*-------------------------------- Video -------------------------------*/

    override fun videoListRequest(episode: SEpisode): Request {
        val (titleId, season, number) = EPISODE_URL_REGEX.find(episode.url)!!.destructured
        return GET("$apiUrl/titles/$titleId/seasons/$season/episodes/$number?loader=episodePage", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val videos = json.decodeFromString<EpisodePageResponse>(response.body.string()).episode.videos
        return videos.parallelCatchingFlatMapBlocking { video ->
            val src = video.src?.takeIf { it.startsWith("http") } ?: return@parallelCatchingFlatMapBlocking emptyList()
            val langTag = when (video.language?.lowercase()) {
                "es-419", "es", "es-mx", "lat" -> "[LAT]"
                else -> "[SUB]"
            }
            val serverName = video.server?.displayName?.replace(Regex("[^A-Za-z0-9 ]"), "")?.trim().orEmpty()
            serverVideoResolver(src).map { resolved ->
                Video(resolved.url, "$langTag $serverName ${resolved.quality}".trim(), resolved.videoUrl, resolved.headers)
            }
        }
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            "ok.ru" in embedUrl || "okru" in embedUrl -> okruExtractor.videosFromUrl(url)
            "vk." in embedUrl -> vkExtractor.videosFromUrl(url)
            listOf("lulustream", "streamwish", "wish", "luluvdo", "filelions").any { it in embedUrl } ->
                streamWishExtractor.videosFromUrl(url)
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(language) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    /*-------------------------------- Helpers / DTOs -------------------------------*/

    private fun SAnime.titleId() = url.substringAfter("/titulo/").substringBefore("/")

    private fun ItemDto.toSAnime(): SAnime {
        val entry = title ?: this
        return SAnime.create().apply {
            title = entry.name.orEmpty()
            thumbnail_url = entry.poster
            setUrlWithoutDomain("/titulo/${entry.id}")
        }
    }

    @Serializable
    private class ChannelResponse(val channel: ChannelDto = ChannelDto())

    @Serializable
    private class ChannelDto(val content: ContentDto = ContentDto())

    @Serializable
    private class ContentDto(
        @SerialName("next_page") val nextPage: Int? = null,
        val data: List<ItemDto> = emptyList(),
    )

    @Serializable
    private class SearchResponse(val results: List<ItemDto> = emptyList())

    @Serializable
    private class ItemDto(
        val id: Int = 0,
        val name: String? = null,
        val poster: String? = null,
        val title: ItemDto? = null,
    )

    @Serializable
    private class TitlePageResponse(
        val title: TitleDto = TitleDto(),
        val seasons: SeasonsDto = SeasonsDto(),
    )

    @Serializable
    private class TitleDto(
        val id: Int = 0,
        val name: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val description: String? = null,
        @SerialName("is_series") val isSeries: Boolean = true,
        val status: String? = null,
        val genres: List<GenreDto> = emptyList(),
    )

    @Serializable
    private class GenreDto(
        @SerialName("display_name") val displayName: String? = null,
        val name: String? = null,
    )

    @Serializable
    private class SeasonsDto(val data: List<SeasonDto> = emptyList())

    @Serializable
    private class SeasonDto(val number: Int = 1)

    @Serializable
    private class EpisodesResponse(val pagination: EpisodesPagination = EpisodesPagination())

    @Serializable
    private class EpisodesPagination(val data: List<EpisodeDto> = emptyList())

    @Serializable
    private class EpisodeDto(
        val name: String? = null,
        @SerialName("episode_number") val episodeNumber: Int = 0,
        @SerialName("season_number") val seasonNumber: Int = 1,
    )

    @Serializable
    private class EpisodePageResponse(val episode: EpisodeVideosDto = EpisodeVideosDto())

    @Serializable
    private class EpisodeVideosDto(val videos: List<VideoDto> = emptyList())

    @Serializable
    private class VideoDto(
        val src: String? = null,
        val language: String? = null,
        val server: ServerDto? = null,
    )

    @Serializable
    private class ServerDto(@SerialName("display_name") val displayName: String? = null)

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
    }
}
