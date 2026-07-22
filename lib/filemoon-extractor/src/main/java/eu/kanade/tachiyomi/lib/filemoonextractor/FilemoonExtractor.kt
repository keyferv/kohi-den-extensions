package eu.kanade.tachiyomi.lib.filemoonextractor

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class FilemoonExtractor(private val client: OkHttpClient, private val preferences: SharedPreferences? = null) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json: Json by injectLazy()

    private val jsonMediaType = "application/json".toMediaType()

    fun videosFromUrl(
        url: String,
        prefix: String = "Filemoon - ",
        headers: Headers? = null,
        referer: String? = null,
    ): List<Video> {
        return try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val mediaId = extractMediaId(httpUrl)
            if (mediaId.isBlank()) {
                Log.w("FilemoonExtractor", "Could not extract media ID from $url")
                return emptyList()
            }
            Log.d("FilemoonExtractor", "Starting extraction host=$host mediaId=$mediaId")

            val userAgent = headers?.get("User-Agent")
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

            val baseHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("User-Agent", userAgent)
                set("Accept", "*/*")
                set("Accept-Language", "en-US,en;q=0.9")
                set("Cache-Control", "no-cache")
                set("Pragma", "no-cache")
            }.build()

            val embedUrl = getEmbedUrl(host, mediaId, baseHeaders)
                ?: return emptyList()
            Log.d("FilemoonExtractor", "Resolved embed URL host=${embedUrl.toHttpUrl().host}")

            val embedHost = embedUrl.toHttpUrl().host
            val pageReferer = referer ?: embedUrl
            val origin = "https://$embedHost"

            val apiHeaders = baseHeaders.newBuilder().apply {
                set("Referer", pageReferer)
                set("Origin", origin)
            }.build()

            val data = challengeFlow(mediaId, apiHeaders, origin, userAgent, url)

            if (data == null) {
                Log.w("FilemoonExtractor", "No data from challenge flow for $url")
                return emptyList()
            }

            val sources = extractSources(data)
            if (sources.isNullOrEmpty()) {
                Log.w("FilemoonExtractor", "No sources found in response for $url")
                return emptyList()
            }
            Log.d("FilemoonExtractor", "Found ${sources.size} source(s)")

            val videoHeaders = baseHeaders.newBuilder().apply {
                set("Referer", origin)
                removeAll("Origin")
            }.build()

            sources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<Video>()
                val quality = source.label ?: "Unknown"

                playlistUtils.extractFromHls(
                    streamUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix${it.replace("Video", quality)}" },
                )
            }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Failed to extract video from $url", e)
            emptyList()
        }
    }

    private fun extractMediaId(httpUrl: okhttp3.HttpUrl): String {
        val segments = httpUrl.pathSegments
        if (segments.size > 1 && (segments[0] == "e" || segments[0] == "eyi" || segments[0] == "d" || segments[0] == "download" || segments[0].startsWith("j"))) {
            return segments[1]
        }
        return segments.lastOrNull { it.isNotEmpty() } ?: return ""
    }

    private fun getEmbedUrl(host: String, mediaId: String, headers: Headers): String? {
        val detailsUrl = "https://$host/api/videos/$mediaId/embed/details"
        try {
            val response = client.newCall(GET(detailsUrl, headers)).execute()
            val code = response.code
            val body = response.use { it.body.string() }
            Log.d("FilemoonExtractor", "Details response code=$code host=$host mediaId=$mediaId")
            val embedFrameUrl = body
                .substringAfter("embed_frame_url", "")
                .substringAfter(":")
                .substringAfter('"')
                .substringBefore('"')
            if (embedFrameUrl.isBlank()) {
                Log.w("FilemoonExtractor", "Empty embed_frame_url from details for $host/$mediaId bodyPrefix=${body.take(120)}")
                return null
            }
            return embedFrameUrl
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Failed to get embed URL from details", e)
            return null
        }
    }

    private fun challengeFlow(
        mediaId: String,
        apiHeaders: Headers,
        origin: String,
        userAgent: String,
        pageUrl: String,
    ): PlaybackResponse? {
        val challengeUrl = "$origin/api/videos/access/challenge"
        val challengeData = try {
            client.newCall(POST(challengeUrl, apiHeaders, "{}".toRequestBody(jsonMediaType)))
                .execute().use {
                    Log.d("FilemoonExtractor", "Challenge response code=${it.code}")
                    json.decodeFromString<ChallengeResponse>(it.body.string())
                }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Challenge request failed", e)
            return null
        }

        val challengeId = challengeData.challengeId
        val nonce = challengeData.nonce

        val (privateKey, publicKeyJwk) = generateEcKeyPair()
        val signature = signNonce(privateKey, nonce)
        val clientFingerprint = generateClientFingerprint(userAgent)

        val attestUrl = "$origin/api/videos/access/attest"
        val attestPayload = AttestRequest(
            viewerId = "",
            deviceId = "",
            challengeId = challengeId,
            nonce = nonce,
            signature = signature,
            publicKey = publicKeyJwk,
            client = clientFingerprint,
            storage = emptyMap(),
            attributes = mapOf("entropy" to "low"),
        )
        val attestBody = json.encodeToString(attestPayload).toRequestBody(jsonMediaType)

        val attestData = try {
            client.newCall(POST(attestUrl, apiHeaders, attestBody))
                .execute().use {
                    Log.d("FilemoonExtractor", "Attest response code=${it.code}")
                    json.decodeFromString<AttestResponse>(it.body.string())
                }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Attest request failed", e)
            return null
        }

        val token = attestData.token
        val viewerId = attestData.viewerId
        val deviceId = attestData.deviceId
        val confidence = attestData.confidence

        val fingerprintPayload = FingerprintPayload(
            fingerprint = FingerprintData(
                token = token,
                viewerId = viewerId,
                deviceId = deviceId,
                confidence = confidence,
            ),
        )

        val captchaUrl = "$origin/api/videos/$mediaId/embed/captcha"
        val refererUrl = apiHeaders["Referer"] ?: pageUrl
        val refererHttpUrl = try {
            refererUrl.toHttpUrl()
        } catch (e: Exception) {
            pageUrl.toHttpUrl()
        }
        val refererHost = refererHttpUrl.host
        val refererOrigin = "${refererHttpUrl.scheme}://$refererHost"

        val embedExtraHeaders = Headers.Builder().apply {
            set("X-Embed-Origin", refererHost)
            set("X-Embed-Referer", "$refererOrigin/")
            set("X-Embed-Parent", pageUrl)
            set("Cookie", "byse_viewer_id=$viewerId; byse_device_id=$deviceId")
        }.build()

        val captchaHeaders = apiHeaders.newBuilder().apply {
            for (index in 0 until embedExtraHeaders.size) {
                set(embedExtraHeaders.name(index), embedExtraHeaders.value(index))
            }
        }.build()

        val captchaBody = json.encodeToString(fingerprintPayload).toRequestBody(jsonMediaType)
        val captchaData = try {
            client.newCall(POST(captchaUrl, captchaHeaders, captchaBody))
                .execute().use {
                    Log.d("FilemoonExtractor", "Captcha response code=${it.code}")
                    json.decodeFromString<CaptchaResponse>(it.body.string())
                }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Captcha request failed", e)
            return null
        }

        val powNonce = captchaData.powNonce
        val powDifficulty = captchaData.powDifficulty
        val powToken = captchaData.powToken

        val solution = solvePow(powNonce, powDifficulty)

        val verifyUrl = "$origin/api/videos/$mediaId/embed/captcha/verify"
        val verifyPayload = VerifyRequest(
            powToken = powToken,
            solution = solution,
            fingerprint = fingerprintPayload.fingerprint,
        )
        val verifyBody = json.encodeToString(verifyPayload).toRequestBody(jsonMediaType)

        val verifyData = try {
            client.newCall(POST(verifyUrl, captchaHeaders, verifyBody))
                .execute().use {
                    Log.d("FilemoonExtractor", "Captcha verify response code=${it.code}")
                    json.decodeFromString<VerifyResponse>(it.body.string())
                }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Captcha verify failed", e)
            return null
        }

        if (verifyData.status != "ok") {
            Log.e("FilemoonExtractor", "PoW verification failed: ${verifyData.status}")
            return null
        }

        val captchaToken = verifyData.token
        if (captchaToken.isNullOrBlank()) {
            Log.e("FilemoonExtractor", "Captcha token is null or blank")
            return null
        }

        val playbackUrl = "$origin/api/videos/$mediaId/embed/playback"
        val playbackHeaders = captchaHeaders.newBuilder().apply {
            set("X-Captcha-Token", captchaToken)
        }.build()
        val playbackBody = json.encodeToString(fingerprintPayload).toRequestBody(jsonMediaType)

        return try {
            client.newCall(POST(playbackUrl, playbackHeaders, playbackBody))
                .execute().use {
                    Log.d("FilemoonExtractor", "Playback response code=${it.code}")
                    json.decodeFromString<PlaybackResponse>(it.body.string())
                }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Playback request failed", e)
            null
        }
    }

    private fun extractSources(data: PlaybackResponse): List<VideoSource>? {
        if (!data.sources.isNullOrEmpty()) {
            return data.sources
        }
        if (data.playback != null) {
            try {
                val decrypted = decrypt(data.playback)
                val decryptedJson = json.decodeFromString<PlaybackResponse>(decrypted)
                return decryptedJson.sources
            } catch (e: Exception) {
                Log.e("FilemoonExtractor", "Decryption failed", e)
            }
        }
        return null
    }

    private fun decrypt(input: PlaybackData): String {
        val keyBytes = when (input.version) {
            null -> input.keyParts.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            else -> {
                val v = input.version.toIntOrNull() ?: 1
                val parts = input.keyParts
                if (parts.size >= v) {
                    val selected = listOf(parts[v - 1], parts[parts.size - v])
                    selected.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
                } else {
                    input.keyParts.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
                }
            }
        }

        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)

        if (payloadBytes.size < 16) {
            Log.e("FilemoonExtractor", "Payload too short for AES-GCM tag (${payloadBytes.size} bytes)")
            return ""
        }

        val ciphertext = payloadBytes.copyOfRange(0, payloadBytes.size - 16)
        val tag = payloadBytes.copyOfRange(payloadBytes.size - 16, payloadBytes.size)
        val encrypted = ciphertext + tag

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(encrypted)
        return String(decryptedBytes, StandardCharsets.UTF_8)
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

    private fun encodeBase64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun generateEcKeyPair(): Pair<java.security.PrivateKey, EcJwk> {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val x = publicKey.w.affineX.toByteArray()
        val y = publicKey.w.affineY.toByteArray()
        val xPadded = if (x.size < 32) ByteArray(32 - x.size) + x else x.copyOfRange(max(0, x.size - 32), x.size)
        val yPadded = if (y.size < 32) ByteArray(32 - y.size) + y else y.copyOfRange(max(0, y.size - 32), y.size)
        val jwk = EcJwk(
            alg = "ES256",
            crv = "P-256",
            ext = true,
            keyOps = listOf("verify"),
            kty = "EC",
            x = encodeBase64Url(xPadded),
            y = encodeBase64Url(yPadded),
        )
        return keyPair.private to jwk
    }

    private fun signNonce(privateKey: java.security.PrivateKey, nonce: String): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(nonce.toByteArray(StandardCharsets.UTF_8))
        val signed = signature.sign()
        return encodeBase64Url(signed)
    }

    private fun generateClientFingerprint(userAgent: String): ClientFingerprint {
        val random = SecureRandom()
        fun randHash(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return encodeBase64Url(bytes)
        }
        return ClientFingerprint(
            userAgent = userAgent,
            pixelRatio = 1,
            screenWidth = 1920,
            screenHeight = 1080,
            colorDepth = 24,
            languages = listOf("en-US", "en"),
            timezone = "America/New_York",
            hardwareConcurrency = 8,
            touchPoints = 0,
            webglVendor = "Google Inc. (Intel)",
            webglRenderer = "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)",
            canvasHash = randHash(),
            audioHash = randHash(),
            webglParamsHash = randHash(),
            fontsHash = randHash(),
            codecsHash = randHash(),
            mediaDevices = "ai0ao0vi0",
            pointerType = "fine,hover",
            extra = mapOf("vendor" to "", "appVersion" to "5.0 (X11)"),
        )
    }

    private fun solvePow(nonce: String, difficulty: Int, maxIterations: Int = 200000): String {
        val prefix = ("$nonce:").toByteArray(Charsets.ISO_8859_1)
        val bufferSize = 512
        val bufferMask = 511
        val initConst = 2654435761L
        val finalConst = 2246822519L
        val mask32 = 0xFFFFFFFFL

        fun rotl(value: Long, shift: Int): Long = ((value shl shift) or (value ushr (32 - shift))) and mask32

        val buf = LongArray(bufferSize)
        for (counter in 0..maxIterations) {
            val counterBytes = counter.toString().toByteArray(Charsets.ISO_8859_1)
            val input = prefix + counterBytes

            var s0 = 1779033703L
            var s1 = 3144134277L
            var s2 = 1013904242L
            var s3 = 2773480762L

            for (b in input) {
                s0 = (s0 + (b.toInt() and 0xFF)) and mask32
                s0 = rotl(s0, 7)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            repeat(8) {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            for (i in 0 until bufferSize) {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
                buf[i] = (s0 xor s2) and mask32
            }

            repeat(2) {
                for (si in 0 until bufferSize) {
                    val a = (buf[si] and bufferMask.toLong()).toInt()
                    var c = (buf[si] + buf[a]) and mask32
                    c = rotl(c, 13)
                    c = (c xor ((buf[(si + 1) and bufferMask] * initConst) and mask32)) and mask32
                    buf[si] = c
                    s0 = (s0 xor c) and mask32
                    s0 = (s0 + s1) and mask32
                    s3 = rotl(s3 xor s0, 16)
                    s2 = (s2 + s3) and mask32
                    s1 = rotl(s1 xor s2, 12)
                    s0 = (s0 + s1) and mask32
                    s3 = rotl(s3 xor s0, 8)
                    s2 = (s2 + s3) and mask32
                    s1 = rotl(s1 xor s2, 7)
                }
            }

            s0 = (s0 + s1) and mask32
            s3 = rotl(s3 xor s0, 16)
            s2 = (s2 + s3) and mask32
            s1 = rotl(s1 xor s2, 12)
            s0 = (s0 + s1) and mask32
            s3 = rotl(s3 xor s0, 8)
            s2 = (s2 + s3) and mask32
            s1 = rotl(s1 xor s2, 7)

            var outVal = s0
            for (ci in 0 until 64) {
                val d = buf[ci]
                outVal = (outVal + d) and mask32
                outVal = rotl(outVal, 5)
                outVal = (outVal xor ((d * finalConst) and mask32)) and mask32
            }
            outVal = (outVal xor s2) and mask32

            val leading = outVal.toInt().countLeadingZeroBits()
            if (leading >= difficulty) {
                return counter.toString()
            }
        }
        throw RuntimeException("PoW solver: no solution found in $maxIterations iterations")
    }

    @Serializable
    data class ChallengeResponse(
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
    )

    @Serializable
    data class AttestRequest(
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
        val signature: String,
        @SerialName("public_key") val publicKey: EcJwk,
        val client: ClientFingerprint,
        val storage: Map<String, String>,
        val attributes: Map<String, String>,
    )

    @Serializable
    data class AttestResponse(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    data class EcJwk(
        val alg: String,
        val crv: String,
        val ext: Boolean,
        @SerialName("key_ops") val keyOps: List<String>,
        val kty: String,
        val x: String,
        val y: String,
    )

    @Serializable
    data class ClientFingerprint(
        @SerialName("user_agent") val userAgent: String,
        @SerialName("pixel_ratio") val pixelRatio: Int,
        @SerialName("screen_width") val screenWidth: Int,
        @SerialName("screen_height") val screenHeight: Int,
        @SerialName("color_depth") val colorDepth: Int,
        val languages: List<String>,
        val timezone: String,
        @SerialName("hardware_concurrency") val hardwareConcurrency: Int,
        @SerialName("touch_points") val touchPoints: Int,
        @SerialName("webgl_vendor") val webglVendor: String,
        @SerialName("webgl_renderer") val webglRenderer: String,
        @SerialName("canvas_hash") val canvasHash: String,
        @SerialName("audio_hash") val audioHash: String,
        @SerialName("webgl_params_hash") val webglParamsHash: String,
        @SerialName("fonts_hash") val fontsHash: String,
        @SerialName("codecs_hash") val codecsHash: String,
        @SerialName("media_devices") val mediaDevices: String,
        @SerialName("pointer_type") val pointerType: String,
        val extra: Map<String, String>,
    )

    @Serializable
    data class FingerprintPayload(
        val fingerprint: FingerprintData,
    )

    @Serializable
    data class FingerprintData(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    data class CaptchaResponse(
        @SerialName("pow_nonce") val powNonce: String,
        @SerialName("pow_difficulty") val powDifficulty: Int,
        @SerialName("pow_token") val powToken: String,
    )

    @Serializable
    data class VerifyRequest(
        @SerialName("pow_token") val powToken: String,
        val solution: String,
        val fingerprint: FingerprintData,
    )

    @Serializable
    data class VerifyResponse(
        val status: String,
        val token: String? = null,
    )

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        @SerialName("key_parts") val keyParts: List<String>,
        val payload: String,
        val version: String? = null,
    )

    @Serializable
    data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default",
    )

    companion object {
        fun addSubtitlePref(screen: PreferenceScreen) {
            EditTextPreference(screen.context).apply {
                key = PREF_SUBTITLE_KEY
                title = "Filemoon subtitle preference"
                summary = "Leave blank to use all subs"
                setDefaultValue(PREF_SUBTITLE_DEFAULT)
            }.also(screen::addPreference)
        }

        private const val PREF_SUBTITLE_KEY = "pref_filemoon_sub_lang_key"
        private const val PREF_SUBTITLE_DEFAULT = "eng"
    }
}

fun String.encodeUrlPath(): String {
    val uri = URI(this)

    val encodedPath = uri.rawPath
        .split("/")
        .joinToString("/") { segment ->
            if (segment.isEmpty()) {
                ""
            } else URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                .replace("+", "%20")
        }

    return URI(
        uri.scheme,
        uri.rawAuthority,
        encodedPath,
        uri.rawQuery,
        uri.rawFragment,
    ).toString()
}
