package eu.kanade.tachiyomi.multisrc.animekaitheme.dto

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// ========================= Shared DTOs =========================

data class VideoCode(
    val type: String,
    val serverId: String,
    val serverName: String,
)

data class VideoData(
    val iframe: String,
    val serverName: String,
)

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
data class IframeResponse(
    val result: IframeDto,
)

@Serializable
data class IframeDto(
    val url: String,
    val skip: SkipDto? = null,
)

@Serializable
data class SkipDto(
    val intro: List<Int>? = null,
    val outro: List<Int>? = null,
)
