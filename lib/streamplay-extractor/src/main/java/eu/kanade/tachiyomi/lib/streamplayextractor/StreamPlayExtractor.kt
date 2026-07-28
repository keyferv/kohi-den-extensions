package eu.kanade.tachiyomi.lib.streamplayextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parseAs
import eu.kanade.tachiyomi.util.asJsoup
import dev.datlag.jsunpacker.JsUnpacker
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class StreamPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).awaitSuccess().asJsoup()

        return document.select("#servers a").parallelCatchingFlatMap { element ->
            extractAndDecodeFromDocument(element.attr("abs:href"), "$prefix ${element.text()}")
        }
    }

    private val kakenRegex by lazy { Regex("window\\.kaken ?= ?\"([^\"]+)\";") }

    private suspend fun extractAndDecodeFromDocument(url: String, prefix: String): List<Video> {
        val document = client.newCall(
            GET(url, headers),
        ).awaitSuccess().asJsoup()

        val packedScript = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")

        val kaken = if (packedScript != null) {
            val scriptContent = packedScript.data()
            JsUnpacker.unpackAndCombine(scriptContent)?.let {
                kakenRegex.find(it)?.groupValues?.get(1)
            }
        } else {
            document.selectFirst("script:containsData(window.kaken)")
                ?.data()?.let {
                    kakenRegex.find(it)?.groupValues?.get(1)
                }
        } ?: return emptyList()

        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Origin", "${httpUrl.scheme}://${httpUrl.host}")
            add("Referer", url)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val apiResponse = client.newCall(
            POST(
                "https://play.streamplay.co.in/api/",
                headers = apiHeaders,
                body = kaken.toRequestBody("text/plain".toMediaType()),
            ),
        ).awaitSuccess().parseAs<APIResponse>()

        val subtitleList = apiResponse.tracks?.let { t ->
            t.map { Track(it.file, it.label) }
        } ?: emptyList()

        val videos = apiResponse.sources.parallelCatchingFlatMap { source ->
            val sourceUrl = fixUrl(source.videoUrl) ?: return@parallelCatchingFlatMap emptyList()
            if (source.type == "hls" && sourceUrl.endsWith("master.m3u8")) {
                playlistUtils.extractFromHls(
                    playlistUrl = sourceUrl,
                    referer = url,
                    subtitleList = subtitleList,
                    videoNameGen = { q -> "$prefix $q (StreamPlay)" },
                )
            } else {
                listOf(
                    Video(
                        sourceUrl,
                        "$prefix (StreamPlay) Original",
                        sourceUrl,
                        headers = headers.newBuilder()
                            .set("Referer", url)
                            .build(),
                        subtitleTracks = subtitleList,
                    ),
                )
            }
        }
        return videos
    }

    private fun fixUrl(url: String): String? {
        val resolved = url.toHttpUrlOrNull()
        if (resolved != null) return resolved.toString()
        return null
    }

    @Serializable
    data class APIResponse(
        val sources: List<SourceObject>,
        val tracks: List<TrackObject>? = null,
    ) {
        @Serializable
        data class SourceObject(
            val file: String,
            val label: String,
            val type: String,
        ) {
            val videoUrl: String
                get() = file.replace("master.txt", "master.m3u8")
        }

        @Serializable
        data class TrackObject(
            val file: String,
            val label: String,
        )
    }
}
