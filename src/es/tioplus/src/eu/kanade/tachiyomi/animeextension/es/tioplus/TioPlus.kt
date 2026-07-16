package eu.kanade.tachiyomi.animeextension.es.tioplus

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TioPlus : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "TioPlus"

    override val baseUrl = "https://tioplus.app"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VidHide"
        private val SERVER_LIST = arrayOf(
            "VidHide", "Earnvids", "TioPlus", "UPFAST", "Netu", "Plus", "Filemoon", "StreamWish", "Voe", "Uqload",
            "Okru", "Doodstream", "StreamTape", "YourUpload", "Mp4Upload",
            "MixDrop", "Fastream", "Upstream", "VidGuard",
        )

        private val REGEX_PLAYER_REDIRECT = """window\.location\.href\s*=\s*['"]([^'"]+)['"]""".toRegex()
        private val REGEX_EMTURBO_URL_PLAY = """urlPlay\s*=\s*['"]([^'"]+)""".toRegex()
        private val PELISPLUS_PLAYER_HOSTS = listOf("upns.pro", "rpmstream.live", "strp2p.com", "4meplayer.pro")
        private val PELISPLUS_PLAYER_AES_KEY = "kiemtienmua911ca".toByteArray()
        private val PELISPLUS_PLAYER_AES_IV = "1234567890oiuytr".toByteArray()
    }

    /* ------------------------------------ Popular ------------------------------------ */
    // https://tioplus.app/peliculas  (page 1)  ->  https://tioplus.app/peliculas/2  (page 2+)
    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas/$page", headers)

    override fun popularAnimeSelector(): String = "article.item > a.itemA"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("h2")?.text().orEmpty()
        thumbnail_url = element.selectFirst("img")
            ?.let { img -> img.attr("abs:data-src").ifBlank { img.attr("abs:src") } }
    }

    override fun popularAnimeNextPageSelector(): String = "a.page[rel=next]"

    /* ------------------------------------ Latest ------------------------------------ */
    // https://tioplus.app/series  (page 1)  ->  https://tioplus.app/series/2  (page 2+)
    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/series" else "$baseUrl/series/$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    /* ------------------------------------ Search ------------------------------------ */
    // The site exposes an HTML fragment endpoint: /api/search/<query>
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/api/search/${query.trim()}", headers)
            genreFilter.state != 0 -> GET(
                if (page == 1) {
                    "$baseUrl/${genreFilter.toUriPart()}"
                } else {
                    "$baseUrl/${genreFilter.toUriPart()}/$page"
                },
                headers,
            )
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    /* ---------------------------------- Anime details -------------------------------- */
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.slugh1")?.text().orEmpty()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.bg")?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")
        description = document.selectFirst("div.description p")?.text().orEmpty()
        genre = document.select("div.genres")
            .firstOrNull { it.text().contains("Generos", ignoreCase = true) }
            ?.select("a")
            ?.joinToString { it.text() }
        status = if (document.location().contains("/pelicula/")) SAnime.COMPLETED else SAnime.UNKNOWN
    }

    /* ----------------------------------- Episodes ----------------------------------- */
    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        if (url.contains("/pelicula/")) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "PELÍCULA"
                    setUrlWithoutDomain(url)
                },
            )
        }

        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(const seasonsJson =)")?.data() ?: return emptyList()
        val jsonStr = script.substringAfter("const seasonsJson =").substringBefore("};") + "}"
        val seasons = runCatching {
            json.decodeFromString<Map<String, List<SeasonDto>>>(jsonStr)
        }.getOrNull() ?: return emptyList()

        val serieUrl = url.trimEnd('/')
        val episodes = mutableListOf<SEpisode>()
        seasons.entries
            .sortedBy { (key, _) -> key.toIntOrNull() ?: 0 }
            .forEach { (_, seasonList) ->
                seasonList
                    .sortedBy { it.episode }
                    .forEach { ep ->
                        val season = ep.season
                        val episode = ep.episode
                        val title = ep.title.orEmpty()
                        episodes.add(
                            SEpisode.create().apply {
                                episode_number = (season * 1000 + episode).toFloat()
                                name = "T$season - E$episode - $title"
                                setUrlWithoutDomain("$serieUrl/season/$season/episode/$episode")
                            },
                        )
                    }
            }
        return episodes // ascending order (oldest first)
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    /* ------------------------------------ Videos ------------------------------------ */
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        document.select("div.bg-tabs > div").forEach { tab ->
            val lang = tab.selectFirst("button.button")?.ownText()?.getLang().orEmpty()
            tab.select("ul.subselect li[data-server]").forEach { li ->
                val dataServer = li.attr("data-server")
                val serverName = li.selectFirst("span")?.text().orEmpty()
                val playerUrl = "$baseUrl/player/" +
                    Base64.encodeToString(dataServer.toByteArray(), Base64.NO_WRAP)
                runCatching {
                    val playerDoc = client.newCall(GET(playerUrl, headers)).execute().asJsoup()
                    val redirect = playerDoc.selectFirst("script:containsData(window.location.href =)")
                        ?.data()
                        ?.let { REGEX_PLAYER_REDIRECT.find(it)?.groupValues?.get(1) }
                        .orEmpty()
                    if (redirect.isNotBlank()) {
                        serverVideoResolver(redirect, lang, serverName)
                    } else {
                        playerDoc.selectFirst("iframe")?.attr("abs:src")?.let {
                            serverVideoResolver(it, lang, serverName)
                        }.orEmpty()
                    }
                }.getOrNull()?.let(videos::addAll)
            }
        }
        return videos.sort()
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    /* -------------------------------- Video extractors ------------------------------- */
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val conventions = listOf(
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "ryderjet", "vidhideplus", "vidhheplus"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg", "earnvids"),
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "uqload" to listOf("uqload"),
        "okru" to listOf("ok.ru", "okru"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2video", "dooood", "d000d", "d0000d"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "yourupload" to listOf("yourupload", "upload"),
        "mp4upload" to listOf("mp4upload"),
        "mixdrop" to listOf("mixdrop", "mdy"),
        "fastream" to listOf("fastream"),
        "upstream" to listOf("upstream"),
        "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay"),
        "waaw" to listOf("waaw", "netu", "hqq"),
    )

    private fun serverVideoResolver(url: String, prefix: String = "", serverName: String = ""): List<Video> {
        // Match against the resolved redirect URL (the site's option labels like
        // "Earnvids"/"TioPlus" don't reliably indicate the real video host).
        val matched = conventions.firstOrNull { (_, names) ->
            names.any { it.lowercase() in url.lowercase() }
        }?.first
        return runCatching {
            when (matched) {
                null -> when {
                    url.contains("emturbovid", true) -> extractEmTurboVideos(url, prefix, serverName)
                    PELISPLUS_PLAYER_HOSTS.any { it in url.lowercase() } -> extractPelisPlusPlayerVideos(url, prefix, serverName)
                    else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                }
                "vidhide" -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$prefix ${optionName(serverName, "VidHide")}:$it" })
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "uqload" -> uqloadExtractor.videosFromUrl(url, prefix)
                "okru" -> okruExtractor.videosFromUrl(url, prefix)
                "doodstream" -> doodExtractor.videosFromUrl(url, "$prefix DoodStream")
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                "mixdrop" -> mixDropExtractor.videoFromUrl(url, prefix = "$prefix ")
                "fastream" -> fastreamExtractor.videosFromUrl(url, prefix = "$prefix Fastream:")
                "upstream" -> upstreamExtractor.videosFromUrl(url, prefix = "$prefix ")
                "vidguard" -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
                "waaw" -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ${optionName(serverName, "Netu")}")
                else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            }
        }.getOrNull() ?: emptyList()
    }

    private fun optionName(serverName: String, fallback: String): String =
        serverName.substringBefore(" - ").ifBlank { fallback }

    private fun extractEmTurboVideos(url: String, prefix: String, serverName: String): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val urlPlay = document.selectFirst("script:containsData(urlPlay)")
            ?.data()
            ?.let { REGEX_EMTURBO_URL_PLAY.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.toHttpUrlOrNull() != null }
            ?: return emptyList()

        return extractHlsVideos(
            urlPlay,
            url,
            videoNameGen = { quality -> "$prefix ${optionName(serverName, "Plus")}:$quality" },
        ).distinctBy { it.url }
    }

    private fun extractPelisPlusPlayerVideos(url: String, prefix: String, serverName: String): List<Video> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val id = httpUrl.fragment?.substringBefore("&")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val playerBaseUrl = "${httpUrl.scheme}://${httpUrl.host}"
        val playerHeaders = headers.newBuilder()
            .set("Referer", "$playerBaseUrl/")
            .build()

        // The video endpoint returns 400 until the initial page/info calls seed the same session flow.
        client.newCall(GET(url, playerHeaders)).execute().close()
        client.newCall(GET("$playerBaseUrl/api/v1/info?id=$id", playerHeaders)).execute().close()

        val encrypted = client.newCall(
            GET("$playerBaseUrl/api/v1/video?id=$id&w=1600&h=900&r=", playerHeaders),
        ).execute().body.string()
        val payload = decryptPelisPlusPlayerPayload(encrypted)
        val jsonObject = json.parseToJsonElement(payload).jsonObject
        val hlsUrl = listOf("source", "cf", "cfNative", "hlsVideoTiktok", "hlsVideoGoogle")
            .firstNotNullOfOrNull { key -> jsonObject[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } }
            ?: return emptyList()
        val name = optionName(serverName, "TioPlus")

        return extractHlsVideos(
            hlsUrl,
            playerBaseUrl,
            videoNameGen = { quality -> "$prefix $name:$quality" },
        )
    }

    private fun extractHlsVideos(
        playlistUrl: String,
        referer: String,
        videoNameGen: (String) -> String,
    ): List<Video> = playlistUtils.extractFromHls(
        playlistUrl,
        referer,
        videoNameGen = videoNameGen,
    )

    private fun decryptPelisPlusPlayerPayload(payload: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(PELISPLUS_PLAYER_AES_KEY, "AES"),
            IvParameterSpec(PELISPLUS_PLAYER_AES_IV),
        )
        return String(cipher.doFinal(payload.hexToByteArray()))
    }

    private fun String.hexToByteArray(): ByteArray {
        val clean = trim()
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    /* ------------------------------------ Filters ----------------------------------- */
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro de género."),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Categoría",
        arrayOf(
            Pair("<seleccionar>", "peliculas"),
            Pair("Películas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "doramas"),
            Pair("Animes", "animes"),
            Pair("Acción", "genero/accion"),
            Pair("Animación", "genero/animacion"),
            Pair("Aventura", "genero/aventura"),
            Pair("Bélica", "genero/belica"),
            Pair("Ciencia ficción", "genero/ciencia-ficcion"),
            Pair("Comedia", "genero/comedia"),
            Pair("Crimen", "genero/crimen"),
            Pair("Documental", "genero/documental"),
            Pair("Dorama", "genero/dorama"),
            Pair("Drama", "genero/drama"),
            Pair("Familia", "genero/familia"),
            Pair("Fantasía", "genero/fantasia"),
            Pair("Historia", "genero/historia"),
            Pair("Kids", "genero/kids"),
            Pair("Misterio", "genero/misterio"),
            Pair("Música", "genero/musica"),
            Pair("Película de TV", "genero/pelicula-de-tv"),
            Pair("Reality", "genero/reality"),
            Pair("Romance", "genero/romance"),
            Pair("Soap", "genero/soap"),
            Pair("Suspense", "genero/suspense"),
            Pair("Terror", "genero/terror"),
            Pair("War & Politics", "genero/war-politics"),
            Pair("Western", "genero/western"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    /* ------------------------------------ Helpers ----------------------------------- */
    private fun String.getLang(): String = when {
        contains("Latino", true) -> "[LAT]"
        contains("Cast", true) -> "[CAST]"
        contains("Sub", true) -> "[SUB]"
        contains("Ingles", true) || contains("English", true) -> "[ENG]"
        else -> ""
    }

    @Serializable
    data class SeasonDto(
        val title: String? = null,
        val image: String? = null,
        val season: Int = 0,
        val episode: Int = 0,
    )

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
