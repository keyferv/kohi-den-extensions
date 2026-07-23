package eu.kanade.tachiyomi.animeextension.es.doramaslatinox

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay.UriPartFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DoramasLatinoX : DooPlay(
    "es",
    "DoramasLatinoX",
    "https://doramaslatinox.com",
) {

    override val episodeMovieText = "Película"
    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    override val additionalInfoItems = listOf("Nombre", "Año", "Temporadas", "Tipo", "Audio", "País")

    override val genresListMessage = "Género"
    override val selectFilterText = "<Seleccionar>"
    override val genreFilterHeader = "NOTA: Los filtros se ignorarán si se usa búsqueda por texto"
    override val genresMissingWarning = "Presiona 'Reset' para intentar mostrar los géneros"

    private val seriesUrlFilter: (String) -> Boolean = { url -> url.contains("/series/") }

    private val seriesCardSelector = "article.item.tvshows > div.poster"

    override fun popularAnimeRequest(page: Int): Request = GET(
        categoryPageUrl("serie", page),
        headers,
    )

    override fun popularAnimeSelector() = seriesCardSelector

    override fun popularAnimeNextPageSelector() = "div.pagination a.arrow_pag"

    override fun latestUpdatesRequest(page: Int): Request = GET(
        categoryPageUrl("dorama", page),
        headers,
    )

    override fun latestUpdatesSelector() = seriesCardSelector

    override fun latestUpdatesNextPageSelector() = "div.pagination a.arrow_pag"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        when {
            query.isBlank() -> {
                filters.firstOrNull { it.state != 0 }?.let {
                    val filter = it as UriPartFilter
                    GET(
                        buildString {
                            append("$baseUrl/${filter.toUriPart()}")
                            if (page > 1) append("/page/$page")
                        },
                        headers,
                    )
                } ?: popularAnimeRequest(page)
            }
            else -> GET(if (page == 1) "$baseUrl/?s=$query" else "$baseUrl/page/$page/?s=$query", headers)
        }

    override fun searchAnimeNextPageSelector() = "a[href*=page]:not(a[href*=page/1/])"

    override fun searchAnimeSelector() = "div.result-item div.image a"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val url = response.request.url.toString()

        val allElements = if ("/?s=" in url || "/page/" in url) {
            document.select(searchAnimeSelector()).map { element ->
                searchAnimeFromElement(element)
            }
        } else {
            document.select(latestUpdatesSelector()).map { element ->
                popularAnimeFromElement(element)
            }
        }

        val seriesOnly = allElements.filter { anime ->
            seriesUrlFilter(anime.url)
        }

        val hasNextPage = document.select(searchAnimeNextPageSelector())
            .any { it.attr("href").contains("/page/") }

        return AnimesPage(seriesOnly, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector())
            .map(::popularAnimeFromElement)
            .filter { seriesUrlFilter(it.url) }
        return AnimesPage(animes, document.hasNextPage(response.request.url.toString()))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        fetchGenresList()
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .filter { seriesUrlFilter(it.url) }
        return AnimesPage(animes, document.hasNextPage(response.request.url.toString()))
    }

    private fun categoryPageUrl(type: String, page: Int): String {
        return if (page == 1) {
            "$baseUrl/tipo/$type/"
        } else {
            "$baseUrl/tipo/$type/page/$page/"
        }
    }

    private fun Document.hasNextPage(currentUrl: String): Boolean {
        val currentPage = Regex("/page/(\\d+)/")
            .find(currentUrl)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 1
        val nextPage = currentPage + 1

        return select("link[rel=next], div.pagination a[href], a[href]").any { element ->
            val href = element.attr("abs:href").ifBlank { element.attr("href") }
            href.contains("/page/$nextPage/")
        }
    }

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val poster = element.selectFirst("img") ?: element.select("img").first()!!
        val linkEl = element.selectFirst("a") ?: element.parent()?.selectFirst("a")
        setUrlWithoutDomain(linkEl?.attr("href") ?: element.attr("href"))
        title = poster.attr("alt").ifBlank {
            element.selectFirst("h3")?.text() ?: poster.attr("title").ifBlank { "Sin título" }
        }
        thumbnail_url = poster.getImageUrl()
    }

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")
        title = img?.attr("alt")?.ifBlank {
            img?.attr("title")
        }.orEmpty()
        thumbnail_url = img?.getImageUrl()
    }

    override fun Document.getDescription(): String {
        fun String.cleanDescription(): String = replace(Regex("\\s+"), " ")
            .trim()

        selectFirst("$additionalInfoSelector p")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?.let { return "$it\n" }

        selectFirst(additionalInfoSelector)
            ?.text()
            ?.cleanDescription()
            ?.takeIf { it.isNotBlank() }
            ?.let { return "$it\n" }

        selectFirst("meta[name=description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { return "$it\n" }

        selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { return "$it\n" }

        return ""
    }

    override fun Element.getInfo(substring: String): String? {
        val matched = allElements.firstOrNull { el ->
            el.ownText().contains(substring, ignoreCase = true) &&
                el.tagName() == "b"
        } ?: return null

        val key = matched.ownText().replace(Regex("\\s*:\\s*$"), "").trim()
        val value = matched.nextSibling()
            ?.toString()
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&nbsp;", " ")
            ?.trim()
            ?: return null

        return "\n$key: $value"
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val seriesSlug = doc.location()
            .substringAfter("/series/")
            .substringBefore("/")
        Log.d(TAG, "episodeList source=${doc.location().safeUrl()} seriesSlug=$seriesSlug")

        val result = mutableListOf<SEpisode>()
        var page = 1
        var foundAnyPage = false
        var hasMorePages = true
        while (hasMorePages) {
            try {
                val episodesUrl = "$baseUrl/wp-json/wp/v2/episodes?per_page=100&page=$page"
                Log.d(TAG, "episodeList REST path=${episodesUrl.safeUrl()}")
                val episodesResponse = client.newCall(GET(episodesUrl, headers)).execute()
                val episodesJson = org.json.JSONArray(episodesResponse.body.string())
                Log.d(TAG, "episodeList REST page=$page entries=${episodesJson.length()}")

                var foundOnPage = false
                for (i in 0 until episodesJson.length()) {
                    val ep = episodesJson.getJSONObject(i)
                    val slug = ep.getString("slug")
                    if (!slug.startsWith("$seriesSlug-")) continue

                    foundAnyPage = true
                    foundOnPage = true

                    val link = ep.getString("link")
                    val title = ep.getJSONObject("title").getString("rendered")
                    val date = ep.getString("date")

                    val seasonAndEp = Regex("(\\d+)x(\\d+)$").find(slug)
                    val season = seasonAndEp?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
                    val epNum = seasonAndEp?.groupValues?.get(2)?.toFloatOrNull() ?: 1F

                    val cleanTitle = title
                        .replace("&#215;", "\u00d7")
                        .replace("&#8217;", "\u2019")
                        .substringAfter(": ").trim()
                        .ifEmpty { "Episodio ${epNum.toInt()}" }

                    result.add(
                        SEpisode.create().apply {
                            setUrlWithoutDomain(link)
                            episode_number = epNum
                            name = "$episodeSeasonPrefix ${season.toInt()} \u00d7 ${epNum.toInt()} - $cleanTitle"
                            date_upload = runCatching {
                                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).parse(date)?.time
                            }.getOrNull() ?: 0L
                        },
                    )
                    if (result.size <= 5) {
                        Log.d(
                            TAG,
                            "episodeList match slug=$slug season=${season.toInt()} episode=${epNum.toInt()} name=${cleanTitle.take(80)}",
                        )
                    }
                }

                val totalPages = episodesResponse.header("X-WP-TotalPages")?.toIntOrNull() ?: page
                page++
                hasMorePages = page <= totalPages && (!foundAnyPage || foundOnPage)
            } catch (e: Exception) {
                Log.d(TAG, "episodeList REST page $page failed: ${e.message.safeLogMessage()}")
                break
            }
        }
        Log.d(TAG, "episodeList matched=${result.size}")

        return if (result.isEmpty()) {
            Log.d(TAG, "episodeList fallback single episode used")
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                episode_number = 1F
                name = episodeMovieText
            }.let(::listOf)
        } else {
            Log.d(TAG, "episodeList returning=${result.size}")
            result.reversed()
        }
    }

    override fun videoListSelector() = "li.dooplay_player_option"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select(videoListSelector())
        val referer = response.request.url.toString()
        val embedHeaders = headersBuilder().set("Referer", referer).build()
        Log.d(TAG, "videoList episode=${referer.safeUrl()} players=${players.size}")

        val videos = players.flatMap { player ->
            runCatching {
                val post = player.attr("data-post")
                val nume = player.attr("data-nume")
                val type = player.attr("data-type")
                val serverLabel = player.selectFirst("span.title")?.text().orEmpty()
                Log.d(TAG, "player start label=$serverLabel post=$post type=$type nume=$nume")

                val embedUrl = getEmbedUrl(post, type, nume, referer)
                if (embedUrl.isBlank()) {
                    Log.d(TAG, "player empty embed label=$serverLabel post=$post type=$type nume=$nume")
                    return@flatMap emptyList()
                }

                val embedHost = embedUrl.toHttpUrlOrNull()?.host.orEmpty()
                Log.d(
                    TAG,
                    "player embed label=$serverLabel host=$embedHost path=${embedUrl.safeUrl()} p2pHash=${embedUrl.substringAfter('#', "").isNotBlank()}",
                )

                when {
                    "abyssplayer.com" in embedHost -> {
                        Log.d(TAG, "player skipped label=$serverLabel host=$embedHost reason=abyssplayer_deferred")
                        emptyList()
                    }
                    "p2pplay.online" in embedHost -> {
                        Log.d(TAG, "player route label=$serverLabel host=$embedHost route=p2pplay_direct")
                        val result = extractP2pPlayVideos(embedUrl, serverLabel)
                        Log.d(TAG, "player result label=$serverLabel host=$embedHost count=${result.size}")
                        result
                    }
                    else -> {
                        Log.d(TAG, "player route label=$serverLabel host=$embedHost route=universal_default")
                        val result = universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = serverLabel)
                        Log.d(TAG, "player result label=$serverLabel host=$embedHost count=${result.size}")
                        result
                    }
                }
            }.getOrElse { throwable ->
                Log.d(TAG, "player failed ${throwable::class.simpleName}: ${throwable.message.safeLogMessage()}")
                emptyList()
            }
        }.sort()
        Log.d(TAG, "videoList totalVideos=${videos.size}")
        return videos
    }

    private fun String.toHttpUrlOrNull(): okhttp3.HttpUrl? {
        return runCatching { toHttpUrl() }.getOrNull()
    }

    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun extractP2pPlayVideos(embedUrl: String, prefix: String): List<Video> {
        val embedHttpUrl = embedUrl.toHttpUrlOrNull() ?: return emptyList()
        val videoId = embedHttpUrl.fragment
            ?.substringBefore('&')
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val origin = "${embedHttpUrl.scheme}://${embedHttpUrl.host}"
        val p2pHeaders = headersBuilder()
            .set("User-Agent", P2PPLAY_USER_AGENT)
            .set("Referer", embedUrl)
            .set("Origin", origin)
            .build()

        Log.d(TAG, "p2pplay direct start host=${embedHttpUrl.host} id=$videoId")

        val videoUrl = "$origin/api/v1/video?id=$videoId&w=1600&h=900&r=${baseUrl.toHttpUrl().host}"
        val encryptedBody = client.newCall(GET(videoUrl, p2pHeaders))
            .execute()
            .body.string()
        val decryptedBody = decryptP2pPlayResponse(encryptedBody)
        val videoJson = JSONObject(decryptedBody)

        val videoHeaders = headersBuilder()
            .set("User-Agent", P2PPLAY_USER_AGENT)
            .set("Referer", embedUrl)
            .set("Origin", origin)
            .build()

        return buildList {
            videoJson.optString("cfNative")
                .takeIf { it.isNotBlank() }
                ?.let { add(Video(it, "$prefix P2PPlay HLS", it, videoHeaders)) }

            videoJson.optString("source")
                .takeIf { it.isNotBlank() }
                ?.addP2pPlayToken(videoJson.optJSONObject("pk"))
                ?.let { add(Video(it, "$prefix P2PPlay HLS Backup", it, videoHeaders)) }
        }.also { videos ->
            Log.d(TAG, "p2pplay direct videos=${videos.size}")
        }
    }

    private fun decryptP2pPlayResponse(encryptedHex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(P2PPLAY_AES_KEY.toByteArray(), "AES"),
            IvParameterSpec(P2PPLAY_AES_IV.toByteArray()),
        )
        return cipher.doFinal(encryptedHex.trim().hexToByteArray()).toString(Charsets.UTF_8)
    }

    private fun String.addP2pPlayToken(pk: JSONObject?): String {
        val key = pk?.optString("k").orEmpty()
        val expires = pk?.optString("kx").orEmpty()
        if (key.isBlank() || expires.isBlank() || "k=" in this || "/v4/" !in this) return this
        val separator = if ('?' in this) "&" else "?"
        return "$this${separator}k=$key&kx=$expires"
    }

    private fun String.hexToByteArray(): ByteArray {
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun getEmbedUrl(post: String, type: String, nume: String, referer: String): String {
        val restHeaders = headersBuilder().set("Referer", referer).build()
        val restUrl = "$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume"
        Log.d(TAG, "getEmbedUrl REST path=${restUrl.safeUrl()} referer=${referer.safeUrl()}")
        val restBody = client.newCall(GET(restUrl, restHeaders))
            .execute().body.string()

        val embedUrl = restBody
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")

        if (embedUrl.isNotBlank()) {
            Log.d(TAG, "getEmbedUrl REST found host=${embedUrl.toHttpUrlOrNull()?.host.orEmpty()} path=${embedUrl.safeUrl()}")
            return embedUrl
        }
        Log.d(TAG, "getEmbedUrl REST empty, trying AJAX fallback")

        val formBody = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()

        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
        val ajaxEmbedUrl = client.newCall(POST(ajaxUrl, restHeaders, formBody))
            .execute().body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
        Log.d(TAG, "getEmbedUrl AJAX found=${ajaxEmbedUrl.isNotBlank()} host=${ajaxEmbedUrl.toHttpUrlOrNull()?.host.orEmpty()}")
        return ajaxEmbedUrl
    }

    private fun String.safeUrl(): String = runCatching {
        val parsed = toHttpUrl()
        "${parsed.host}${parsed.encodedPath}"
    }.getOrDefault(take(120).substringBefore('?').substringBefore('#'))

    private fun String?.safeLogMessage(): String = orEmpty()
        .replace(Regex("https?://\\S+")) { match -> match.value.safeUrl() }
        .take(160)

    override fun String.toDate() = 0L

    private companion object {
        const val TAG = "DoramasLatinoX"
        const val P2PPLAY_AES_KEY = "kiemtienmua911ca"
        const val P2PPLAY_AES_IV = "1234567890oiuytr"
        const val P2PPLAY_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
