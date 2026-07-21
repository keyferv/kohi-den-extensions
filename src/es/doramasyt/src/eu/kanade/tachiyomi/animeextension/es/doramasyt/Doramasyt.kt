package eu.kanade.tachiyomi.animeextension.es.doramasyt

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.ceil

class Doramasyt : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Doramasyt"

    override val baseUrl = "https://www.doramasyt.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf(
            "Voe",
            "StreamWish",
            "Okru",
            "Filemoon",
            "DoodStream",
            "Cybervynx",
            "MixDrop",
            "Streamtape",
        )
    }

    // ── Details ─────────────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".flex-column h1.text-capitalize")?.text() ?: ""
            description = document.selectFirst(".h-100 .mb-3 p")?.text()
            genre = document.select(".lh-lg span").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".gap-3 img")?.getImageUrl()
            status = document.select(".lh-sm .ms-2").eachText().let { items ->
                when {
                    items.any { it.contains("Finalizado") } -> SAnime.COMPLETED
                    items.any { it.contains("Estreno") } -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
        }
        return animeDetails
    }

    // ── Popular / Latest ────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request {
        if (page == 1) {
            try {
                DoramasytFilters.fetchFilters(client, headers, baseUrl)
            } catch (_: Exception) {}
        }
        return GET("$baseUrl/doramas?p=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".ficha_efecto a")
        val nextPage = document.select(".pagination [rel=\"next\"]").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".title_cap")!!.text()
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/emision?p=$page", headers)

    // ── Search ──────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DoramasytFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/doramas${params.getQuery()}&p=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ── Episodes ────────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val token = document.select("meta[name='csrf-token']").attr("content")
        val capListLink = document.select(".caplist").attr("data-ajax")
        val referer = document.location()

        val detail = getEpisodeDetails(capListLink, token, referer)
        val total = detail.eps.size
        val perPage = detail.perpage ?: return emptyList()
        val pages = (total / perPage).ceilPage()

        return (1..pages).flatMap { page ->
            runCatching {
                getEpisodePage(detail.paginateUrl ?: "", page, token, referer).caps.mapIndexed { idx, ep ->
                    val episodeNumber = (ep.episodio ?: (idx + 1))
                    SEpisode.create().apply {
                        name = "Capítulo $episodeNumber"
                        episode_number = episodeNumber.toFloat()
                        setUrlWithoutDomain(ep.url ?: "")
                    }
                }
            }.getOrDefault(emptyList())
        }.reversed()
    }

    private fun getEpisodeDetails(capListLink: String, token: String, referer: String): EpisodesDto {
        val formBody = FormBody.Builder().add("_token", token).build()
        val request = Request.Builder()
            .url(capListLink)
            .post(formBody)
            .header("accept", "application/json, text/javascript, */*; q=0.01")
            .header("accept-language", "es-419,es;q=0.8")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("origin", baseUrl)
            .header("referer", referer)
            .header("x-requested-with", "XMLHttpRequest")
            .build()

        return client.newCall(request).execute().use { response ->
            json.decodeFromString<EpisodesDto>(response.body.string())
        }
    }

    private fun getEpisodePage(paginateUrl: String, page: Int, token: String, referer: String): EpisodeInfoDto {
        val formBodyEp = FormBody.Builder()
            .add("_token", token)
            .add("p", "$page")
            .build()
        val requestEp = Request.Builder()
            .url(paginateUrl)
            .post(formBodyEp)
            .header("accept", "application/json, text/javascript, */*; q=0.01")
            .header("accept-language", "es-419,es;q=0.8")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("origin", baseUrl)
            .header("referer", referer)
            .header("x-requested-with", "XMLHttpRequest")
            .build()

        return client.newCall(requestEp).execute().use { response ->
            json.decodeFromString<EpisodeInfoDto>(response.body.string())
        }
    }

    // ── Videos ──────────────────────────────────────────────────────────

    /**
     * Data class representing a player button from the episode page.
     * @param dataPlayer Base64-encoded encrypted JSON (iv, value, mac, tag)
     * @param serverName Server name from button text (e.g., "voe", "filemoon", "doodstream")
     */
    private data class PlayerEntry(val dataPlayer: String, val serverName: String)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()

        val playerEntries = document.select("button.play-video[data-player]").map { button ->
            PlayerEntry(
                dataPlayer = button.attr("data-player"),
                serverName = button.text().trim(),
            )
        }.ifEmpty {
            // Fallback: old-style [data-player] elements with plain Base64 URLs
            document.select("[data-player]").map { el ->
                PlayerEntry(
                    dataPlayer = el.attr("data-player"),
                    serverName = "",
                )
            }
        }

        val videoUrls = playerEntries.flatMap { entry ->
            fetchVideoUrlsFromReproductor(entry, referer)
        }

        return videoUrls.flatMap { videoUrl ->
            runCatching { serverVideoResolver(videoUrl) }.getOrDefault(emptyList())
        }
    }

    /**
     * Fetches the /reproductor page for a given player entry and extracts
     * the actual video/embed URL from the response.
     *
     * Flow: button click → /reproductor?video=<base64>&player=<server> →
     *       page renders iframe/video with actual embed URL
     */
    private fun fetchVideoUrlsFromReproductor(entry: PlayerEntry, referer: String): List<String> {
        return try {
            val playerUrl = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("reproductor")
                .addQueryParameter("video", entry.dataPlayer)
                .addQueryParameter("player", entry.serverName)
                .build()
            val playerHeaders = headers.newBuilder().apply {
                set("referer", referer)
                set("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }.build()

            client.newCall(GET(playerUrl, playerHeaders)).execute().use { playerResponse ->
                val playerDoc = playerResponse.asJsoup()
                extractUrlsFromPlayerDocument(playerDoc).ifEmpty {
                    val finalUrl = playerResponse.request.url.toString()
                    if (finalUrl != playerUrl.toString() && isKnownVideoUrl(finalUrl)) {
                        listOf(finalUrl)
                    } else {
                        decodeLegacyPlayerUrl(entry.dataPlayer)
                    }
                }
            }
        } catch (_: Exception) {
            decodeLegacyPlayerUrl(entry.dataPlayer)
        }
    }

    private fun extractUrlsFromPlayerDocument(playerDoc: org.jsoup.nodes.Document): List<String> {
        val urls = mutableListOf<String>()

        playerDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) urls.add(src)
        }

        playerDoc.select("video source[src], video[src]").forEach { video ->
            val src = video.attr("abs:src")
            if (src.isNotBlank()) urls.add(src)
        }

        playerDoc.select("[data-url], [data-src], [data-link]").forEach { el ->
            listOf("data-url", "data-src", "data-link").forEach { attr ->
                val url = el.attr("abs:$attr")
                if (url.isNotBlank() && url.startsWith("http")) urls.add(url)
            }
        }

        playerDoc.select("script").forEach { script ->
            val data = script.data()
            videoUrlRegex.findAll(data).forEach { match -> urls.add(match.value) }
            knownEmbedUrlRegex.findAll(data).forEach { match -> urls.add(match.value) }
            playerAssignmentRegex.findAll(data).forEach { match -> urls.add(match.groupValues[1]) }
        }

        playerDoc.select("meta[http-equiv=refresh]").forEach { meta ->
            refreshUrlRegex.find(meta.attr("content"))?.let { match ->
                urls.add(match.groupValues[1].trim())
            }
        }

        return urls.distinct()
    }

    private fun decodeLegacyPlayerUrl(dataPlayer: String): List<String> {
        return runCatching { String(Base64.decode(dataPlayer, Base64.DEFAULT)) }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }
            ?.let(::listOf)
            ?: emptyList()
    }

    private fun isKnownVideoUrl(url: String): Boolean {
        return url.contains(".mp4") || url.contains(".m3u8") ||
            url.contains("voe") || url.contains("filemoon") ||
            url.contains("dood") || url.contains("streamtape") ||
            url.contains("mixdrop") || url.contains("uqload") ||
            url.contains("ok.ru") || url.contains("streamwish") ||
            url.contains("lulu") || url.contains("mp4upload") ||
            url.contains("savefiles") || url.contains("listeamed") ||
            url.contains("vidply") || url.contains("mega") ||
            url.contains("bysekoze") || url.contains("dsvplay")
    }

    override fun getFilterList(): AnimeFilterList = DoramasytFilters.getFilterList()

    // ── Server / Quality resolution ─────────────────────────────────────

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url)
            embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> okruExtractor.videosFromUrl(url)
            embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") || embedUrl.contains("bysekoze") -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") || embedUrl.contains("wishfast") -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> streamTapeExtractor.videosFromUrl(url)
            embedUrl.contains("cybervynx") || embedUrl.contains("medixiru") -> cybervynxVideosFromUrl(url)
            embedUrl.contains("doodstream") || embedUrl.contains("dood.") || embedUrl.contains("ds2play") || embedUrl.contains("doods.") || embedUrl.contains("dsvplay") -> doodExtractor.videosFromUrl(url)
            embedUrl.contains("filelions") || embedUrl.contains("lion") -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            embedUrl.contains("luluvdo") || embedUrl.contains("lulu") -> luluExtractor.videosFromUrl(url, "")
            embedUrl.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers)
            embedUrl.contains("listeamed") || embedUrl.contains("vembed") || embedUrl.contains("vidguard") || embedUrl.contains("vgfplay") || embedUrl.contains("bembed") -> vidGuardExtractor.videosFromUrl(url, "")
            embedUrl.contains("mix") || embedUrl.contains("mxdrop") -> mixdropExtractor.videosFromUrl(url)
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    private fun cybervynxVideosFromUrl(url: String): List<Video> = runCatching {
        val embedData = client.newCall(GET(url, headers)).execute().use { embedResponse ->
            EmbedData(
                url = embedResponse.request.url.toString(),
                host = embedResponse.request.url.host,
                body = embedResponse.body.string(),
            )
        }

        val masterFromEmbed = extractM3u8Url(embedData.body, embedData.host)
        if (masterFromEmbed != null) {
            return@runCatching cybervynxVideosFromHls(masterFromEmbed, embedData.url)
        }

        val medixiruEmbedUrl = buildMedixiruEmbedUrl(embedData.url)
        if (medixiruEmbedUrl != null && medixiruEmbedUrl != embedData.url) {
            val medixiruHeaders = headers.newBuilder()
                .set("Referer", embedData.url)
                .build()
            client.newCall(GET(medixiruEmbedUrl, medixiruHeaders)).execute().use { medixiruResponse ->
                val medixiruBody = medixiruResponse.body.string()
                val masterFromMedixiru = extractM3u8Url(medixiruBody, medixiruResponse.request.url.host)
                if (masterFromMedixiru != null) {
                    return@runCatching cybervynxVideosFromHls(masterFromMedixiru, medixiruEmbedUrl)
                }
            }
        }

        val dlUrl = extractDlUrl(embedData.body, embedData.host)
            ?: buildMedixiruDlUrl(embedData.url, embedData.host)
            ?: return@runCatching emptyList()

        val dlHeaders = headers.newBuilder()
            .set("Referer", embedData.url)
            .build()
        val masterUrl = client.newCall(GET(dlUrl, dlHeaders)).execute().use { dlResponse ->
            extractM3u8Url(dlResponse.body.string(), dlResponse.request.url.host)
        } ?: return@runCatching emptyList()

        cybervynxVideosFromHls(masterUrl, embedData.url)
    }.getOrElse {
        emptyList()
    }

    private data class EmbedData(val url: String, val host: String, val body: String)

    private fun cybervynxVideosFromHls(masterUrl: String, referer: String): List<Video> {
        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = referer,
            videoNameGen = { quality -> "Cybervynx:$quality" },
        )
    }

    private fun extractM3u8Url(body: String, host: String): String? {
        val unpackedScripts = Jsoup.parse(body).select("script").mapNotNull { script ->
            val scriptBody = script.data()
            when {
                scriptBody.contains("eval(function(p,a,c") -> JsUnpacker.unpackAndCombine(scriptBody)
                scriptBody.contains("m3u8") -> scriptBody
                else -> null
            }
        }

        return sequenceOf(body.unescapePlayerString())
            .plus(unpackedScripts.asSequence().map { it.unescapePlayerString() })
            .mapNotNull { extractM3u8UrlFromText(it, host) }
            .firstOrNull()
    }

    private fun extractM3u8UrlFromText(text: String, host: String): String? {
        m3u8Regex.find(text)?.value?.let { return it }

        val relativeUrl = relativeM3u8Regex.find(text)?.groupValues?.get(1) ?: return null
        return when {
            relativeUrl.startsWith("//") -> "https:$relativeUrl"
            relativeUrl.startsWith("/") -> "https://$host$relativeUrl"
            else -> "https://$host/$relativeUrl"
        }
    }

    private fun extractDlUrl(body: String, host: String): String? {
        val url = dlUrlRegex.find(body.unescapePlayerString())?.groupValues?.get(1) ?: return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "https://$host$url"
            else -> "https://$host/$url"
        }.replace("&amp;", "&")
    }

    private fun buildMedixiruDlUrl(embedUrl: String, host: String): String? {
        val fileCode = Regex("""/(?:e|embed)/([a-zA-Z0-9]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: return null
        val embedHost = embedUrl.toHttpUrl().host
        val dlHost = if (embedHost == "cybervynx.com") "medixiru.com" else host
        val referer = if (dlHost == "medixiru.com") "cybervynx.com" else embedHost
        return "https://$dlHost/dl?op=view&file_code=$fileCode&referer=$referer&hls4=1"
    }

    private fun buildMedixiruEmbedUrl(embedUrl: String): String? {
        val fileCode = Regex("""/(?:e|embed)/([a-zA-Z0-9]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: return null
        val embedHost = embedUrl.toHttpUrl().host
        return if (embedHost == "cybervynx.com") "https://medixiru.com/e/$fileCode" else embedUrl
    }

    private fun String.unescapePlayerString(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
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

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun org.jsoup.nodes.Element.getImageUrl(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> ""
        }
    }

    private fun org.jsoup.nodes.Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("anime.png")
    }

    private fun Double.ceilPage(): Int = if (this % 1 == 0.0) this.toInt() else ceil(this).toInt()

    private val m3u8Regex = Regex("""https?://[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?""")
    private val relativeM3u8Regex = Regex("""["']([^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?)["']""")
    private val dlUrlRegex = Regex("""["']([^"']*/dl\?[^"']+)["']""")
    private val videoUrlRegex = Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8|mpd)""", RegexOption.IGNORE_CASE)
    private val knownEmbedUrlRegex = Regex("""https?://[^\s"'<>]*(?:voe|filemoon|dood|mixdrop|streamtape|uqload|ok\.ru|streamwish|filelions|mxdrop|lulu|mega|mp4upload|savefiles)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    private val playerAssignmentRegex = Regex("""(?:src|file|url|source)\s*[:=]\s*['"](https?://[^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val refreshUrlRegex = Regex("""url=(.+)""", RegexOption.IGNORE_CASE)

    // ── Preferences ─────────────────────────────────────────────────────

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
