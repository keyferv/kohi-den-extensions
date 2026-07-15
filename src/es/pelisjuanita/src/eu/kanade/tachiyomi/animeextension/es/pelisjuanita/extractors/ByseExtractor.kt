package eu.kanade.tachiyomi.animeextension.es.pelisjuanita.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ByseExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val httpUrl = url.toHttpUrl()
        val id = httpUrl.pathSegments.lastOrNull().orEmpty()
        if (id.isBlank()) return emptyList()

        val embedUrl = client.newCall(GET("https://${httpUrl.host}/api/videos/$id/embed/details"))
            .execute()
            .body.string()
            .substringAfter("embed_frame_url", "")
            .substringAfter(":")
            .substringAfter('"')
            .substringBefore('"')

        if (embedUrl.isBlank()) return emptyList()

        val playbackUrl = "https://${embedUrl.toHttpUrl().host}/api/videos/$id/embed/playback"
        val playbackHeaders = headers.newBuilder().apply {
            set("Referer", embedUrl)
            set("X-Embed-Origin", baseUrl.toHttpUrl().host)
            set("X-Embed-Parent", url.encodeUrlPath())
            set("X-Embed-Referer", baseUrl)
            set("Accept", "*/*")
            set("Accept-Language", "es-US,es;q=0.9,en-US;q=0.8,en;q=0.7")
            set("Cache-Control", "no-cache")
            set("Pragma", "no-cache")
            set("Priority", "u=1, i")
            set("Sec-Fetch-Dest", "empty")
            set("Sec-Fetch-Mode", "cors")
            set("Sec-Fetch-Site", "same-origin")
            set("Sec-Fetch-Storage-Access", "active")
        }.build()

        return client.newCall(GET(playbackUrl, playbackHeaders)).execute()
            .parseAs<PlaybackResponseDto>()
            .let { decrypt(it.playback) }
            .substringAfter("sources")
            .substringAfter("[")
            .substringBefore("]")
            .split("},")
            .flatMap { source ->
                val videoUrl = source.substringAfter("\"url\":\"", "")
                    .substringBefore('"')
                    .takeIf(String::isNotBlank)
                    ?.replace("\\u0026", "&")
                    ?: return@flatMap emptyList()

                playlistUtils.extractFromHls(
                    videoUrl,
                    videoNameGen = { "$prefix Byse:$it" },
                )
            }
    }

    private fun decrypt(input: PlaybackDto): String {
        val keyBytes = input.keyParts
            .map { decodeBase64Url(it) }
            .fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, decodeBase64Url(input.iv))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return String(cipher.doFinal(decodeBase64Url(input.payload)), Charsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input
            .replace('-', '+')
            .replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    @Serializable
    private data class PlaybackResponseDto(
        val playback: PlaybackDto,
    )

    @Serializable
    private data class PlaybackDto(
        val algorithm: String,
        val iv: String,
        val payload: String,
        val key_parts: List<String>,
        val expires_at: String,
        val decrypt_keys: DecryptKeysDto,
        val iv2: String,
        val payload2: String,
    ) {
        val keyParts: List<String>
            get() = key_parts
    }

    @Serializable
    private data class DecryptKeysDto(
        val edge_1: String,
        val edge_2: String,
        val legacy_fallback: String,
    )
}

private fun String.encodeUrlPath(): String {
    val uri = URI(this)
    val encodedPath = uri.rawPath
        .split("/")
        .joinToString("/") { segment ->
            if (segment.isEmpty()) {
                ""
            } else {
                URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
            }
        }

    return URI(
        uri.scheme,
        uri.rawAuthority,
        encodedPath,
        uri.rawQuery,
        uri.rawFragment,
    ).toString()
}
