package eu.kanade.tachiyomi.lib.omniembedextractor

import android.util.Log
import eu.kanade.tachiyomi.lib.amazonextractor.AmazonExtractor
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.lib.buzzheavierextractor.BuzzheavierExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.fusevideoextractor.FusevideoExtractor
import eu.kanade.tachiyomi.lib.kwikextractor.KwikExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.pixeldrainextractor.PixelDrainExtractor
import eu.kanade.tachiyomi.lib.rumbleextractor.RumbleExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamdavextractor.StreamDavExtractor
import eu.kanade.tachiyomi.lib.streamhubextractor.StreamHubExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamplayextractor.StreamPlayExtractor
import eu.kanade.tachiyomi.lib.streamsilkextractor.StreamSilkExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamupextractor.StreamupExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient

private const val TAG = "OmniEmbedExtractor"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

private enum class EmbedType {
    OKRU,
    VK,
    DOOD,
    STREAMTAPE,
    MP4UPLOAD,
    STREAMWISH,
    FILEMOON,
    KWIK,

    VOE,
    STREAMLARE,
    STREAMHUB,
    VIDGUARD,
    SENDVID,
    STREAMDAV,
    STREAMSILK,
    VIDO,
    VUDEO,
    UPSTREAM,
    SIBNET,
    RUMBLE,
    AMAZON,
    FUSEVIDEO,
    LULU,
    BUZZHEAVIER,
    FASTREAM,
    VIDBOM,
    PIXELDRAIN,
    MIXDROP,

    VIDMOLY,
    VIDHIDE,
    STREAMPLAY,
    STREAMUP,
    UQLOAD,
    BLOGGER,
    UNKNOWN,
}

private val EMBED_DOMAIN_MAP = mapOf(
    "ok.ru" to EmbedType.OKRU,
    "vk.com" to EmbedType.VK,
    "doodstream.com" to EmbedType.DOOD,
    "dood.wf" to EmbedType.DOOD,
    "doodstream" to EmbedType.DOOD,
    "streamtape.com" to EmbedType.STREAMTAPE,
    "streamtape.net" to EmbedType.STREAMTAPE,
    "mp4upload.com" to EmbedType.MP4UPLOAD,
    "streamwish.com" to EmbedType.STREAMWISH,
    "niramirus.com" to EmbedType.STREAMWISH,
    "medixiru.com" to EmbedType.STREAMWISH,
    "filemoon.sx" to EmbedType.FILEMOON,
    "filemoon.to" to EmbedType.FILEMOON,
    "kwik.cx" to EmbedType.KWIK,
    "voe.sx" to EmbedType.VOE,
    "voe.to" to EmbedType.VOE,
    "voe.sg" to EmbedType.VOE,
    "voe.pm" to EmbedType.VOE,
    "voe.sh" to EmbedType.VOE,
    "voe.st" to EmbedType.VOE,
    "voe.cloud" to EmbedType.VOE,
    "streamlare.com" to EmbedType.STREAMLARE,
    "slwatch.co" to EmbedType.STREAMLARE,
    "streamhub.gg" to EmbedType.STREAMHUB,
    "vidguard.app" to EmbedType.VIDGUARD,
    "vidguard.io" to EmbedType.VIDGUARD,
    "vgf.play" to EmbedType.VIDGUARD,
    "sendvid.com" to EmbedType.SENDVID,
    "streamdav.com" to EmbedType.STREAMDAV,
    "streamsilk.com" to EmbedType.STREAMSILK,
    "vido.lol" to EmbedType.VIDO,
    "vidoza.net" to EmbedType.VIDO,
    "vudeo.co" to EmbedType.VUDEO,
    "upstream.to" to EmbedType.UPSTREAM,
    "sibnet.ru" to EmbedType.SIBNET,
    "rumble.com" to EmbedType.RUMBLE,
    "amazon.com" to EmbedType.AMAZON,
    "fusevideo.com" to EmbedType.FUSEVIDEO,
    "luluvdo.com" to EmbedType.LULU,
    "buzzheavier.com" to EmbedType.BUZZHEAVIER,
    "fastream.to" to EmbedType.FASTREAM,
    "vidbom.com" to EmbedType.VIDBOM,
    "vidbem.com" to EmbedType.VIDBOM,
    "vidbm.com" to EmbedType.VIDBOM,
    "vedpom.com" to EmbedType.VIDBOM,
    "pixeldrain.com" to EmbedType.PIXELDRAIN,
    "mixdrop.ag" to EmbedType.MIXDROP,
    "mixdrop.co" to EmbedType.MIXDROP,
    "mixdrop.to" to EmbedType.MIXDROP,
    "vidmoly.to" to EmbedType.VIDMOLY,
    "vidmoly.biz" to EmbedType.VIDMOLY,
    "vidhide.com" to EmbedType.VIDHIDE,
    "streamplay.co.in" to EmbedType.STREAMPLAY,
    "streamup.cc" to EmbedType.STREAMUP,
    "uqload.co" to EmbedType.UQLOAD,
    "uqload.is" to EmbedType.UQLOAD,
    "blogger.com" to EmbedType.BLOGGER,
    "bp.blogspot.com" to EmbedType.BLOGGER,
)

class OmniEmbedExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val kwikExtractor by lazy { KwikExtractor(client, headers) }

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val streamHubExtractor by lazy { StreamHubExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val streamDavExtractor by lazy { StreamDavExtractor(client) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client, headers) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }
    private val amazonExtractor by lazy { AmazonExtractor(client) }
    private val fusevideoExtractor by lazy { FusevideoExtractor(client, headers) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val buzzheavierExtractor by lazy { BuzzheavierExtractor(client, headers) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamPlayExtractor by lazy { StreamPlayExtractor(client, headers) }
    private val streamupExtractor by lazy { StreamupExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    fun extractVideos(
        embedUrl: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> {
        val embedType = detectEmbedType(embedUrl)
        Log.d(TAG, "extractVideos: type=$embedType url=${embedUrl.take(100)}")

        return try {
            when (embedType) {
                EmbedType.OKRU -> extractFromOkru(embedUrl, qualityLabel)
                EmbedType.VK -> extractFromVk(embedUrl, qualityLabel)
                EmbedType.DOOD -> extractFromDood(embedUrl, qualityLabel)
                EmbedType.STREAMTAPE -> extractFromStreamtape(embedUrl, qualityLabel)
                EmbedType.MP4UPLOAD -> extractFromMp4upload(embedUrl, qualityLabel)
                EmbedType.STREAMWISH -> extractFromStreamwish(embedUrl, qualityLabel)
                EmbedType.FILEMOON -> extractFromFilemoon(embedUrl, qualityLabel)
                EmbedType.KWIK -> extractFromKwik(embedUrl, qualityLabel, subtitles)
                EmbedType.VOE -> extractFromVoe(embedUrl, qualityLabel)
                EmbedType.STREAMLARE -> extractFromStreamlare(embedUrl, qualityLabel)
                EmbedType.STREAMHUB -> extractFromStreamhub(embedUrl, qualityLabel)
                EmbedType.VIDGUARD -> extractFromVidguard(embedUrl, qualityLabel)
                EmbedType.SENDVID -> extractFromSendvid(embedUrl, qualityLabel)
                EmbedType.STREAMDAV -> extractFromStreamdav(embedUrl, qualityLabel)
                EmbedType.STREAMSILK -> extractFromStreamsilk(embedUrl, qualityLabel)
                EmbedType.VIDO -> extractFromVido(embedUrl, qualityLabel)
                EmbedType.VUDEO -> extractFromVudeo(embedUrl, qualityLabel)
                EmbedType.UPSTREAM -> extractFromUpstream(embedUrl, qualityLabel)
                EmbedType.SIBNET -> extractFromSibnet(embedUrl, qualityLabel)
                EmbedType.RUMBLE -> extractFromRumble(embedUrl, qualityLabel)
                EmbedType.AMAZON -> extractFromAmazon(embedUrl, qualityLabel)
                EmbedType.FUSEVIDEO -> extractFromFusevideo(embedUrl, qualityLabel)
                EmbedType.LULU -> extractFromLulu(embedUrl, qualityLabel)
                EmbedType.BUZZHEAVIER -> extractFromBuzzheavier(embedUrl, qualityLabel)
                EmbedType.FASTREAM -> extractFromFastream(embedUrl, qualityLabel)
                EmbedType.VIDBOM -> extractFromVidbom(embedUrl)
                EmbedType.PIXELDRAIN -> extractFromPixeldrain(embedUrl, qualityLabel)
                EmbedType.MIXDROP -> extractFromMixdrop(embedUrl, qualityLabel)
                EmbedType.VIDMOLY -> extractFromVidmoly(embedUrl, qualityLabel)
                EmbedType.VIDHIDE -> extractFromVidhide(embedUrl, qualityLabel)
                EmbedType.STREAMPLAY -> extractFromStreamplay(embedUrl, qualityLabel)
                EmbedType.STREAMUP -> extractFromStreamup(embedUrl, qualityLabel)
                EmbedType.UQLOAD -> extractFromUqload(embedUrl, qualityLabel)
                EmbedType.BLOGGER -> extractFromBlogger(embedUrl, qualityLabel, subtitles)
                EmbedType.UNKNOWN -> {
                    Log.w(TAG, "Unknown embed domain: ${embedUrl.take(100)}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract from ${embedType.name}: ${e.message}")
            emptyList()
        }
    }

    private fun detectEmbedType(url: String): EmbedType {
        val host = try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            url.substringAfter("://").substringBefore("/").substringBefore("?").lowercase()
        }

        for ((domain, type) in EMBED_DOMAIN_MAP) {
            if (host == domain || host.endsWith(".$domain")) {
                return type
            }
        }
        return EmbedType.UNKNOWN
    }

    private fun extractFromOkru(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        okruExtractor.videosFromUrl(embedUrl, prefix = qualityLabel, headers = headers)
    }

    private fun extractFromVk(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        vkExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    private fun extractFromDood(embedUrl: String, qualityLabel: String): List<Video> =
        doodExtractor.videosFromUrl(embedUrl, quality = qualityLabel)

    private fun extractFromStreamtape(embedUrl: String, qualityLabel: String): List<Video> =
        streamtapeExtractor.videosFromUrl(embedUrl, quality = qualityLabel)

    private fun extractFromMp4upload(embedUrl: String, qualityLabel: String): List<Video> {
        val mp4Headers = Headers.Builder()
            .set("Referer", "https://mp4upload.com/")
            .build()
        return mp4uploadExtractor.videosFromUrl(embedUrl, mp4Headers, prefix = qualityLabel)
    }

    private fun extractFromStreamwish(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        streamwishExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    private fun extractFromFilemoon(embedUrl: String, qualityLabel: String): List<Video> {
        val fmHeaders = Headers.Builder()
            .set("Referer", embedUrl)
            .set("User-Agent", USER_AGENT)
            .build()
        return filemoonExtractor.videosFromUrl(embedUrl, prefix = "$qualityLabel ", headers = fmHeaders)
    }

    private fun extractFromKwik(
        embedUrl: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> = kwikExtractor.videosFromUrl(
        url = embedUrl,
        prefix = qualityLabel,
        subtitleList = subtitles,
    )

    private fun extractFromVoe(embedUrl: String, qualityLabel: String): List<Video> =
        voeExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromStreamlare(embedUrl: String, qualityLabel: String): List<Video> =
        streamlareExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromStreamhub(embedUrl: String, qualityLabel: String): List<Video> =
        streamHubExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromVidguard(embedUrl: String, qualityLabel: String): List<Video> =
        vidGuardExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromSendvid(embedUrl: String, qualityLabel: String): List<Video> =
        sendvidExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromStreamdav(embedUrl: String, qualityLabel: String): List<Video> =
        streamDavExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromStreamsilk(embedUrl: String, qualityLabel: String): List<Video> =
        streamSilkExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromVido(embedUrl: String, qualityLabel: String): List<Video> =
        vidoExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromVudeo(embedUrl: String, qualityLabel: String): List<Video> =
        vudeoExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromUpstream(embedUrl: String, qualityLabel: String): List<Video> =
        upstreamExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromSibnet(embedUrl: String, qualityLabel: String): List<Video> =
        sibnetExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromRumble(embedUrl: String, qualityLabel: String): List<Video> =
        rumbleExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromAmazon(embedUrl: String, qualityLabel: String): List<Video> =
        amazonExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromFusevideo(embedUrl: String, qualityLabel: String): List<Video> =
        fusevideoExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromLulu(embedUrl: String, qualityLabel: String): List<Video> =
        luluExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromBuzzheavier(embedUrl: String, qualityLabel: String): List<Video> =
        buzzheavierExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromFastream(embedUrl: String, qualityLabel: String): List<Video> =
        fastreamExtractor.videosFromUrl(embedUrl, prefix = "$qualityLabel ")

    private fun extractFromVidbom(embedUrl: String): List<Video> =
        vidBomExtractor.videosFromUrl(embedUrl)

    private fun extractFromPixeldrain(embedUrl: String, qualityLabel: String): List<Video> =
        pixelDrainExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromMixdrop(embedUrl: String, qualityLabel: String): List<Video> =
        mixDropExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)

    private fun extractFromVidmoly(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        vidMolyExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    private fun extractFromVidhide(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        vidHideExtractor.videosFromUrl(embedUrl, videoNameGen = { quality -> "$qualityLabel VidHide - $quality" })
    }

    private fun extractFromStreamplay(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        streamPlayExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    private fun extractFromStreamup(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        streamupExtractor.getVideosFromUrl(embedUrl, headers, qualityLabel)
    }

    private fun extractFromUqload(embedUrl: String, qualityLabel: String): List<Video> = runBlocking {
        uqloadExtractor.videosFromUrl(embedUrl, prefix = qualityLabel)
    }

    private fun extractFromBlogger(
        embedUrl: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> = runBlocking {
        bloggerExtractor.videosFromUrl(embedUrl, headers, suffix = qualityLabel)
    }
}
